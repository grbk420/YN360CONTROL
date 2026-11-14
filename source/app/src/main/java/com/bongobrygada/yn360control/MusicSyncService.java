package com.bongobrygada.yn360control;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;

/** Foreground microphone service, explicitly started from a visible Activity.
 * Samples are analysed in memory and immediately discarded; no audio is saved.
 */
public final class MusicSyncService extends Service {
    public static final String START="com.bongobrygada.yn360control.MUSIC_START";
    public static final String STOP="com.bongobrygada.yn360control.MUSIC_STOP";
    private static final String CHANNEL="yn360_live_music";
    private static final int NOTIFICATION_ID=3610;
    private volatile boolean capturing=false;
    private Thread worker;
    private volatile AudioRecord recorder;

    @Override public void onCreate(){
        super.onCreate();
        NotificationChannel channel=new NotificationChannel(CHANNEL,
            "Muzyka LIVE • mikrofon",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Analiza rytmu lokalnie przez mikrofon, bez zapisywania dźwięku");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content=PendingIntent.getActivity(this,21,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,MusicSyncService.class);
        stop.setAction(STOP);
        PendingIntent end=PendingIntent.getService(this,22,stop,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_yn360)
            .setContentTitle("YN360 • MUSIC SYNC LIVE")
            .setContentText("Mikrofon analizuje muzykę • audio nie jest nagrywane")
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,"Zatrzymaj",end).build())
            .build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?STOP:intent.getAction();
        MusicReactiveController music=LightingSession.get(this).music;
        if(STOP.equals(action)||!music.isActive()){
            music.stop(3);
            stopNow();
            return START_NOT_STICKY;
        }
        if(!START.equals(action)){
            stopNow();return START_NOT_STICKY;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            music.stop(3);stopNow();return START_NOT_STICKY;
        }
        try {
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION_ID,notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION_ID,notification());
        }catch(RuntimeException ex){
            music.stop(3);stopNow();return START_NOT_STICKY;
        }
        if(!capturing){
            capturing=true;
            worker=new Thread(this::captureLoop,"YN360-Music-Microphone");
            worker.setDaemon(true);
            worker.start();
        }
        return START_NOT_STICKY; // No automatic restarting of microphone capture
    }

    private void captureLoop(){
        AudioRecord audio=null;
        try {
            final int sampleRate=16000;
            int min=AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException("AudioRecord unsupported");
            int buffer=Math.max(4096,min*2);
            audio=new AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer);
            if(audio.getState()!=AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Microphone not initialized");
            recorder=audio;
            audio.startRecording();
            short[] pcm=new short[1024]; // approximately 64 ms per frame
            MusicReactiveController music=LightingSession.get(this).music;
            while(capturing&&music.isActive()&&!Thread.currentThread().isInterrupted()){
                int n=audio.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(n>0)music.process(pcm,n);
                else if(n<0)break;
            }
        }catch(SecurityException|IllegalArgumentException|IllegalStateException ignored){
            // User can revoke microphone access at any time; fail closed.
        }finally{
            capturing=false;
            recorder=null;
            if(audio!=null){
                try{audio.stop();}catch(Exception ignored){}
                try{audio.release();}catch(Exception ignored){}
            }
            LightingSession.get(this).music.stop(3);
            stopSelf();
        }
    }

    private void stopNow(){
        capturing=false;
        Thread t=worker;
        if(t!=null)t.interrupt();
        AudioRecord r=recorder;
        if(r!=null){
            try{r.stop();}catch(Exception ignored){}
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy(){
        capturing=false;
        Thread t=worker;
        if(t!=null)t.interrupt();
        AudioRecord r=recorder;
        if(r!=null){
            try{r.stop();}catch(Exception ignored){}
        }
        LightingSession.get(this).music.stop(3);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
