package com.ac7916.stethoscope;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Minimal GATT client for the existing AC7916AB firmware. No cloud calls. */
public class BleClient {
    private static UUID uuid16(String shortUuid) {
        return UUID.fromString("0000"+shortUuid+"-0000-1000-8000-00805f9b34fb");
    }
    private static final UUID AE30=uuid16("ae30"), AE01=uuid16("ae01"), AE02=uuid16("ae02");
    private static final UUID AE3A=uuid16("ae3a"), AE3C=uuid16("ae3c");
    private static final UUID CCC=uuid16("2902");

    public interface Listener {
        void status(String text);
        void found(BluetoothDevice device, String name);
        void mtu(int value);
        void connected(boolean connected);
        void notificationReady(boolean ready);
        void micData(byte[] data);
        void echoData(byte[] data);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txEcho, rxEcho, rxMic;
    private boolean scanActive=false;
    private final Set<String> seen=new HashSet<>();
    private int mtu=23;

    public BleClient(Context ctx, Listener listener) {
        this.context=ctx.getApplicationContext();
        this.listener=listener;
        BluetoothManager mgr=(BluetoothManager)ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter=mgr==null?null:mgr.getAdapter();
    }
    public boolean bluetoothAvailable() {return adapter!=null && adapter.isEnabled();}
    private void uiStatus(String s) {main.post(()->listener.status(s));}

