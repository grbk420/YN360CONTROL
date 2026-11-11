package com.bongobrygada.yn360control;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Foreground Bluetooth scene owner for continuing effects when Activity is hidden. */
public final class EffectsForegroundService extends Service {
    public static final String ACTION_STOP="com.bongobrygada.yn360control.SCENES_STOP";
    public static final String ACTION_KEEP="com.bongobrygada.yn360control.SCENES_KEEP";
    private static final String CHANNEL="yn360_background_scenes";
    private static final int NOTIFICATION_ID=3609;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean active=false;
    private final Runnable monitor=new Runnable(){
        @Override public void run(){
            if(!active)return;
            LightingSession session=LightingSession.get(EffectsForegroundService.this);
            if(!session.first.isReady())session.effects.stop(1);
            if(!session.second.isReady())session.effects.stop(2);
            if(!session.hasRunningScenes()){
                active=false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            handler.postDelayed(this,1500);
        }
    };
    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Efekty świetlne w tle",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Sterowanie aktywną sceną przez Bluetooth");
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,1,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,EffectsForegroundService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopAction=PendingIntent.getService(this,2,stop,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_yn360)
            .setContentTitle("YN360 • scena działa w tle")
            .setContentText("Bluetooth jest aktywny • otwórz panel sterowania")
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,"Zatrzymaj",stopAction).build())
            .build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ACTION_STOP:intent.getAction();
        LightingSession session=LightingSession.get(this);
        if(ACTION_STOP.equals(action)){
            session.effects.stop(3);
            active=false;
            handler.removeCallbacks(monitor);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(!ACTION_KEEP.equals(action)||!session.hasRunningScenes()){
            stopSelf();
            return START_NOT_STICKY;
        }
        try{
            if(Build.VERSION.SDK_INT>=29){
                startForeground(NOTIFICATION_ID,notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            }else startForeground(NOTIFICATION_ID,notification());
        }catch(RuntimeException ex){
            session.effects.stop(3);
            active=false;
            stopSelf();
            return START_NOT_STICKY;
        }
        active=true;
        handler.removeCallbacks(monitor);
        handler.postDelayed(monitor,1500);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){
        active=false;
        handler.removeCallbacks(monitor);
        LightingSession.get(this).effects.stop(3);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
