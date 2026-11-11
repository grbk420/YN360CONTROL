package com.bongobrygada.yn360control;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;
import android.view.View;
import java.util.*;

public class MainActivity extends Activity {
    private BluetoothAdapter adapter;
    private BleLightController light1, light2;
    private TextView status;
    private SeekBar brightness, brightnessCct, whiteTemp, red, green, blue, fxSpeed;
    private ColorPaletteView colorPalette;
    private android.view.View colorPreview;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable palettePending;
    private long lastLiveSend=0L;
    private boolean slidersFromPalette=false;
    private int paletteR=255, paletteG=0, paletteB=0;
    private LampEffects lampEffects;
    private TextView lamp1State,lamp2State,colorValue,timerStatus1,timerStatus2,selectedTarget;
    private View timerBody, diagnosticsBody;
    private Spinner timerDuration;
    private final Handler timerUi=new Handler(Looper.getMainLooper());
    private final int[] timerMinutes={5,15,30,60,120,240};
    private final Runnable timerTick=new Runnable(){@Override public void run(){refreshTimers();timerUi.postDelayed(this,1000);}};
    private boolean timerReceiverRegistered=false;
    private final BroadcastReceiver timerReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            int expired=intent.getIntExtra("expired",0);
            if(expired!=0){lampEffects.stop(expired); forMask(expired,BleLightController::off);}
            refreshTimers();
        }
    };
    private Button target1, target2, targetBoth;
    private int selectedTargetMask=1;
    private int manualMode = 0; // 0 = white, 1 = RGB
    private static final int DRAFT_NONE=-1,DRAFT_CCT=0,DRAFT_RGB=1;
    private final int[] draftMode={DRAFT_NONE,DRAFT_NONE};
    private final int[][] draftRgb={{255,0,0},{255,0,0}};
    private final int[][] draftCct={{0,0,128,210},{0,0,128,210}};
    private int activeTab=0;
    private CheckBox cctRgbFallback2;
    private TextView diagResults2;
    private CheckBox diagUseAck2;
    private Spinner cctChannel2;
    private String diagLast2="Nie uruchomiono testu";
    private SharedPreferences prefs;
    private static final String PREF_L1 = "lamp1_mac";
    private static final String PREF_L2 = "lamp2_mac";
    private static final String PREF_L1_NAME = "lamp1_name";
    private static final String PREF_L2_NAME = "lamp2_name";

    private static class Candidate {
        BluetoothDevice device;
        int rssi;
        String name;
        boolean advertisesYnService;
        Candidate(BluetoothDevice d, int r, String n, boolean adv){ device=d; rssi=r; name=n; advertisesYnService=adv; }
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main);
        prefs=getSharedPreferences("yn360", MODE_PRIVATE);
        status=findViewById(R.id.status);
        lamp1State=findViewById(R.id.lamp1State); lamp2State=findViewById(R.id.lamp2State); colorValue=findViewById(R.id.colorValue);
        timerStatus1=findViewById(R.id.timerStatus1); timerStatus2=findViewById(R.id.timerStatus2); timerDuration=findViewById(R.id.timerDuration);
        timerBody=findViewById(R.id.timerBody); diagnosticsBody=findViewById(R.id.diagnosticsBody); selectedTarget=findViewById(R.id.selectedTarget);
        brightness=findViewById(R.id.brightness); brightnessCct=findViewById(R.id.brightnessCct); whiteTemp=findViewById(R.id.whiteTemp);
        red=findViewById(R.id.red); green=findViewById(R.id.green); blue=findViewById(R.id.blue);
        fxSpeed=findViewById(R.id.fxSpeed);
        colorPalette=findViewById(R.id.colorPalette); colorPreview=findViewById(R.id.colorPreview);
        target1=findViewById(R.id.target1); target2=findViewById(R.id.target2); targetBoth=findViewById(R.id.targetBoth);
        cctRgbFallback2=findViewById(R.id.cctRgbFallback2);
        diagResults2=findViewById(R.id.diagResults2);
        diagUseAck2=findViewById(R.id.diagUseAck2);
        cctChannel2=findViewById(R.id.cctChannel2);

        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); adapter=bm.getAdapter();
        light1=new BleLightController(this, s->runOnUiThread(()->{status.setText("L1: "+s); updateButtons();}));
        light2=new BleLightController(this, s->runOnUiThread(()->{
            status.setText("L2: "+s);
            updateButtons();
            if(diagResults2!=null) showDiag2();
        }));
        light2.setWhiteChannel(prefs.getInt("cct_channel_2",1));
        lampEffects=new LampEffects(light1,light2);

        findViewById(R.id.updatesBtn).setOnClickListener(v->openUpdates());
        findViewById(R.id.connect1).setOnClickListener(v->chooseVerifiedLamp(1));
        findViewById(R.id.connect2).setOnClickListener(v->chooseVerifiedLamp(2));
        findViewById(R.id.identify1).setOnClickListener(v->identify(light1, "Lampa 1"));
        findViewById(R.id.identify2).setOnClickListener(v->identify(light2, "Lampa 2"));
        findViewById(R.id.onBtn).setOnClickListener(v->{
            int mask=selectedMask();
            stopFx(false);
            int connected=readyMask(mask);
            for(int i=0;i<2;i++){
                int bit=1<<i;
                if((connected&bit)!=0)applyPowerOn(i);
            }
            showPowerMessage("WŁĄCZ",mask,connected);
            refreshTargetLabel();
        });
        findViewById(R.id.offBtn).setOnClickListener(v->{
            int mask=selectedMask();
            stopFx(false);
            cancelTimer(mask);
            int connected=readyMask(mask);
            forMask(mask,BleLightController::off);
            showPowerMessage("WYŁĄCZ",mask,connected);
            refreshTargetLabel();
        });
        findViewById(R.id.whiteBtn).setOnClickListener(v->applySelectedMode(1));
        findViewById(R.id.rgbBtn).setOnClickListener(v->applySelectedMode(0));
        findViewById(R.id.policeBtn).setOnClickListener(v->startPolice());
        findViewById(R.id.discoBtn).setOnClickListener(v->startDisco());
        findViewById(R.id.strobeBtn).setOnClickListener(v->startStrobe());
        findViewById(R.id.flashBtn).setOnClickListener(v->startFlash());
        findViewById(R.id.fadeBtn).setOnClickListener(v->startFade());
        findViewById(R.id.lightningBtn).setOnClickListener(v->startLightning());
        findViewById(R.id.candleBtn).setOnClickListener(v->startCandle());
        findViewById(R.id.oceanBtn).setOnClickListener(v->startScene(LampEffects.Kind.OCEAN,"OCEAN"));
        findViewById(R.id.sunsetBtn).setOnClickListener(v->startScene(LampEffects.Kind.SUNSET,"SUNSET"));
        findViewById(R.id.pulseBtn).setOnClickListener(v->startScene(LampEffects.Kind.PULSE,"PULSE"));
        findViewById(R.id.tvBtn).setOnClickListener(v->startScene(LampEffects.Kind.TV,"TV"));
        findViewById(R.id.neonBtn).setOnClickListener(v->startScene(LampEffects.Kind.NEON,"NEON"));
        findViewById(R.id.auroraBtn).setOnClickListener(v->startScene(LampEffects.Kind.AURORA,"AURORA FLOW"));
        findViewById(R.id.breatheBtn).setOnClickListener(v->startScene(LampEffects.Kind.BREATHING,"SOFT BREATH"));
        findViewById(R.id.sleepBtn).setOnClickListener(v->startScene(LampEffects.Kind.SLEEP,"SLEEP • 15 MIN"));
        fxSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
          public void onProgressChanged(SeekBar s,int p,boolean fromUser){if(fromUser) lampEffects.changeSpeed(p);}
          public void onStartTrackingTouch(SeekBar s){}
          public void onStopTrackingTouch(SeekBar s){}
        });
        findViewById(R.id.stopFxBtn).setOnClickListener(v->stopFx(true));
        requestBtPermissions();
        updateButtons();
        setupManualSliders();
        setupColorPalette();
        setupTabs();
        setupCctFallback();
        setupDiagnostics2();
        setupQuickColors();
        setupTimerControls();
        target1.setOnClickListener(v->selectTarget(1));
        target2.setOnClickListener(v->selectTarget(2));
        targetBoth.setOnClickListener(v->selectTarget(3));
        selectTarget(prefs.getInt("target_mask",1));
        setupCollapsibleSections();
        new Handler(Looper.getMainLooper()).postDelayed(this::autoReconnectSaved, 900);
    }

    private int readyMask(int mask){
        return ((mask&1)!=0&&light1.isReady()?1:0)|((mask&2)!=0&&light2.isReady()?2:0);
    }
    private void showPowerMessage(String action,int requested,int ready){
        if(ready==0){
            status.setText(action+": brak połączonej lampy. Połącz Bluetooth.");
            Toast.makeText(this,"Brak połączonych lamp",Toast.LENGTH_SHORT).show();
        }else{
            status.setText(action+": komenda wysłana do "+targetLabel(ready)
                +(ready==requested?"":" (pozostałe rozłączone)"));
        }
    }
    private void refreshTargetLabel(){
        if(selectedTarget==null || light1==null || light2==null)return;
        int mask=selectedMask(),allowed=enabledMask(mask);
        String state=allowed==mask?"WŁĄCZONA":allowed==0?"NACIŚNIJ WŁĄCZ":"CZĘŚCIOWO WŁĄCZONE";
        selectedTarget.setText(targetLabel(mask).toUpperCase(java.util.Locale.ROOT)+" • "+state);
        selectedTarget.setTextColor(android.graphics.Color.parseColor(allowed==mask?"#84EBC8":"#CEB4FF"));
    }
    private void setupCollapsibleSections(){
        setTimerExpanded(prefs.getBoolean("timer_expanded",false));
        setDiagnosticsExpanded(prefs.getBoolean("diagnostics_expanded",false));
        findViewById(R.id.timerToggle).setOnClickListener(v->{
            boolean expanded=timerBody.getVisibility()!=View.VISIBLE;
            prefs.edit().putBoolean("timer_expanded",expanded).apply();
            setTimerExpanded(expanded);
        });
        findViewById(R.id.diagToggle).setOnClickListener(v->{
            boolean expanded=diagnosticsBody.getVisibility()!=View.VISIBLE;
            prefs.edit().putBoolean("diagnostics_expanded",expanded).apply();
            setDiagnosticsExpanded(expanded);
        });
    }
    private void setTimerExpanded(boolean expanded){
        timerBody.setVisibility(expanded?View.VISIBLE:View.GONE);
        ((Button)findViewById(R.id.timerToggle)).setText(expanded?"⌄ Ukryj timer":"⏱ Wyłącznik czasowy ▾");
    }
    private void setDiagnosticsExpanded(boolean expanded){
        diagnosticsBody.setVisibility(expanded?View.VISIBLE:View.GONE);
        ((Button)findViewById(R.id.diagToggle)).setText(expanded?"⌄ Ukryj diagnostykę":"⚙ Serwis i diagnostyka ▾");
    }
    private void updateTimerPresets(){
        int[] ids={R.id.timer5,R.id.timer15,R.id.timer30,R.id.timer60,R.id.timer120};
        int current=timerDuration.getSelectedItemPosition();
        for(int i=0;i<ids.length;i++){
            ((Button)findViewById(ids[i])).setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor(i==current?"#7445CE":"#29334E")));
        }
    }
    private void setupTimerControls(){
        String[] choices={"5 minut","15 minut","30 minut","1 godzina","2 godziny","4 godziny"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        timerDuration.setAdapter(adapter);
        timerDuration.setSelection(Math.max(0,Math.min(5,prefs.getInt("last_timer_index",2))));
        int[] presets={R.id.timer5,R.id.timer15,R.id.timer30,R.id.timer60,R.id.timer120};
        for(int i=0;i<presets.length;i++){
            final int index=i;
            findViewById(presets[i]).setOnClickListener(v->timerDuration.setSelection(index));
        }
        timerDuration.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                prefs.edit().putInt("last_timer_index",pos).apply();updateTimerPresets();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        updateTimerPresets();
        findViewById(R.id.startTimerBtn).setOnClickListener(v->{
            int mask=selectedMask();
            int ready=readyMask(mask);
            if(ready!=mask){
                status.setText("Połącz najpierw wszystkie wybrane lampy");
                Toast.makeText(this,"Wymagane połączenie obu wybranych lamp",Toast.LENGTH_LONG).show();
                return;
            }
            long duration=timerMinutes[timerDuration.getSelectedItemPosition()]*60_000L;
            Intent intent=new Intent(this,LampTimerService.class);
            intent.setAction(LampTimerService.ACTION_SET);
            intent.putExtra("mask",mask);
            intent.putExtra("duration",duration);
            try{
                if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
                status.setText("Timer aktywny • "+targetLabel(mask));
                timerUi.postDelayed(this::refreshTimers,350);
            }catch(Exception ex){
                status.setText("Nie można włączyć timera: "+ex.getClass().getSimpleName());
            }
        });
        findViewById(R.id.cancelTimerBtn).setOnClickListener(v->{
            cancelTimer(selectedMask());
            status.setText("Anulowano timer • "+targetLabel(selectedMask()));
        });
        refreshTimers();
    }
    private void cancelTimer(int mask){
        Intent intent=new Intent(this,LampTimerService.class);
        intent.setAction(LampTimerService.ACTION_CANCEL);
        intent.putExtra("mask",mask);
        try{
            // The service may already be active. startService updates it without
            // forcing a new foreground service when no timer is running.
            startService(intent);
        }catch(Exception ignored){}
        timerUi.postDelayed(this::refreshTimers,300);
    }
    private void refreshTimers(){
        long now=System.currentTimeMillis();
        TextView[] labels={timerStatus1,timerStatus2};
        for(int i=0;i<2;i++){
            long end=prefs.getLong("timer_end_"+(i+1),0L);
            long remaining=Math.max(0L,end-now);
            String value=remaining<=0?"brak odliczania":
                String.format(java.util.Locale.getDefault(),"%02d:%02d:%02d",
                    remaining/3600000L,(remaining/60000L)%60,(remaining/1000L)%60);
            labels[i].setText("Lampa "+(i+1)+": "+value);
        }
    }
    @Override protected void onStart(){
        super.onStart();
        IntentFilter filter=new IntentFilter(LampTimerService.ACTION_CHANGED);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(timerReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(timerReceiver,filter);
        timerReceiverRegistered=true;
        timerUi.post(timerTick);
    }
    @Override protected void onStop(){
        timerUi.removeCallbacks(timerTick);
        if(timerReceiverRegistered){unregisterReceiver(timerReceiver);timerReceiverRegistered=false;}
        super.onStop();
    }

    private String shortMac(String mac){
        if(mac==null || mac.length()<8) return "";
        return mac.substring(Math.max(0, mac.length()-8));
    }

    private void updateButtons(){
        String m1=prefs.getString(PREF_L1,null), m2=prefs.getString(PREF_L2,null);
        String n1=prefs.getString(PREF_L1_NAME,"YN360"), n2=prefs.getString(PREF_L2_NAME,"YN360");
        ((Button)findViewById(R.id.connect1)).setText(m1==null?"Połącz":"Zmień");
        ((Button)findViewById(R.id.connect2)).setText(m2==null?"Połącz":"Zmień");
        lamp1State.setText(light1.isReady()?"● Połączona • "+n1:(m1!=null?"◌ Zapamiętana":"○ Rozłączona"));
        lamp2State.setText(light2.isReady()?"● Połączona • "+n2:(m2!=null?"◌ Zapamiętana":"○ Rozłączona"));
        lamp1State.setTextColor(android.graphics.Color.parseColor(light1.isReady()?"#41E7A8":"#9CA9C4"));
        lamp2State.setTextColor(android.graphics.Color.parseColor(light2.isReady()?"#41E7A8":"#9CA9C4"));
        refreshTargetLabel();
    }

    private boolean hasBtPermissions(){
        if(Build.VERSION.SDK_INT>=31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestBtPermissions(){
        if(Build.VERSION.SDK_INT>=31 && !hasBtPermissions())
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},55);
        else if(Build.VERSION.SDK_INT<31 && !hasBtPermissions())
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},56);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(hasBtPermissions()) new Handler(Looper.getMainLooper()).postDelayed(this::autoReconnectSaved, 300);
    }

    @SuppressWarnings("MissingPermission")
    private void autoReconnectSaved(){
        if(!hasBtPermissions() || adapter==null || !adapter.isEnabled()) return;
        String m1=prefs.getString(PREF_L1,null), m2=prefs.getString(PREF_L2,null);
        try { if(m1!=null) light1.connect(adapter.getRemoteDevice(m1)); } catch(Exception ignored){}
        try { if(m2!=null && !m2.equals(m1)) light2.connect(adapter.getRemoteDevice(m2));
            else if(m2!=null) status.setText("Te same adresy BLE dla obu lamp. Wybierz osobne urządzenia.");
        } catch(Exception ignored){}
    }

    @SuppressWarnings("MissingPermission")
    private void chooseVerifiedLamp(int slot){
        requestBtPermissions();
        if(!hasBtPermissions()) return;
        if(adapter==null || !adapter.isEnabled()){ Toast.makeText(this,"Włącz Bluetooth",Toast.LENGTH_SHORT).show(); return; }

        BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();
        LinkedHashMap<String,Candidate> found=new LinkedHashMap<>();
        status.setText("Szukam lamp YN360…");

        ScanCallback cb=new ScanCallback(){
            @Override public void onScanResult(int type, ScanResult r){
                BluetoothDevice d=r.getDevice();
                ScanRecord sr=r.getScanRecord();
                String name=null;
                try { if(sr!=null) name=sr.getDeviceName(); } catch(Exception ignored){}
                if(name==null || name.trim().isEmpty()) { try { name=d.getName(); } catch(Exception ignored){} }
                boolean adv=false;
                try {
                    if(sr!=null && sr.getServiceUuids()!=null){
                        for(android.os.ParcelUuid u: sr.getServiceUuids()) if(BleLightController.SERVICE_UUID.equals(u.getUuid())) { adv=true; break; }
                    }
                } catch(Exception ignored){}
                Candidate old=found.get(d.getAddress());
                if(old==null || r.getRssi()>old.rssi){
                    found.put(d.getAddress(), new Candidate(d,r.getRssi(),name,adv));
                } else if(old.name==null && name!=null){ old.name=name; }
            }
        };
        scanner.startScan(cb);
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            try{scanner.stopScan(cb);}catch(Exception ignored){}
            if(found.isEmpty()){ status.setText("Nie wykryto BLE. Zbliż telefon do lamp i spróbuj ponownie."); return; }
            ArrayList<Candidate> list=new ArrayList<>(found.values());
            list.sort((a,b)->{ if(a.advertisesYnService!=b.advertisesYnService) return a.advertisesYnService?-1:1; return Integer.compare(b.rssi,a.rssi); });
            // Najpierw sprawdzamy najmocniejsze sygnały. Zatrzymujemy się, gdy znajdziemy obie lampy.
            int limit=Math.min(30,list.size());
            probeSequential(slot, list.subList(0,limit), 0, new ArrayList<>());
        },6000);
    }

    @SuppressWarnings("MissingPermission")
    private void probeSequential(int slot, List<Candidate> list, int index, ArrayList<Candidate> verified){
        if(verified.size()>=2 || index>=list.size()){
            showVerifiedDialog(slot, verified);
            return;
        }
        Candidate c=list.get(index);
        status.setText("Sprawdzam urządzenia… "+(index+1)+"/"+list.size()+"   znaleziono: "+verified.size());

        final BluetoothGatt[] holder={null};
        final boolean[] finished={false};
        Runnable timeout=()->{
            if(finished[0]) return; finished[0]=true;
            try{ if(holder[0]!=null){ holder[0].disconnect(); holder[0].close(); } }catch(Exception ignored){}
            probeSequential(slot,list,index+1,verified);
        };

        BluetoothGattCallback gcb=new BluetoothGattCallback(){
            @Override public void onConnectionStateChange(BluetoothGatt g,int statusCode,int newState){
                if(finished[0]) return;
                if(statusCode==BluetoothGatt.GATT_SUCCESS && newState==BluetoothProfile.STATE_CONNECTED){
                    g.discoverServices();
                } else if(newState==BluetoothProfile.STATE_DISCONNECTED){
                    runOnUiThread(timeout);
                }
            }
            @Override public void onServicesDiscovered(BluetoothGatt g,int statusCode){
                if(finished[0]) return;
                boolean ok=false;
                if(statusCode==BluetoothGatt.GATT_SUCCESS){
                    BluetoothGattService s=g.getService(BleLightController.SERVICE_UUID);
                    ok=s!=null && s.getCharacteristic(BleLightController.SEND_UUID)!=null;
                }
                finished[0]=true;
                try{g.disconnect();g.close();}catch(Exception ignored){}
                if(ok) verified.add(c);
                final int next=index+1;
                runOnUiThread(()->probeSequential(slot,list,next,verified));
            }
        };
        try{
            holder[0]=c.device.connectGatt(this,false,gcb,BluetoothDevice.TRANSPORT_LE);
            new Handler(Looper.getMainLooper()).postDelayed(timeout,2400);
        }catch(Exception e){
            probeSequential(slot,list,index+1,verified);
        }
    }

    @SuppressWarnings("MissingPermission")
    private void showVerifiedDialog(int slot, ArrayList<Candidate> verified){
        if(verified.isEmpty()){
            status.setText("Nie znalazłem YN360. Włącz lampy i trzymaj telefon blisko nich.");
            new AlertDialog.Builder(this)
                    .setTitle("Nie znaleziono YN360")
                    .setMessage("Aplikacja sprawdziła urządzenia BLE, ale żadne nie udostępniło usługi sterowania YN360. Spróbuj ponownie z włączoną tylko jedną lampą i telefonem obok niej.")
                    .setPositiveButton("OK",null).show();
            return;
        }
        String[] names=new String[verified.size()];
        for(int i=0;i<verified.size();i++){
            Candidate c=verified.get(i);
            int bars=c.rssi>-55?3:(c.rssi>-70?2:1);
            String strength=bars==3?"bardzo blisko":(bars==2?"blisko":"słabszy sygnał");
            String shown=(c.name!=null && !c.name.trim().isEmpty())?c.name.trim():"YN360";
            names[i]=shown+"  •  "+strength+"  ("+c.rssi+" dBm)\n"+c.device.getAddress();
        }
        status.setText("Znaleziono "+verified.size()+" lampę/lampy YN360 ✓");
        new AlertDialog.Builder(this).setTitle("Wybierz lampę "+slot).setItems(names,(dlg,which)->{
            Candidate c=verified.get(which);
            String other=slot==1?prefs.getString(PREF_L2,null):prefs.getString(PREF_L1,null);
            if(other!=null && other.equals(c.device.getAddress())){
                Toast.makeText(this,"Ta lampa jest już przypisana do drugiego przycisku",Toast.LENGTH_LONG).show(); return;
            }
            String shown=(c.name!=null && !c.name.trim().isEmpty())?c.name.trim():"YN360";
            SharedPreferences.Editor ed=prefs.edit().putString(slot==1?PREF_L1:PREF_L2,c.device.getAddress());
            ed.putString(slot==1?PREF_L1_NAME:PREF_L2_NAME,shown).apply();
            if(slot==1) light1.connect(c.device); else light2.connect(c.device);
            updateButtons();
        }).setNeutralButton("Wyczyść zapisaną lampę",(d,w)->{
            prefs.edit().remove(slot==1?PREF_L1:PREF_L2).remove(slot==1?PREF_L1_NAME:PREF_L2_NAME).apply();
            if(slot==1) light1.disconnect(); else light2.disconnect();
            updateButtons();
        }).show();
    }


    private void identify(BleLightController light, String label){
        if(!light.isReady()){Toast.makeText(this,label+" nie jest połączona",Toast.LENGTH_SHORT).show();return;}
        if(!light.isUserEnabled()){status.setText("Najpierw naciśnij Włącz, aby wykonać test");return;}
        lampEffects.start(LampEffects.Kind.IDENTIFY,light==light1?1:2,220,45,255,255,255);
        status.setText(label+" miga 3×");
    }

    // Live controls: throttle BLE writes to avoid flooding GATT queues.
    private void queueLiveRgb(int mask,int r,int g,int b) {
        if(palettePending!=null) ui.removeCallbacks(palettePending);
        stageRgb(mask);
        int allowed=enabledMask(mask);
        if(allowed==0){
            palettePending=null;
            status.setText("Kolor zapisany w podglądzie • naciśnij Włącz");
            return;
        }
        final int rr=r,gg=g,bb=b,mm=allowed;
        long delay=Math.max(0,85-(android.os.SystemClock.uptimeMillis()-lastLiveSend));
        palettePending=()->{
            palettePending=null;
            lastLiveSend=android.os.SystemClock.uptimeMillis();
            applyRgbToMask(mm,rr,gg,bb);
        };
        ui.postDelayed(palettePending,delay);
    }

    private void setupManualSliders(){
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean fromUser){
                if(!fromUser || slidersFromPalette) return;
                stopFx(false);
                final int mask=selectedMask();
                if(s==red || s==green || s==blue) {
                    manualMode=1;
                    paletteR=red.getProgress(); paletteG=green.getProgress(); paletteB=blue.getProgress();
                    updatePreview(paletteR,paletteG,paletteB);
                    queueLiveRgb(mask,paletteR,paletteG,paletteB);
                } else if(s==brightness) {
                    if(manualMode==1) queueLiveRgb(mask,red.getProgress(),green.getProgress(),blue.getProgress());
                    else { stageWhite(mask);applyWhiteToMask(mask); }
                } else if(s==brightnessCct || s==whiteTemp){
                    manualMode=0;
                    stageWhite(mask);
                    applyWhiteToMask(mask);
                }
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        };
        brightness.setOnSeekBarChangeListener(listener);
        brightnessCct.setOnSeekBarChangeListener(listener);
        whiteTemp.setOnSeekBarChangeListener(listener);
        red.setOnSeekBarChangeListener(listener);
        green.setOnSeekBarChangeListener(listener);
        blue.setOnSeekBarChangeListener(listener);
    }

    private void setupColorPalette(){
        updatePreview(paletteR,paletteG,paletteB);
        colorPalette.setOnColorChangedListener((r,g,b)->{
            if(lampEffects.hasActive(selectedMask())) stopFx(false);
            manualMode=1;
            paletteR=r; paletteG=g; paletteB=b;
            slidersFromPalette=true;
            red.setProgress(r); green.setProgress(g); blue.setProgress(b);
            slidersFromPalette=false;
            updatePreview(r,g,b);
            queueLiveRgb(selectedMask(),r,g,b);
        });
    }


    private void setupTabs() {
        findViewById(R.id.tabRgb).setOnClickListener(v->applySelectedMode(0));
        findViewById(R.id.tabWhite).setOnClickListener(v->applySelectedMode(1));
        findViewById(R.id.tabFx).setOnClickListener(v->showTab(2));
        showTab(0);  // Display only during initial UI setup, no BLE writes yet.
    }

    private void setupDiagnostics2() {
        String[] channels={"CH1","CH2","CH3","CH4","CH5","CH6","CH7","CH8"};
        ArrayAdapter<String> channelAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,channels);
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cctChannel2.setAdapter(channelAdapter);
        int savedChannel=prefs.getInt("cct_channel_2",1);
        cctChannel2.setSelection(Math.max(0,Math.min(7,savedChannel-1)));
        cctChannel2.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                int channel=position+1;
                light2.setWhiteChannel(channel);
                prefs.edit().putInt("cct_channel_2",channel).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        findViewById(R.id.testCctChannel2).setOnClickListener(v->{
            int channel=cctChannel2.getSelectedItemPosition()+1;
            light2.setWhiteChannel(channel);
            runLamp2Test("CCT LAMPA 2 • CH"+channel,()->light2.setModeWhite(70,70));
        });

        diagUseAck2.setChecked(prefs.getBoolean("lamp2_prefer_ack",false));
        light2.setPreferResponse(diagUseAck2.isChecked());
        diagUseAck2.setOnCheckedChangeListener((button,enabled)->{
            prefs.edit().putBoolean("lamp2_prefer_ack",enabled).apply();
            light2.setPreferResponse(enabled);
            diagLast2=enabled?"Tryb testowy: WRITE z ACK, o ile obsługiwany":"Automatyczny tryb GATT";
            showDiag2();
        });
        findViewById(R.id.diagIdentify1).setOnClickListener(v->{
            if(!light1.isReady()){Toast.makeText(this,"Lampa 1 niepołączona",Toast.LENGTH_SHORT).show();return;}
            identify(light1,"Lampa 1");
        });
        findViewById(R.id.diagIdentify2).setOnClickListener(v->{
            if(!light2.isReady()){Toast.makeText(this,"Lampa 2 niepołączona",Toast.LENGTH_SHORT).show();return;}
            identify(light2,"Lampa 2");
            diagLast2="Identyfikacja fizyczna Lampy 2 — sprawdź, która lampa miga";
            showDiag2();
        });
        findViewById(R.id.diagRgb2).setOnClickListener(v->runLamp2Test("RGB CZERWONY",()->light2.setModeRgb(255,0,0)));
        findViewById(R.id.diagWhite2).setOnClickListener(v->runLamp2Test("NATYWNE CCT • CH"+light2.getWhiteChannel(),()->light2.setModeWhite(50,50)));
        findViewById(R.id.diagFallback2).setOnClickListener(v->runLamp2Test("BIEL PRZEZ RGB",()->light2.setModeRgb(255,220,180)));
        findViewById(R.id.diagReconnect2).setOnClickListener(v->{
            String addr=prefs.getString(PREF_L2,null);
            if(addr==null || adapter==null || !adapter.isEnabled() || !hasBtPermissions()) {
                diagLast2="Nie można połączyć — brak zapisanej Lampy 2 lub pozwolenia Bluetooth";
                showDiag2();
                return;
            }
            lampEffects.stop(2);
            light2.disconnect();
            diagLast2="Ponowne łączenie Lampy 2…";
            showDiag2();
            ui.postDelayed(()->{
                try{light2.connect(adapter.getRemoteDevice(addr));}
                catch(Exception error){diagLast2="Błąd ponownego łączenia: "+error.getClass().getSimpleName();showDiag2();}
            },500);
        });
        findViewById(R.id.diagCopy2).setOnClickListener(v->{
            String report="YN360 Control v1.2 • Lampa 2\n"+diagLast2+"\n"+light2.debugSnapshot();
            android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("YN360 diagnostyka L2",report));
            Toast.makeText(this,"Skopiowano diagnostykę Lampy 2",Toast.LENGTH_SHORT).show();
        });
        showDiag2();
    }
    private void runLamp2Test(String name,Runnable command) {
        lampEffects.stop(2);
        if(!light2.isReady()){
            diagLast2=name+": brak aktywnego połączenia BLE. Użyj Połącz ponownie.";
            showDiag2();return;
        }
        if(!light2.isUserEnabled()){
            diagLast2=name+": najpierw naciśnij Włącz dla Lampy 2";
            showDiag2();return;
        }
        command.run();
        diagLast2=name+": żądanie wysłane do kolejki Lampy 2 (sprawdź reakcję)";
        showDiag2();
        ui.postDelayed(this::showDiag2,1200);
    }
    private void showDiag2(){
        if(diagResults2==null || light2==null)return;
        diagResults2.setText(diagLast2+"\n"+light2.debugSnapshot());
    }

    private void setupCctFallback() {
        cctRgbFallback2.setChecked(prefs.getBoolean("cct_rgb_fallback_2",false));
        cctRgbFallback2.setOnCheckedChangeListener((button,checked)->{
            prefs.edit().putBoolean("cct_rgb_fallback_2",checked).apply();
            if(activeTab==1 && light2!=null && light2.isUserEnabled()) applySelectedMode(1);
        });
    }
    private void applySelectedMode(int mode) {
        showTab(mode);
        stopFx(false);
        int mask=selectedMask();
        manualMode=mode==1?0:1;
        if(mode==1)stageWhite(mask);else stageRgb(mask);
        int allowed=enabledMask(mask);
        if(allowed==0){
            status.setText((mode==1?"Biel":"Kolor")+" zapisany w podglądzie • naciśnij Włącz");
            return;
        }
        if(mode==1) applyWhiteToMask(allowed,true);
        else forMask(allowed,l->l.setModeRgb(scaled(red.getProgress()),scaled(green.getProgress()),scaled(blue.getProgress())));
        status.setText((mode==1?"CCT":"RGB")+" • "+targetLabel(allowed));
    }
    private void showTab(int selected) {
        activeTab=selected;
        findViewById(R.id.rgbSection).setVisibility(selected==0?View.VISIBLE:View.GONE);
        findViewById(R.id.whiteSection).setVisibility(selected==1?View.VISIBLE:View.GONE);
        findViewById(R.id.fxSection).setVisibility(selected==2?View.VISIBLE:View.GONE);
        int active=android.graphics.Color.parseColor("#7742D9");
        int inactive=android.graphics.Color.parseColor("#252F46");
        int[] ids={R.id.tabRgb,R.id.tabWhite,R.id.tabFx};
        for(int i=0;i<3;i++) {
            ((Button)findViewById(ids[i])).setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(i==selected?active:inactive));
        }
    }
    private void setupQuickColors() {
        int[] ids={R.id.quickRed,R.id.quickOrange,R.id.quickYellow,R.id.quickGreen,R.id.quickCyan,R.id.quickBlue,R.id.quickPink};
        int[][] colors={{255,20,40},{255,125,8},{255,225,20},{20,235,70},{0,225,250},{25,75,255},{255,0,180}};
        for(int i=0;i<ids.length;i++) {
            final int index=i;
            findViewById(ids[i]).setOnClickListener(v->selectQuickColor(colors[index]));
        }
    }
    private void selectQuickColor(int[] c) {
        stopFx(false);
        manualMode=1;
        slidersFromPalette=true;
        red.setProgress(c[0]); green.setProgress(c[1]); blue.setProgress(c[2]);
        slidersFromPalette=false;
        paletteR=c[0]; paletteG=c[1]; paletteB=c[2];
        colorPalette.setColor(c[0],c[1],c[2]);
        updatePreview(c[0],c[1],c[2]);
        queueLiveRgb(selectedMask(),c[0],c[1],c[2]);
    }

    private void updatePreview(int r,int g,int b){
        colorPalette.setColor(r,g,b);
        colorPreview.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(r,g,b)));
        if(colorValue!=null) colorValue.setText(String.format(java.util.Locale.US,"#%02X%02X%02X • RGB %d, %d, %d",r,g,b,r,g,b));
    }

    /** Switching the target never sends a light command or interrupts the other lamp. */
    private void selectTarget(int mask){
        selectedTargetMask=mask==2?2:mask==3?3:1;
        prefs.edit().putInt("target_mask",selectedTargetMask).apply();
        int active=android.graphics.Color.parseColor("#7545D6");
        int normal=android.graphics.Color.parseColor("#222D47");
        Button[] tabs={target1,target2,targetBoth};
        for(int i=0;i<tabs.length;i++){
            boolean chosen=(i==0&&selectedTargetMask==1)
                ||(i==1&&selectedTargetMask==2)||(i==2&&selectedTargetMask==3);
            tabs[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(chosen?active:normal));
            tabs[i].setTextColor(android.graphics.Color.parseColor(chosen?"#FFFFFF":"#B8C5E0"));
            tabs[i].setSelected(chosen);
        }
        refreshTargetLabel();
        if(palettePending!=null){ui.removeCallbacks(palettePending);palettePending=null;}
        status.setText("Sterowanie: "+targetLabel(selectedTargetMask)+" • bez zmiany ustawień lamp");
    }

    private interface Action { void run(BleLightController l); }

    private int selectedMask(){ return selectedTargetMask; }

    private void forMask(int mask, Action a){
        if((mask&1)!=0 && light1.isReady()) a.run(light1);
        if((mask&2)!=0 && light2.isReady()) a.run(light2);
    }

    private void cancelForMask(int mask){
        if((mask&1)!=0) light1.cancelPending();
        if((mask&2)!=0) light2.cancelPending();
    }

    private int scaled(int v){ return Math.max(0,Math.min(255, v*brightness.getProgress()/255)); }
    private int enabledMask(int mask){
        return ((mask&1)!=0&&light1.isReady()&&light1.isUserEnabled()?1:0)
            |((mask&2)!=0&&light2.isReady()&&light2.isUserEnabled()?2:0);
    }
    private void stageRgb(int mask){
        for(int i=0;i<2;i++)if((mask&(1<<i))!=0){
            draftMode[i]=DRAFT_RGB;
            draftRgb[i][0]=scaled(red.getProgress());
            draftRgb[i][1]=scaled(green.getProgress());
            draftRgb[i][2]=scaled(blue.getProgress());
        }
    }
    private void stageWhite(int mask){
        int temp=whiteTemp.getProgress(),brightnessValue=brightnessCct.getProgress();
        int value99=Math.max(0,brightnessValue*99/255);
        int cool=value99*temp/255,warm=value99*(255-temp)/255;
        for(int i=0;i<2;i++)if((mask&(1<<i))!=0){
            draftMode[i]=DRAFT_CCT;
            draftCct[i][0]=cool;draftCct[i][1]=warm;
            draftCct[i][2]=temp;draftCct[i][3]=brightnessValue;
        }
    }
    private void applyPowerOn(int index){
        BleLightController lamp=index==0?light1:light2;
        if(!lamp.isReady())return;
        lamp.setUserEnabled(true);
        if(draftMode[index]==DRAFT_RGB){
            lamp.setModeRgb(draftRgb[index][0],draftRgb[index][1],draftRgb[index][2]);
        }else if(draftMode[index]==DRAFT_CCT){
            if(index==1&&cctRgbFallback2!=null&&cctRgbFallback2.isChecked()){
                float fraction=draftCct[index][2]/255f,level=draftCct[index][3]/255f;
                lamp.setModeRgb(Math.round((255-28*fraction)*level),
                    Math.round((149+90*fraction)*level),
                    Math.round((80+175*fraction)*level));
            }else lamp.setModeWhite(draftCct[index][0],draftCct[index][1]);
        }else lamp.turnOn();
    }
    private void applyRgb(int r,int g,int b){ applyRgbToMask(selectedMask(),r,g,b); }
    private void applyRgbToMask(int mask,int r,int g,int b){ forMask(enabledMask(mask),l->l.rgb(scaled(r),scaled(g),scaled(b))); }
    private void applyWhite(){ applyWhiteToMask(selectedMask()); }
    private void applyWhiteToMask(int mask){ applyWhiteToMask(mask,false); }
    private void applyWhiteToMask(int mask,boolean priority){
        mask=enabledMask(mask);
        final int temp=whiteTemp.getProgress();
        final int b99=Math.max(0,brightnessCct.getProgress()*99/255);
        final int cool=b99*temp/255;
        final int warm=b99*(255-temp)/255;
        if((mask&1)!=0 && light1.isReady()) { if(priority)light1.setModeWhite(cool,warm); else light1.white(cool,warm); }
        if((mask&2)!=0 && light2.isReady()){
            if(cctRgbFallback2!=null && cctRgbFallback2.isChecked()){
                // Compatibility option for a lamp that accepts RGB but ignores native white packets.
                // Approximate cool/warm appearance using the RGB LEDs (not true high-CRI CCT).
                float fraction=temp/255f;
                int r=Math.round((255-28*fraction)*brightnessCct.getProgress()/255f);
                int g=Math.round((149+90*fraction)*brightnessCct.getProgress()/255f);
                int b=Math.round((80+175*fraction)*brightnessCct.getProgress()/255f);
                if(priority)light2.setModeRgb(r,g,b);else light2.rgb(r,g,b);
            } else {if(priority)light2.setModeWhite(cool,warm);else light2.white(cool,warm);}
        }
    }


    private void stopFx(boolean announce) {
        if(palettePending!=null){ui.removeCallbacks(palettePending);palettePending=null;}
        lampEffects.stop(selectedMask());
        if(announce) status.setText("Zatrzymano • "+targetLabel(selectedMask()));
    }
    private void startScene(LampEffects.Kind kind,String name) {
        if(palettePending!=null){ui.removeCallbacks(palettePending);palettePending=null;}
        int mask=selectedMask();
        int allowed=enabledMask(mask);
        if(allowed==0){status.setText("Najpierw naciśnij Włącz • scena nie uruchomiona");return;}
        int actual=lampEffects.start(kind,allowed,brightness.getProgress(),fxSpeed.getProgress(),red.getProgress(),green.getProgress(),blue.getProgress());
        if(actual==0){status.setText("Połącz wybraną lampę przez Bluetooth");Toast.makeText(this,"Brak połączenia",Toast.LENGTH_SHORT).show();}
        else status.setText(name+" • "+targetLabel(actual)+(actual==mask?"":" (podłączone)"));
    }
    private void startPolice(){startScene(LampEffects.Kind.POLICE,"POLICE");}
    private void startDisco(){startScene(LampEffects.Kind.DISCO,"DISCO");}
    private void startStrobe(){startScene(LampEffects.Kind.STROBE,"STROBE ⚠");}
    private void startFlash(){startScene(LampEffects.Kind.FLASH,"FLASH");}
    private void startFade(){startScene(LampEffects.Kind.FADE,"COLOR FADE");}
    private void startLightning(){startScene(LampEffects.Kind.LIGHTNING,"LIGHTNING");}
    private void startCandle(){startScene(LampEffects.Kind.CANDLE,"FIRE / CANDLE");}

    private String targetLabel(int mask){ return mask==1?"Lampa 1":(mask==2?"Lampa 2":"Obie"); }

    private void openUpdates(){
        try {
            Intent intent=new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/grbk420/YN360CONTROL/releases/latest"));
            startActivity(intent);
        } catch(Exception e){
            Toast.makeText(this,"Nie mogę otworzyć strony aktualizacji",Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy(){ super.onDestroy(); if(palettePending!=null) ui.removeCallbacks(palettePending); lampEffects.close(); light1.disconnect(); light2.disconnect(); }
}