    public void startScan() {
        stopScan();
        if (!bluetoothAvailable()) {uiStatus("请开启手机蓝牙"); return;}
        try {
            seen.clear();
            scanner=adapter.getBluetoothLeScanner();
            if (scanner==null){uiStatus("无法启动蓝牙扫描");return;}
            scanCallback=new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {showResult(result);}
                @Override public void onScanFailed(int errorCode){uiStatus("扫描失败，错误码 "+errorCode);scanActive=false;}
            };
            ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null,settings,scanCallback);
            scanActive=true;
            uiStatus("扫描中：寻找 JL-AC79XX ...");
            main.postDelayed(()->{if (scanActive){stopScan(); uiStatus("扫描结束。若未出现设备，检查供电与广播状态");}},12000);
        } catch (SecurityException e) {uiStatus("缺少附近设备/定位权限");}
    }
    private void showResult(ScanResult r) {
        if (r==null||r.getDevice()==null)return;
        String n=(r.getScanRecord()!=null)?r.getScanRecord().getDeviceName():null;
        if (n==null || n.trim().isEmpty()) {
            try {n=r.getDevice().getName();}catch(SecurityException ignored) {}
        }
        if (n==null) return;
        String upper=n.toUpperCase(java.util.Locale.ROOT);
        if (!upper.contains("AC79") && !upper.startsWith("JL-") && !upper.contains("WL82")) return;
        String addr=r.getDevice().getAddress();
        if (seen.add(addr)) {
            final String display=n;
            main.post(()->listener.found(r.getDevice(),display));
        }
    }
    public void stopScan() {
        scanActive=false;
        if (scanner!=null && scanCallback!=null) {
            try {scanner.stopScan(scanCallback);}catch(Exception ignored) {}
        }
        scanCallback=null;
    }
    public void connect(BluetoothDevice device) {
        if (device==null)return;
        stopScan();disconnect();
        uiStatus("连接中："+device.getAddress());
        try {
            gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            if (gatt==null) uiStatus("无法创建 GATT 连接");
        } catch(SecurityException e){uiStatus("无蓝牙连接权限");}
    }
    public void disconnect() {
        BluetoothGatt old=gatt;
        gatt=null;rxMic=null;txEcho=null;rxEcho=null;mtu=23;
        if(old!=null)try{old.disconnect();old.close();}catch(Exception ignored){}
        main.post(()->{listener.connected(false);listener.notificationReady(false);});
    }
    public void close(){stopScan();disconnect();}

    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int newState) {
            if (g!=gatt){g.close();return;}
            if(newState==BluetoothProfile.STATE_CONNECTED && status==BluetoothGatt.GATT_SUCCESS){
                main.post(()->listener.connected(true));
                uiStatus("BLE 已连接，协商 MTU...");
                try{if(!g.requestMtu(200))g.discoverServices();}catch(SecurityException e){uiStatus("缺少蓝牙连接权限");}
            } else if (newState==BluetoothProfile.STATE_DISCONNECTED || status!=BluetoothGatt.GATT_SUCCESS){
                uiStatus("连接已断开 (GATT "+status+")");
                if(g==gatt){gatt=null;rxMic=null;rxEcho=null;txEcho=null;}
                try{g.close();}catch(Exception ignored){}
                main.post(()->{listener.connected(false);listener.notificationReady(false);});
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g,int newMtu,int status){
            if(g!=gatt)return;
            mtu=(status==BluetoothGatt.GATT_SUCCESS)?newMtu:23;
            main.post(()->listener.mtu(mtu));
            uiStatus("MTU="+mtu+"；发现服务...");
            try{g.discoverServices();}catch(SecurityException e){uiStatus("无法发现服务");}
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){
            if(g!=gatt)return;
            if(status!=BluetoothGatt.GATT_SUCCESS){uiStatus("服务发现失败："+status);return;}
            BluetoothGattService audio=g.getService(AE3A), test=g.getService(AE30);
            rxMic=audio==null?null:audio.getCharacteristic(AE3C);
            txEcho=test==null?null:test.getCharacteristic(AE01);
            rxEcho=test==null?null:test.getCharacteristic(AE02);
            if(rxMic==null){uiStatus("找不到 AE3A/AE3C，请确认已烧录双麦测试固件");return;}
            if(mtu<87)uiStatus("MTU="+mtu+"，小于84字节包所需的87，可能无法接收");
            else uiStatus("已发现 AE3C，正在订阅 Notify...");
            subscribe(g,rxMic);
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){
            if(g!=gatt)return;
            BluetoothGattCharacteristic c=d.getCharacteristic();
            if(status!=BluetoothGatt.GATT_SUCCESS){uiStatus("Notify订阅失败："+status);return;}
            if(c.getUuid().equals(AE3C)){
                main.post(()->listener.notificationReady(true));
                uiStatus("AE3C 已订阅；等待 84 字节 MC 数据包");
                if(rxEcho!=null)subscribe(g,rxEcho);
            } else if(c.getUuid().equals(AE02)) uiStatus("AE3C + AE02 通知均已准备好");
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
            receive(g,c,value);
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){
            receive(g,c,c.getValue());
        }
    };
    private void receive(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
        if(g!=gatt||value==null)return;
        byte[] copy=value.clone();
        if(c.getUuid().equals(AE3C)) main.post(()->listener.micData(copy));
        else if(c.getUuid().equals(AE02)) main.post(()->listener.echoData(copy));
    }
    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt g,BluetoothGattCharacteristic c){
        try {
            if(!g.setCharacteristicNotification(c,true)){uiStatus("本地通知启用失败");return;}
            BluetoothGattDescriptor d=c.getDescriptor(CCC);
            if(d==null){uiStatus("没有找到 CCC 0x2902");return;}
            byte[] value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            boolean ok;
            if(Build.VERSION.SDK_INT>=33)ok=(g.writeDescriptor(d,value)==BluetoothGatt.GATT_SUCCESS);
            else{d.setValue(value);ok=g.writeDescriptor(d);}
            if(!ok)uiStatus("写入 CCC 未启动");
        } catch(Exception e){uiStatus("订阅异常："+e.getClass().getSimpleName());}
    }
    @SuppressWarnings("deprecation")
    public void sendEchoTest(){
        BluetoothGatt g=gatt;BluetoothGattCharacteristic tx=txEcho;
        if(g==null||tx==null){uiStatus("请先连接设备并发现 AE01");return;}
        byte[] data=new byte[]{0x41,0x42,0x43,0x44};
        try{
            boolean ok;
            if(Build.VERSION.SDK_INT>=33){
                ok=(g.writeCharacteristic(tx,data,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)==BluetoothGatt.GATT_SUCCESS);
            } else {
                tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                tx.setValue(data);ok=g.writeCharacteristic(tx);
            }
            uiStatus(ok?"已发送 ABCD → AE01；等待 AE02 回传":"ABCD 发送失败");
        }catch(Exception e){uiStatus("BLE 写入异常："+e.getClass().getSimpleName());}
    }
}
