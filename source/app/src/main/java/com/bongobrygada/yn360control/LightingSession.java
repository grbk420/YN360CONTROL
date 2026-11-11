package com.bongobrygada.yn360control;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** App-process owner for both BLE connections and background scene scheduler. */
public final class LightingSession {
    public interface Observer { void onStatus(int lamp,String status); }
    private static LightingSession instance;
    public final BleLightController first;
    public final BleLightController second;
    public final LampEffects effects;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Observer observer;

    private LightingSession(Context context){
        Context app=context.getApplicationContext();
        first=new BleLightController(app,state->notifyObserver(1,state));
        second=new BleLightController(app,state->notifyObserver(2,state));
        effects=new LampEffects(first,second);
    }
    public static synchronized LightingSession get(Context context){
        if(instance==null)instance=new LightingSession(context);
        return instance;
    }
    public void attach(Observer next){observer=next;}
    public void detach(Observer old){if(observer==old)observer=null;}
    private void notifyObserver(int lamp,String status){
        main.post(()->{
            Observer current=observer;
            if(current!=null)current.onStatus(lamp,status);
        });
    }
    public boolean hasRunningScenes(){return effects.hasActive(3);}
}
