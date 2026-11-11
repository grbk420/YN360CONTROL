package com.bongobrygada.yn360control;

import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.UUID;

public class BleLightController {
    public static final UUID SERVICE_UUID = UUID.fromString("f000aa60-0451-4000-b000-000000000000");
    public static final UUID SEND_UUID = UUID.fromString("f000aa61-0451-4000-b000-000000000000");
    private final Context context;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic send;
    private boolean ready = false;
    private final Listener listener;
    private final Handler io = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing = false;
    private int inFlightId = 0;
    private boolean inFlightNoResponse = false;
    private int retryFailures = 0;
    private long generation = 0;
    private String deviceAddress;
    private byte[] lastLitPacket = new byte[]{(byte)0xAE,(byte)0xAA,0x01,50,45,0x56};

    public interface Listener { void onState(String state); }
    public BleLightController(Context c, Listener l) { context = c; listener = l; }
    public boolean isReady(){ return ready; }

    @SuppressWarnings("MissingPermission")
    public void connect(BluetoothDevice device) {
        disconnect();
        deviceAddress=device.getAddress();
        String stored=context.getSharedPreferences("yn360",Context.MODE_PRIVATE)
            .getString("last_lit_"+deviceAddress,null);
        if(stored!=null && stored.length()==12){
            try{
                byte[] previous=new byte[6];
                for(int i=0;i<6;i++) previous[i]=(byte)Integer.parseInt(stored.substring(i*2,i*2+2),16);
                if((previous[0]&0xFF)==0xAE && (previous[5]&0xFF)==0x56 &&
                   ((previous[1]&0xFF)==0xA1||(previous[1]&0xFF)==0xAA)) lastLitPacket=previous;
            }catch(Exception ignored){}
        }
        listener.onState("Łączenie: " + safeName(device));
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressWarnings("MissingPermission")
    public synchronized void disconnect(){
        generation++;
        ready = false; send = null; queue.clear(); writing=false; inFlightId++;
        if(gatt != null){ try{gatt.disconnect();}catch(Exception ignored){} try{gatt.close();}catch(Exception ignored){} gatt = null; }
    }

    public synchronized void cancelPending(){
        generation++;
        queue.clear();
    }

    private String safeName(BluetoothDevice d){
        try { String n=d.getName(); return (n==null?d.getAddress():n+" • "+d.getAddress()); } catch(Exception e){ return d.getAddress(); }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if(status==BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED){ listener.onState("Połączono, wykrywam usługę…"); g.discoverServices(); }
            else if(newState == BluetoothProfile.STATE_DISCONNECTED){ synchronized(BleLightController.this){ if(g!=gatt) return; ready=false; send=null; queue.clear(); writing=false; inFlightId++; generation++; } listener.onState("Rozłączono"); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status){
            if(g!=gatt || status!=BluetoothGatt.GATT_SUCCESS){listener.onState("Błąd wykrywania usług");return;}
            BluetoothGattService s=g.getService(SERVICE_UUID);
            if(s==null){ listener.onState("Nie znaleziono usługi YN360"); return; }
            send=s.getCharacteristic(SEND_UUID);
            if(send==null){ listener.onState("Nie znaleziono kanału sterowania"); return; }
            ready=true; listener.onState("Gotowa ✓");
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status){
            final int ticket;
            synchronized(BleLightController.this){
                if(g!=gatt || !writing || inFlightNoResponse)return;
                ticket=inFlightId;
            }
            io.postDelayed(()->finishWrite(ticket),60);
        }
    };

    public synchronized void write(byte[] data){
        if(!ready || gatt==null || send==null) return;
        while(queue.size()>4) queue.pollFirst();
        queue.offerLast(data.clone());
        drain();
    }

    private synchronized void finishWrite(int ticket){
        if(!writing || ticket!=inFlightId) return;
        writing=false;
        retryFailures=0;
        drain();
    }

    @SuppressWarnings("MissingPermission")
    private synchronized void drain(){
        if(writing || !ready || gatt==null || send==null || queue.isEmpty()) return;
        byte[] data=queue.pollFirst();
        int props=send.getProperties();
        boolean noResponse=(props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0;
        int type=noResponse?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        int ticket=++inFlightId;
        writing=true;
        inFlightNoResponse=noResponse;
        boolean accepted=false;
        try{
            if(Build.VERSION.SDK_INT>=33) {
                accepted=gatt.writeCharacteristic(send,data,type)==BluetoothStatusCodes.SUCCESS;
            }else{
                send.setWriteType(type);
                send.setValue(data);
                accepted=gatt.writeCharacteristic(send);
            }
        }catch(Exception ignored){}
        if(!accepted){
            writing=false;
            if(retryFailures++<5)queue.offerFirst(data);
            io.postDelayed(this::drain,180);
            return;
        }
        if(noResponse){
            // No-response writes have no guaranteed completion callback.
            io.postDelayed(()->finishWrite(ticket),145);
        }else{
            // Recover from a missing BLE callback rather than permanently freezing this lamp.
            io.postDelayed(()->finishWrite(ticket),1100);
        }
    }

    // YN360 protocol: 0xA3 is the actual OFF instruction (NOT 0xEE).
    // Bluetooth stays alive while the LEDs are switched off.
    public void off(){ write(new byte[]{(byte)0xAE,(byte)0xA3,0,0,0,0x56}); }
    public void turnOn(){ write(lastLitPacket.clone()); }

    private void remember(byte[] packet){
        lastLitPacket=packet.clone();
        if(deviceAddress==null)return;
        StringBuilder hex=new StringBuilder(12);
        for(byte b:packet) hex.append(String.format(java.util.Locale.US,"%02X",b&255));
        context.getSharedPreferences("yn360",Context.MODE_PRIVATE).edit()
            .putString("last_lit_"+deviceAddress,hex.toString()).apply();
    }

    public void rgb(int r,int g,int b){
        int rr=clamp255(r),gg=clamp255(g),bb=clamp255(b);
        byte[] packet=new byte[]{(byte)0xAE,(byte)0xA1,(byte)rr,(byte)gg,(byte)bb,0x56};
        if(rr+gg+bb>0) remember(packet); // Never replace remembered color with black strobe frames.
        write(packet);
    }
    public void white(int cool,int warm){
        int c=clamp99(cool),w=clamp99(warm);
        byte[] packet=new byte[]{(byte)0xAE,(byte)0xAA,0x01,(byte)c,(byte)w,0x56};
        if(c+w>0)remember(packet);
        write(packet);
    }
    private int clamp255(int v){ return Math.max(0,Math.min(255,v)); }
    private int clamp99(int v){ return Math.max(0,Math.min(99,v)); }
}
