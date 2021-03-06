package org.gatt_ip;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanRecord;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.gatt_ip.lescanner.BluetoothLEScanner;
import org.gatt_ip.lescanner.BluetoothLEScannerForLollipop;
import org.gatt_ip.lescanner.BluetoothLEScannerForMR2;
import org.gatt_ip.util.Util;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Created by vensi on 9/25/15.
 */
public class BluetoothLEService extends InterfaceService {
    public static final String CCC_UUID = "00002902-0000-1000-8000-00805f9b34fb";
    private static final String TAG = BluetoothLEService.class.getName();

    private List<BluetoothGatt> m_connected_devices;
    private List<BluetoothDevice> m_available_devices;

    private BluetoothLEScanner m_le_scanner;

    private BluetoothAdapter m_bluetooth_adapter;


    public class BluetoothLEBinder extends InterfaceService.InterfaceBinder {
        @Override
        public InterfaceService getService() {
            return BluetoothLEService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getBluetoothAdapterState();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if(m_le_scanner != null) {
            m_le_scanner.stopLEScan();
        }

        if (m_connected_devices != null) {
            Log.d("connected devices", "" + m_connected_devices.size());

            for (BluetoothGatt gatt : m_connected_devices) {
                gatt.disconnect();
            }
        }
        if(m_receiver != null)
            unregisterReceiver(m_receiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        m_available_devices = new ArrayList<>();
        m_connected_devices = new ArrayList<>();

        registerReceiver(m_receiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new BluetoothLEBinder();
    }

    public List<BluetoothDevice> getAvailableDevices()
    {
        return m_available_devices;
    }

    public List<BluetoothGatt> getConnectedDevices()
    {
        return m_connected_devices;
    }

    public void startDeviceDiscovery(int timeout, boolean duplicates)
    {
        if( (m_le_scanner == null) || ( m_le_scanner!= null && !m_le_scanner.mScanning) ) {
            int apiVersion = android.os.Build.VERSION.SDK_INT;

            if (apiVersion >= Build.VERSION_CODES.LOLLIPOP) {
                m_le_scanner = new BluetoothLEScannerForLollipop(this.getApplicationContext(), duplicates);
            } else {
                m_le_scanner = new BluetoothLEScannerForMR2(this.getApplicationContext(), duplicates);
            }
            m_le_scanner.registerScanListener(m_le_scan_listener);
            m_le_scanner.startLEScan();
        }
    }

    public void stopDeviceDiscovery()
    {
        m_le_scanner.stopLEScan();
    }

    @Override
    public void connectDevice(String deviceIdentifier) {
        for (BluetoothDevice bdevice : m_available_devices) {
            if (bdevice.getAddress().equals(deviceIdentifier)) {
                bdevice.connectGatt(getApplicationContext(), true, mGattCallback);
            }
        }
    }

    @Override
    public void disconnectDevice(String deviceIdentifier) {
        try {
            Iterator<BluetoothGatt> iterator = m_connected_devices.iterator();
            while (iterator.hasNext()){
                BluetoothGatt gatt = iterator.next();
                BluetoothDevice device = gatt.getDevice();
                if (device.getAddress().equals(deviceIdentifier)) {
                    gatt.disconnect();
                }
            }
        } catch (ConcurrentModificationException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void getDeviceServices(String deviceIdentifier) {
        BluetoothGatt gatt = Util.peripheralIn(m_connected_devices, deviceIdentifier);

        if (gatt == null) {
            for(DeviceEventListener listener : m_listeners) {
                listener.onError(Error.DEVICE_NOT_FOUND);
            }
        } else {
            gatt.discoverServices();
        }
    }

    @Override
    public void getDeviceAttributes(String serviceIdentifier) {
        UUID serviceUUID = UUID.fromString(Util.ConvertUUID_16bitInto128bit(serviceIdentifier));
        HashMap<BluetoothGatt, BluetoothGattService> requestedPeripheralAndService = Util.serviceIn(m_connected_devices, serviceUUID);
        Set<BluetoothGatt> keySet = requestedPeripheralAndService.keySet();
        BluetoothGatt gatt = null;

        for (BluetoothGatt bGatt : keySet) {
            gatt = bGatt;
        }

        if (gatt == null) {
            for(DeviceEventListener listener : m_listeners) {
                listener.onError(Error.DEVICE_NOT_FOUND);
            }
        } else {
            BluetoothGattService requestedService = requestedPeripheralAndService.get(gatt);

            if (requestedService == null) {
                for(DeviceEventListener listener : m_listeners) {
                    listener.onError(Error.DEVICE_SERVICE_NOT_FOUND);
                }
            } else {
                for (DeviceEventListener listener : m_listeners) {
                    listener.onDeviceAttributes(gatt.getDevice().getAddress(), requestedService.getUuid().toString(), requestedService.getCharacteristics());
                }
            }
        }
    }

    @Override
    public void getDeviceAttributeDescriptors(String attributeIdentifier) {
        BluetoothGatt gatt = null;
        UUID characteristicsUUID = UUID.fromString(Util.ConvertUUID_16bitInto128bit(attributeIdentifier));
        HashMap<BluetoothGatt, BluetoothGattCharacteristic> peripheralAndCharacteristic = Util.characteristicIn(m_connected_devices, characteristicsUUID);
        Set<BluetoothGatt> keySet = peripheralAndCharacteristic.keySet();

        for (BluetoothGatt bGatt : keySet) {
            gatt = bGatt;
        }

        if (gatt == null) {
            for(DeviceEventListener listener : m_listeners) {
                listener.onError(Error.DEVICE_NOT_FOUND);
            }
        } else {
            BluetoothGattCharacteristic characteristic = peripheralAndCharacteristic.get(gatt);

            if (characteristic == null) {
                for(DeviceEventListener listener : m_listeners) {
                    listener.onError(Error.DEVICE_ATTRIBUTES_NOT_FOUND);
                }
            } else {
                String peripheralUUID = gatt.getDevice().getAddress().toUpperCase(Locale.getDefault());
                String serviceUUID = characteristic.getService().getUuid().toString().toUpperCase(Locale.getDefault());
                String characteristicUUID = characteristic.getUuid().toString().toUpperCase(Locale.getDefault());

                for(DeviceEventListener listener : m_listeners)
                    listener.onDeviceAttributeDescriptors(peripheralUUID, serviceUUID, characteristicUUID, characteristic.getDescriptors());
            }
        }
    }

    @Override
    public void getDeviceAttributeValue(String attributeIdentifier) {
        UUID characteristicUUID = UUID.fromString(attributeIdentifier);
        if(m_connected_devices.size() > 0 && characteristicUUID != null) {
            HashMap<BluetoothGatt, BluetoothGattCharacteristic> characteristics = Util.characteristicIn(m_connected_devices, characteristicUUID);
            Set<BluetoothGatt> keySet = characteristics.keySet();

            for (BluetoothGatt gatt : keySet) {
                BluetoothGattCharacteristic characteristic = characteristics.get(gatt);

                if (characteristic == null) {
                    for (DeviceEventListener listener : m_listeners) {
                        listener.onError(Error.DEVICE_ATTRIBUTES_NOT_FOUND);
                    }
                } else {
                    if(!gatt.readCharacteristic(characteristic)){
                        Log.e("Read", " ### Failed to Read the characteristic :(");
                        for (DeviceEventListener listener : m_listeners) {
                            listener.onError(Error.ATTRIBUTE_READ_FAILED);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void getDeviceAttributeNotifications(String attributeIdentifier, boolean enable) {
        UUID characteristicUUID = UUID.fromString(attributeIdentifier);
        if(m_connected_devices.size()>0 && characteristicUUID!=null) {

            HashMap<BluetoothGatt, BluetoothGattCharacteristic> characteristics = Util.characteristicIn(m_connected_devices, characteristicUUID);
            Set<BluetoothGatt> keySet = characteristics.keySet();

            for (BluetoothGatt gatt : keySet) {
                BluetoothGattCharacteristic characteristic = characteristics.get(gatt);

                if (characteristic == null) {
                    for(DeviceEventListener listener : m_listeners) {
                        listener.onError(Error.DEVICE_ATTRIBUTES_NOT_FOUND);
                    }
                } else {
                    boolean status = false;

                    // client characteristic configuration.
                    UUID descUUID = UUID.fromString(CCC_UUID);
                    BluetoothGattDescriptor desc = characteristic.getDescriptor(descUUID);

                    // check whether characteristic having notify or indicate property
                    if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0) {
                        if(desc!=null){
                            status = desc.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                        }
                    } else {
                        if(desc!=null){
                            status = desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        }
                    }

                    if (!status) {
                        Log.e(TAG, "### Descriptor setvalue fail ###");
                    } else {
                        if (!gatt.writeDescriptor(desc)) {
                            Log.e(TAG, "failed to Write the value into descriptor.");
                            for (DeviceEventListener listener : m_listeners) {
                                listener.onError(Error.ATTRIBUTE_DESCRIPTOR_WRITE_FAILED);
                            }
                        }
                    }
                    // set notification for characteristic
                    if (!gatt.setCharacteristicNotification(characteristic, enable)) {
                        Log.e(TAG, "Failed to set the notifications for the Characteristic.");
                        for (DeviceEventListener listener : m_listeners) {
                            listener.onError(Error.ATTRIBUTE_NOTIFICATION_FAILED);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void writeDeviceAttributeValue(String attributeIdentifier, String writeType, byte[] data) {
        UUID characteristicUUID = UUID.fromString(attributeIdentifier);

        if(m_connected_devices.size()>0 && characteristicUUID!=null){

            HashMap<BluetoothGatt, BluetoothGattCharacteristic> characteristics = Util.characteristicIn(m_connected_devices, characteristicUUID);
            if(characteristics!=null) {
                Set<BluetoothGatt> keySet = characteristics.keySet();

                for (BluetoothGatt gatt : keySet) {
                    BluetoothGattCharacteristic characteristic = characteristics.get(gatt);

                    if (characteristic == null) {
                        for(DeviceEventListener listener : m_listeners) {
                            listener.onError(Error.DEVICE_ATTRIBUTES_NOT_FOUND);
                        }
                    } else {
                        // TODO: Handle remaining write types, if any
                        int writType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                        if((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0){
                            writType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                            for (DeviceEventListener listener : m_listeners) {
                                listener.sendResponseForWriteTypeNoReponse();
                            }
                        }
                        characteristic.setWriteType(writType);
                        // call to write characteristic
                        characteristic.setValue(data);
                        if(!gatt.writeCharacteristic(characteristic)){
                            Log.e(TAG, "failed to write the Characteristic value.");
                            for (DeviceEventListener listener : m_listeners) {
                                listener.onError(Error.ATTRIBUTE_WRITE_FAILED);
                            }
                        }
                    }
                }
            }
        }else{
            for (DeviceEventListener listener : m_listeners) {
                listener.noConnectedDevices();
            }
        }
    }

    @Override
    public void getDeviceAttributeDescriptorValue(String attributeDescriptorIdentifier, String attributeIdentifier, String serviceIdentifier) {
        UUID descriptorUUID = UUID.fromString(attributeDescriptorIdentifier);
        UUID characteristicsUUID = UUID.fromString(attributeIdentifier);
        BluetoothGatt gatt = null;

        if(m_connected_devices.size()>0 && descriptorUUID!=null && characteristicsUUID!=null) {

            HashMap<BluetoothGatt, BluetoothGattCharacteristic> peripheralAndCharacteristic = Util.characteristicIn(m_connected_devices, characteristicsUUID);
            Set<BluetoothGatt> keySet = peripheralAndCharacteristic.keySet();

            for (BluetoothGatt bGatt : keySet) {
                gatt = bGatt;
            }

            if (gatt == null) {
                for(DeviceEventListener listener : m_listeners) {
                    listener.onError(Error.DEVICE_NOT_FOUND);
                }
            } else {
                BluetoothGattCharacteristic characteristic = peripheralAndCharacteristic.get(gatt);

                if (characteristic == null) {
                    for(DeviceEventListener listener : m_listeners) {
                        listener.onError(Error.DEVICE_ATTRIBUTES_NOT_FOUND);
                    }
                } else {
                    List<BluetoothGattDescriptor> descriptors = characteristic.getDescriptors();
                    ListIterator<BluetoothGattDescriptor> listitr = null;
                    listitr = descriptors.listIterator();
                    while (listitr.hasNext()) {
                        BluetoothGattDescriptor descriptor = listitr.next();
                        if(descriptor.getUuid().equals(descriptorUUID))
                        {
                            if(!gatt.readDescriptor(descriptor)){
                                Log.e(TAG, "failed to Read the descriptor value.");
                                for (DeviceEventListener listener : m_listeners) {
                                    listener.onError(Error.ATTRIBUTE_DESCRIPTOR_READ_FAILED);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void writeDeviceAttributeDescriptorValue(String attributeDescriptorIdentifier, byte[] data) {
        UUID descriptorUUID = UUID.fromString(attributeDescriptorIdentifier);
        if(m_connected_devices.size()>0 && descriptorUUID!=null) {

            HashMap<BluetoothGatt, BluetoothGattDescriptor> descriptors = Util.descriptorIn(m_connected_devices, descriptorUUID);
            Set<BluetoothGatt> keySet = descriptors.keySet();

            for (BluetoothGatt gatt : keySet) {
                BluetoothGattDescriptor desc = descriptors.get(gatt);

                if (desc == null) {
                    for(DeviceEventListener listener : m_listeners) {
                        listener.onError(Error.ATTRIBUTE_DESCRIPTOR_NOT_FOUND);
                    }
                } else {
                    desc.setValue(data);
                    if(!gatt.writeDescriptor(desc)){
                        Log.e(TAG, "failed to Write the value into descriptor.");
                        for (DeviceEventListener listener : m_listeners) {
                            listener.onError(Error.ATTRIBUTE_DESCRIPTOR_WRITE_FAILED);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void getDeviceSignal(String deviceIdentifier) {
        BluetoothGatt gatt = Util.peripheralIn(m_connected_devices, deviceIdentifier);

        if (gatt == null) {
            for(DeviceEventListener listener : m_listeners) {
                listener.onError(Error.DEVICE_NOT_FOUND);
            }
        } else {
            gatt.readRemoteRssi();
        }
    }

    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,int newState) {
            BluetoothDevice device = gatt.getDevice();
            switch (status){
                case BluetoothGatt.GATT_SUCCESS:
                    if(newState == BluetoothProfile.STATE_CONNECTED) {
                        if (m_connected_devices.contains(gatt)) {
                            for (int i = 0; i < m_connected_devices.size(); i++) {
                                if (m_connected_devices.get(i).equals(gatt))
                                    m_connected_devices.set(i, m_connected_devices.get(i));
                            }
                        } else {
                            m_connected_devices.add(gatt);
                        }
                        for(DeviceEventListener listener : m_listeners)
                            listener.onDeviceConnection(device.getName(), device.getAddress());
                    } else if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // delete connected device from list after disconnect device.
                        if (m_connected_devices.contains(gatt)) {
                            for (int i = 0; i < m_connected_devices.size(); i++) {
                                if (m_connected_devices.get(i).equals(gatt))
                                    m_connected_devices.remove(i);
                            }
                        }
                        gatt.close();
                        for(DeviceEventListener listener : m_listeners) {
                            listener.onDeviceDisconnection(device.getName(), device.getAddress().toUpperCase(Locale.getDefault()));
                        }
                    }
                    break;

                case BluetoothGatt.GATT_FAILURE:
                    gatt.disconnect();
                    gatt.close();

                    for(DeviceEventListener listener : m_listeners) {
                        listener.onDeviceConnectionFailure(device.getName(), device.getAddress().toUpperCase(Locale.getDefault()), status);
                    }
                    break;

                case 133:
                    gatt.disconnect();
                    gatt.close();

                    for(DeviceEventListener listener : m_listeners) {
                        listener.onDeviceConnectionFailure(device.getName(), device.getAddress().toUpperCase(Locale.getDefault()), status);
                    }
                    break;

                case 8:
                    if (m_connected_devices.contains(gatt)) {
                        for (int i = 0; i < m_connected_devices.size(); i++) {
                            if (m_connected_devices.get(i).equals(gatt))
                                m_connected_devices.remove(i);
                        }
                    }
                    gatt.disconnect();
                    gatt.close();

                    for(DeviceEventListener listener : m_listeners) {
                        listener.onDeviceUnexpectedlyDisconnection(device.getName(), device.getAddress().toUpperCase(Locale.getDefault()), status);
                    }
                    break;

                case 19:
                    if (m_connected_devices.contains(gatt)) {
                        for (int i = 0; i < m_connected_devices.size(); i++) {
                            if (m_connected_devices.get(i).equals(gatt))
                                m_connected_devices.remove(i);
                        }
                    }
                    gatt.disconnect();
                    gatt.close();

                    for(DeviceEventListener listener : m_listeners) {
                        listener.onDeviceUnexpectedlyDisconnection(device.getName(), device.getAddress().toUpperCase(Locale.getDefault()), status);
                    }
                    break;

                default:

                    if (m_connected_devices.contains(gatt)) {
                        for (int i = 0; i < m_connected_devices.size(); i++) {
                            if (m_connected_devices.get(i).equals(gatt))
                                m_connected_devices.remove(i);
                        }
                    }
                    gatt.disconnect();
                    gatt.close();

                    for(DeviceEventListener listener : m_listeners) {
                        listener.onDeviceUnexpectedlyDisconnection(device.getName(), device.getAddress().toUpperCase(Locale.getDefault()), status);
                    }
                    break;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            for(DeviceEventListener listener : m_listeners) {
                listener.onDeviceServices(gatt.getDevice().getAddress().toUpperCase(Locale.getDefault()), gatt.getServices(), status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            String deviceIdentifier = gatt.getDevice().getAddress().toUpperCase(Locale.getDefault());
            String serviceIdentifier = characteristic.getService().getUuid().toString().toUpperCase(Locale.getDefault());
            String attributeIdentifier = characteristic.getUuid().toString().toUpperCase(Locale.getDefault());
            byte[] attributeValue = characteristic.getValue();

            for(DeviceEventListener listener : m_listeners) {
                listener.onDevcieAttributeRead(deviceIdentifier, serviceIdentifier, attributeIdentifier, attributeValue, 0);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            String deviceIdentifier = gatt.getDevice().getAddress().toUpperCase(Locale.getDefault());
            String serviceIdentifier = characteristic.getService().getUuid().toString().toUpperCase(Locale.getDefault());
            String attributeIdentifier = characteristic.getUuid().toString().toUpperCase(Locale.getDefault());

            for(DeviceEventListener listener : m_listeners) {
                listener.onDeviceAttributeWrite(deviceIdentifier, serviceIdentifier, attributeIdentifier, 0);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,BluetoothGattCharacteristic characteristic) {
            String deviceIdentifier = gatt.getDevice().getAddress().toUpperCase(Locale.getDefault());
            String serviceIdentifier = characteristic.getService().getUuid().toString().toUpperCase(Locale.getDefault());
            String attributeIdentifier = characteristic.getUuid().toString().toUpperCase(Locale.getDefault());
            byte[] attributeValue = characteristic.getValue();

            for(DeviceEventListener listener : m_listeners) {
                listener.onDeviceAttributeChanged(deviceIdentifier, serviceIdentifier, attributeIdentifier, attributeValue, 0);
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt,BluetoothGattDescriptor descriptor, int status) {
            String deviceIdentifier = gatt.getDevice().getAddress().toUpperCase(Locale.getDefault());
            String serviceIdentifier = descriptor.getCharacteristic().getService().getUuid().toString().toUpperCase(Locale.getDefault());
            String attributeIdentifier = descriptor.getCharacteristic().getUuid().toString().toUpperCase(Locale.getDefault());
            String descriptorIdentifier = descriptor.getUuid().toString().toUpperCase(Locale.getDefault());
            byte[] descriptorValue = descriptor.getValue();

            for(DeviceEventListener listener : m_listeners) {
                listener.onDeviceAttributeDescriptorRead(deviceIdentifier, serviceIdentifier, attributeIdentifier, descriptorIdentifier, descriptorValue, status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,BluetoothGattDescriptor descriptor, int status) {
            String deviceIdentifier = gatt.getDevice().getAddress().toUpperCase(Locale.getDefault());
            String serviceIdentifier = descriptor.getCharacteristic().getService().getUuid().toString().toUpperCase(Locale.getDefault());
            String attributeIdentifier = descriptor.getCharacteristic().getUuid().toString().toUpperCase(Locale.getDefault());
            String descriptorIdentifier = descriptor.getUuid().toString().toUpperCase(Locale.getDefault());

            for(DeviceEventListener listener : m_listeners) {
                listener.onDeviceAttributeDescriptoWrite(deviceIdentifier, serviceIdentifier, attributeIdentifier, descriptorIdentifier, status);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            for(DeviceEventListener listener : m_listeners) {
                listener.onDeviceSignal(gatt.getDevice().getAddress().toUpperCase(Locale.getDefault()), gatt.getDevice().getName(), rssi, status);
            }
        }
    };

    BluetoothLEScanner.LEScanListener m_le_scan_listener = new BluetoothLEScanner.LEScanListener() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, ScanRecord record, byte[] advertisementData) {
            if (device == null) {
                return;
            }

            if (m_available_devices.contains(device)) {
                for (int i = 0; i < m_available_devices.size(); i++) {
                    if (m_available_devices.get(i).equals(device))
                        m_available_devices.set(i, device);
                }
            } else {
                m_available_devices.add(device);
            }

            List<String> serviceUUIDs = m_le_scanner.parseAdvertisementData(advertisementData);

            for (DeviceEventListener listener : m_listeners) {
                listener.onDeviceFound(device.getAddress().toUpperCase(Locale.getDefault()), device.getName(), rssi, serviceUUIDs, record, advertisementData);
            }
        }

    };

    public void getBluetoothAdapterState() {

        m_bluetooth_adapter = BluetoothAdapter.getDefaultAdapter();

        if (m_bluetooth_adapter == null) {

            setServiceState(ServiceState.SERVICE_STATE_NONE);
        }
        else if(isBLESupport()) {

            if(m_bluetooth_adapter.getState() == BluetoothAdapter.STATE_OFF) {

                setServiceState(ServiceState.SERVICE_STATE_INACTIVE);
            }
            else if((m_bluetooth_adapter.getState() == BluetoothAdapter.STATE_ON)) {

                setServiceState(ServiceState.SERVICE_STATE_ACTIVE);
            }
        }
        else {

            setServiceState(ServiceState.SERVICE_STATE_UNSUPPORTED);
        }
    }

    private boolean isBLESupport() {

        PackageManager m_manager = getApplicationContext().getPackageManager();

        if(!m_manager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)){

            return false;
        }
        else
        {
            return true;
        }
    }

    private BroadcastReceiver m_receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        m_current_state = ServiceState.SERVICE_STATE_INACTIVE;
                        break;
                    case BluetoothAdapter.STATE_ON:
                    case BluetoothAdapter.STATE_TURNING_ON:
                        m_current_state = ServiceState.SERVICE_STATE_ACTIVE;
                        break;
                }

            } else if(action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {

            }
        }
    };
}