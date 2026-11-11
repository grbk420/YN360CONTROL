package com.bongobrygada.yn360control;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.UUID;

/**
 * Foreground countdown timer. Owns BLE connections only when a timer expires,
 * so the UI retains its normal control of the lamps until then.
 * Android force-stop, Bluetooth disabled, battery loss and out-of-range devices
 * can still prevent commands from reaching a lamp.
 */
public class LampTimerService extends Service {
    public static final String ACTION_SET="com.bongobrygada.yn360control.TIMER_SET";
    public static final String ACTION_CANCEL="com.bongobrygada.yn360control.TIMER_CANCEL";
    public static final String ACTION_CHANGED="com.bongobrygada.yn360control.TIMER_CHANGED";
    private static final String CHANNEL="yn360_timer";
    private static final int NOTIFICATION_ID=3608;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final long[] end={0L,0L};
    private final Runnable tick=new Runnable(){@Override public void run(){runTimers();}};
    private android.content.SharedPreferences prefs;

    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("yn360",MODE_PRIVATE);
        end[0]=prefs.getLong("timer_end_1",0L);
        end[1]=prefs.getLong("timer_end_2",0L);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"YN360 wyłącznik czasowy",
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null){
            String action=intent.getAction();
            int mask=intent.getIntExtra("mask",0)&3;
            if(ACTION_SET.equals(action)){
                long delay=intent.getLongExtra("duration",0L);
                if(delay>=60_000L && delay<=24*60*60_000L){
                    long finish=System.currentTimeMillis()+delay;
                    for(int i=0;i<2;i++)if((mask&(1<<i))!=0)setDeadline(i,finish);
                }
            }else if(ACTION_CANCEL.equals(action)){
                for(int i=0;i<2;i++)if((mask&(1<<i))!=0)setDeadline(i,0L);
            }
        }
        if(!anyActive()){
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            broadcastChanged();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID,makeNotification());
        handler.removeCallbacks(tick);
        handler.post(tick);
        broadcastChanged();
        return START_STICKY;
    }
    private void setDeadline(int index,long value){
        end[index]=value;
        prefs.edit().putLong("timer_end_"+(index+1),value).apply();
    }
    private boolean anyActive(){return end[0]>0L || end[1]>0L;}

    private Notification makeNotification(){
        StringBuilder description=new StringBuilder();
        long now=System.currentTimeMillis();
        for(int i=0;i<2;i++)if(end[i]>0){
            if(description.length()>0)description.append(" • ");
            long minutes=Math.max(1,(end[i]-now+59999)/60000);
            description.append("Lampa ").append(i+1).append(": ").append(minutes).append(" min");
        }
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL)
            .setContentTitle("YN360 • aktywny wyłącznik czasowy")
            .setContentText(description.toString())
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pending).setOngoing(true).build();
    }

    private void runTimers(){
        long now=System.currentTimeMillis();
        int expired=0;
        for(int i=0;i<2;i++)if(end[i]>0 && end[i]<=now){
            expired|=(1<<i);
            setDeadline(i,0);
        }
        if(expired!=0){
            for(int i=0;i<2;i++)if((expired&(1<<i))!=0)sendOffToLamp(i);
            broadcastChanged();
        }
        if(anyActive()){
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,makeNotification());
            handler.postDelayed(tick,1000);
        }else{
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
    private void broadcastChanged(){
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }

    @SuppressWarnings("MissingPermission")
    private void sendOffToLamp(int index){
        String address=prefs.getString(index==0?"lamp1_mac":"lamp2_mac",null);
        if(address==null)return;
        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=bm.getAdapter();
        if(adapter==null || !adapter.isEnabled())return;
        if(Build.VERSION.SDK_INT>=31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            BluetoothDevice device=adapter.getRemoteDevice(address);
            // A second GATT link at deadline may not be accepted by all firmware.
            // The controller will also receive the event while the UI is open.
            final BluetoothGatt[] holder={null};
            final boolean[] done={false};
            BluetoothGattCallback callback=new BluetoothGattCallback(){
                @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){
                    if(done[0])return;
                    if(status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED){
                        g.discoverServices();
                    }else if(state==BluetoothProfile.STATE_DISCONNECTED)finish(g);
                }
                @Override public void onServicesDiscovered(BluetoothGatt g,int status){
                    if(done[0])return;
                    BluetoothGattService service=g.getService(BleLightController.SERVICE_UUID);
                    BluetoothGattCharacteristic characteristic=service==null?null:service.getCharacteristic(BleLightController.SEND_UUID);
                    if(status!=BluetoothGatt.GATT_SUCCESS || characteristic==null){finish(g);return;}
                    byte[] packet={(byte)0xAE,(byte)0xA3,0,0,0,0x56};
                    int properties=characteristic.getProperties();
                    int type=(properties&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0
                        ?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                    boolean ok;
                    if(Build.VERSION.SDK_INT>=33)
                        ok=g.writeCharacteristic(characteristic,packet,type)==BluetoothStatusCodes.SUCCESS;
                    else {
                        characteristic.setWriteType(type);
                        characteristic.setValue(packet);
                        ok=g.writeCharacteristic(characteristic);
                    }
                    if(!ok)finish(g);
                    else handler.postDelayed(()->finish(g),850);
                }
                void finish(BluetoothGatt g){
                    if(done[0])return;
                    done[0]=true;
                    try{g.disconnect();}catch(Exception ignored){}
                    try{g.close();}catch(Exception ignored){}
                }
            };
            holder[0]=device.connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE);
            handler.postDelayed(()->{
                if(!done[0] && holder[0]!=null){
                    done[0]=true;
                    try{holder[0].disconnect();holder[0].close();}catch(Exception ignored){}
                }
            },15000);
        }catch(Exception ignored){}
    }
    @Override public android.os.IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}
}
