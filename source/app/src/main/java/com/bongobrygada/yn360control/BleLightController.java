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
    private volatile boolean ready = false;
    // App-session safety gate: only explicit power ON enables colour/mode commands.
    private volatile boolean userEnabled=false;
    private volatile boolean turboStrobe=false;
    private volatile int acceptedWrites=0;
    private volatile int ackWrites=0;
    private volatile int callbackWrites=0;
    private volatile int noResponseWrites=0;
    private volatile int responseWrites=0;
    private volatile int characteristicProps=0;
    private volatile int lastWriteType=0; // 1=no response, 2=response
    private volatile int lastRequestCode=-1;
    private volatile String lastPacket="—";
    private volatile boolean preferResponse=false;
    private volatile int whiteChannel=1;
    private volatile int failedWrites=0;
    private volatile int lastGattStatus=-1;
    private volatile String lastCommand="—";
    private volatile String lastConnection="Rozłączona";
    private final Listener listener;
    private final Handler io = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing = false;
    private int inFlightId = 0;
    private boolean inFlightNoResponse = false;
    private int retryFailures = 0;
    private final ArrayDeque<byte[]> priorityQueue = new ArrayDeque<>();
    private long generation = 0;
    private String deviceAddress;
    private byte[] lastLitPacket = new byte[]{(byte)0xAE,(byte)0xAA,0x01,50,45,0x56};

    public interface Listener { void onState(String state); }
    public BleLightController(Context c, Listener l) { context = c; listener = l; }
    public boolean isReady(){ return ready; }
    public boolean isUserEnabled(){return userEnabled;}
    public void setUserEnabled(boolean enabled){userEnabled=enabled;}
    /** Faster pacing is only used with no-response writes while turbo is active. */
    public synchronized void setTurboStrobe(boolean enabled){turboStrobe=enabled; if(enabled)queue.clear();}
    public void setWhiteChannel(int channel){ if(channel>=1 && channel<=8)whiteChannel=channel; }
    public int getWhiteChannel(){return whiteChannel;}
    public synchronized void setPreferResponse(boolean prefer){preferResponse=prefer;}
    public boolean canUseResponse(){return (characteristicProps&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0;}
    public boolean canUseNoResponse(){return (characteristicProps&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0;}
    private String availableWriteTypes(){
        if(!ready)return "brak";
        String response=canUseResponse()?"WRITE":"";
        String nr=canUseNoResponse()?"WRITE_NO_RESPONSE":"";
        return response+(response.length()>0&&nr.length()>0?" + ":"")+nr;
    }
    public String debugSnapshot(){
        String address=deviceAddress==null?"nieprzypisana":deviceAddress.substring(Math.max(0,deviceAddress.length()-8));
        String writeMode=lastWriteType==1?"BEZ POTWIERDZENIA":lastWriteType==2?"Z POTWIERDZENIEM":"brak zapisu";
        return (ready?"GOTOWA":"NIEPOŁĄCZONA")+" • "+address+" • CCT CH"+whiteChannel+"\n"
            +"Cechy GATT: 0x"+Integer.toHexString(characteristicProps).toUpperCase(java.util.Locale.US)
            +" ("+availableWriteTypes()+")\n"
            +"Tryb ostatniego zapisu: "+writeMode+" • wymuszaj ACK: "+(preferResponse?"TAK":"NIE")+"\n"
            +"Przyjęte żądania: "+acceptedWrites+"  zapis z ACK: "+responseWrites+"  bez ACK: "+noResponseWrites+"\n"
            +"Callbacki: "+callbackWrites+"  ACK w trybie WRITE: "+ackWrites+"  błędy: "+failedWrites+"\n"
            +"Ostatnia ramka: "+lastPacket+" • zwrot Android: "+lastRequestCode+" • GATT: "+lastGattStatus+"\n"
            +lastConnection;
    }

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
        lastConnection="Łączenie";
        listener.onState("Łączenie: " + safeName(device));
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressWarnings("MissingPermission")
    public synchronized void disconnect(){
        generation++;
        ready = false;userEnabled=false;turboStrobe=false; send = null; characteristicProps=0; queue.clear(); priorityQueue.clear(); writing=false; inFlightId++;
        lastConnection="Rozłączona";
        if(gatt != null){ try{gatt.disconnect();}catch(Exception ignored){} try{gatt.close();}catch(Exception ignored){} gatt = null; }
    }

    public synchronized void cancelPending(){
        generation++;
        queue.clear();
        priorityQueue.clear();
    }

    private String safeName(BluetoothDevice d){
        try { String n=d.getName(); return (n==null?d.getAddress():n+" • "+d.getAddress()); } catch(Exception e){ return d.getAddress(); }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if(g!=gatt)return;
            lastGattStatus=status;
            if(status==BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                lastConnection="Połączona, szukam kanału sterowania";
                listener.onState("Połączono, wykrywam usługę…");
                g.discoverServices();
            } else if(newState==BluetoothProfile.STATE_DISCONNECTED || status!=BluetoothGatt.GATT_SUCCESS){
                synchronized(BleLightController.this){
                    if(g!=gatt)return;
                    ready=false;userEnabled=false;turboStrobe=false;send=null;characteristicProps=0;queue.clear();priorityQueue.clear();writing=false;inFlightId++;generation++;
                }
                lastConnection="Rozłączona (GATT "+status+")";
                listener.onState(lastConnection);
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status){
            if(g!=gatt || status!=BluetoothGatt.GATT_SUCCESS){listener.onState("Błąd wykrywania usług");return;}
            BluetoothGattService s=g.getService(SERVICE_UUID);
            if(s==null){ listener.onState("Nie znaleziono usługi YN360"); return; }
            send=s.getCharacteristic(SEND_UUID);
            if(send==null){ listener.onState("Nie znaleziono kanału sterowania"); return; }
            characteristicProps=send.getProperties();
            ready=true;
            lastConnection="Gotowa, kanał GATT znaleziony";
            listener.onState("Gotowa ✓");
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status){
            final int ticket;
            final boolean waitResponse;
            synchronized(BleLightController.this){
                if(g!=gatt)return;
                callbackWrites++;
                lastGattStatus=status;
                waitResponse=writing && !inFlightNoResponse;
                ticket=inFlightId;
                if(status==BluetoothGatt.GATT_SUCCESS){
                    if(waitResponse)ackWrites++;
                }else{
                    failedWrites++;
                    lastConnection="Błąd zapisu GATT "+status;
                    listener.onState(lastConnection);
                }
            }
            if(waitResponse)io.postDelayed(()->finishWrite(ticket),60);
        }
    };

    public synchronized void write(byte[] data){
        if(!ready || gatt==null || send==null) return;
        // A fast strobe must not build a long FIFO of obsolete ON/OFF frames.
        if(turboStrobe && canUseNoResponse() && !preferResponse)queue.clear();
        else while(queue.size()>4)queue.pollFirst();
        queue.offerLast(data.clone());
        drain();
    }

    /** Direct mode change: clear obsolete color frames and prioritize newest CCT/RGB. */
    public synchronized void writeMode(byte[] data){
        if(!ready || gatt==null || send==null)return;
        queue.clear();
        priorityQueue.clear();
        priorityQueue.offerLast(data.clone());
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
        if(writing || !ready || gatt==null || send==null || (queue.isEmpty() && priorityQueue.isEmpty())) return;
        byte[] data=priorityQueue.isEmpty()?queue.pollFirst():priorityQueue.pollFirst();
        lastCommand=String.format(java.util.Locale.US,"%02X",data[1]&255);
        int props=send.getProperties();
        boolean supportsResponse=(props&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0;
        boolean supportsNoResponse=(props&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0;
        if(!supportsResponse && !supportsNoResponse){
            failedWrites++;
            lastConnection="Brak możliwości zapisu GATT";
            listener.onState(lastConnection);
            return;
        }
        // Prefer response only when explicitly enabled AND supported.
        boolean noResponse=supportsNoResponse && !(preferResponse && supportsResponse);
        int type=noResponse?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        lastWriteType=type;
        lastPacket=String.format(java.util.Locale.US,"%02X %02X %02X %02X %02X %02X",
            data[0]&255,data[1]&255,data[2]&255,data[3]&255,data[4]&255,data[5]&255);
        int ticket=++inFlightId;
        writing=true;
        inFlightNoResponse=noResponse;
        boolean accepted=false;
        lastRequestCode=-1;
        try{
            if(Build.VERSION.SDK_INT>=33) {
                lastRequestCode=gatt.writeCharacteristic(send,data,type);
                accepted=lastRequestCode==BluetoothStatusCodes.SUCCESS;
            }else{
                send.setWriteType(type);
                send.setValue(data);
                accepted=gatt.writeCharacteristic(send);
                lastRequestCode=accepted?0:-1;
            }
        }catch(Exception ignored){lastRequestCode=-2;}
        if(!accepted){
            failedWrites++;
            lastConnection="Android odmówił zapisu";
            writing=false;
            if(retryFailures++<5)priorityQueue.offerFirst(data);
            io.postDelayed(this::drain,180);
            return;
        }
        acceptedWrites++;
        if(noResponse)noResponseWrites++;else responseWrites++;
        if(noResponse){
            // No-response writes have no guaranteed completion callback.
            io.postDelayed(()->finishWrite(ticket),turboStrobe?78:145);
        }else{
            // Recover from a missing BLE callback rather than permanently freezing this lamp.
            io.postDelayed(()->finishWrite(ticket),1100);
        }
    }

    // YN360 protocol: 0xA3 is the actual OFF instruction (NOT 0xEE).
    // Bluetooth stays alive while the LEDs are switched off.
    public void off(){ userEnabled=false;writeMode(new byte[]{(byte)0xAE,(byte)0xA3,0,0,0,0x56}); }
    public void turnOn(){userEnabled=true;writeMode(lastLitPacket.clone()); }

    private void remember(byte[] packet){
        lastLitPacket=packet.clone();
        if(deviceAddress==null)return;
        StringBuilder hex=new StringBuilder(12);
        for(byte b:packet) hex.append(String.format(java.util.Locale.US,"%02X",b&255));
        context.getSharedPreferences("yn360",Context.MODE_PRIVATE).edit()
            .putString("last_lit_"+deviceAddress,hex.toString()).apply();
    }

    public void rgb(int r,int g,int b){
        if(!userEnabled)return;
        int rr=clamp255(r),gg=clamp255(g),bb=clamp255(b);
        byte[] packet=new byte[]{(byte)0xAE,(byte)0xA1,(byte)rr,(byte)gg,(byte)bb,0x56};
        if(rr+gg+bb>0 && !turboStrobe) remember(packet); // Do not replace remembered colour with strobe flashes.
        write(packet);
    }
    public void white(int cool,int warm){
        if(!userEnabled)return;
        int c=clamp99(cool),w=clamp99(warm);
        byte[] packet=new byte[]{(byte)0xAE,(byte)0xAA,(byte)whiteChannel,(byte)c,(byte)w,0x56};
        if(c+w>0)remember(packet);
        write(packet);
    }
    public void setModeRgb(int r,int g,int b){
        if(!userEnabled)return;
        int rr=clamp255(r),gg=clamp255(g),bb=clamp255(b);
        byte[] packet=new byte[]{(byte)0xAE,(byte)0xA1,(byte)rr,(byte)gg,(byte)bb,0x56};
        if(rr+gg+bb>0)remember(packet);
        writeMode(packet);
    }
    public void setModeWhite(int cool,int warm){
        if(!userEnabled)return;
        int c=clamp99(cool),w=clamp99(warm);
        byte[] packet=new byte[]{(byte)0xAE,(byte)0xAA,(byte)whiteChannel,(byte)c,(byte)w,0x56};
        if(c+w>0)remember(packet);
        writeMode(packet);
    }
    private int clamp255(int v){ return Math.max(0,Math.min(255,v)); }
    private int clamp99(int v){ return Math.max(0,Math.min(99,v)); }
}
