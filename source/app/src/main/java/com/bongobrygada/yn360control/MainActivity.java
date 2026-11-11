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
    private SeekBar brightness, whiteTemp, red, green, blue, fxSpeed;
    private ColorPaletteView colorPalette;
    private android.view.View colorPreview;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable palettePending;
    private long lastLiveSend=0L;
    private boolean slidersFromPalette=false;
    private int paletteR=255, paletteG=0, paletteB=0;
    private int fxToken=0;
    private RadioButton target1, target2, targetBoth;
    private final Handler fx = new Handler(Looper.getMainLooper());
    private Runnable currentFx;
    private final Random random = new Random();
    private int fadeHue = 0;
    private int manualMode = 0; // 0 = white, 1 = RGB
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
        brightness=findViewById(R.id.brightness); whiteTemp=findViewById(R.id.whiteTemp);
        red=findViewById(R.id.red); green=findViewById(R.id.green); blue=findViewById(R.id.blue);
        fxSpeed=findViewById(R.id.fxSpeed);
        colorPalette=findViewById(R.id.colorPalette); colorPreview=findViewById(R.id.colorPreview);
        target1=findViewById(R.id.target1); target2=findViewById(R.id.target2); targetBoth=findViewById(R.id.targetBoth);

        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); adapter=bm.getAdapter();
        light1=new BleLightController(this, s->runOnUiThread(()->{status.setText("L1: "+s); updateButtons();}));
        light2=new BleLightController(this, s->runOnUiThread(()->{status.setText("L2: "+s); updateButtons();}));

        findViewById(R.id.updatesBtn).setOnClickListener(v->openUpdates());
        findViewById(R.id.connect1).setOnClickListener(v->chooseVerifiedLamp(1));
        findViewById(R.id.connect2).setOnClickListener(v->chooseVerifiedLamp(2));
        findViewById(R.id.identify1).setOnClickListener(v->identify(light1, "Lampa 1"));
        findViewById(R.id.identify2).setOnClickListener(v->identify(light2, "Lampa 2"));
        findViewById(R.id.onBtn).setOnClickListener(v->{stopFx(false); manualMode=0; applyWhite();});
        findViewById(R.id.offBtn).setOnClickListener(v->{stopFx(false); forMask(selectedMask(),BleLightController::off);});
        findViewById(R.id.whiteBtn).setOnClickListener(v->{stopFx(false); manualMode=0; applyWhite();});
        findViewById(R.id.rgbBtn).setOnClickListener(v->{stopFx(false); manualMode=1; applyRgb(red.getProgress(),green.getProgress(),blue.getProgress());});
        findViewById(R.id.policeBtn).setOnClickListener(v->startPolice());
        findViewById(R.id.discoBtn).setOnClickListener(v->startDisco());
        findViewById(R.id.strobeBtn).setOnClickListener(v->startStrobe());
        findViewById(R.id.flashBtn).setOnClickListener(v->startFlash());
        findViewById(R.id.fadeBtn).setOnClickListener(v->startFade());
        findViewById(R.id.lightningBtn).setOnClickListener(v->startLightning());
        findViewById(R.id.candleBtn).setOnClickListener(v->startCandle());
        findViewById(R.id.stopFxBtn).setOnClickListener(v->stopFx(true));
        requestBtPermissions();
        updateButtons();
        setupManualSliders();
        setupColorPalette();
        target1.setOnClickListener(v->onTargetChanged());
        target2.setOnClickListener(v->onTargetChanged());
        targetBoth.setOnClickListener(v->onTargetChanged());
        new Handler(Looper.getMainLooper()).postDelayed(this::autoReconnectSaved, 900);
    }

    private String shortMac(String mac){
        if(mac==null || mac.length()<8) return "";
        return mac.substring(Math.max(0, mac.length()-8));
    }

    private void updateButtons(){
        String m1=prefs.getString(PREF_L1,null), m2=prefs.getString(PREF_L2,null);
        String n1=prefs.getString(PREF_L1_NAME,"YN360"), n2=prefs.getString(PREF_L2_NAME,"YN360");
        ((Button)findViewById(R.id.connect1)).setText(light1.isReady()?"Lampa 1 ✓  "+n1:(m1!=null?"Lampa 1 • "+n1+" • "+shortMac(m1):"Lampa 1"));
        ((Button)findViewById(R.id.connect2)).setText(light2.isReady()?"Lampa 2 ✓  "+n2:(m2!=null?"Lampa 2 • "+n2+" • "+shortMac(m2):"Lampa 2"));
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
        try { if(m2!=null) light2.connect(adapter.getRemoteDevice(m2)); } catch(Exception ignored){}
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
        if(!light.isReady()){ Toast.makeText(this,label+" nie jest połączona",Toast.LENGTH_SHORT).show(); return; }
        stopFx(false);
        final int token=++fxToken;
        final int[] step={0};
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            if(step[0]>=6){ light.off(); status.setText(label+" zidentyfikowana"); currentFx=null; return; }
            if(step[0]%2==0) light.white(99,99); else light.off();
            step[0]++;
            currentFx=this;
            fx.postDelayed(this,190);
        }};
        currentFx=r;
        status.setText(label+" miga 3× — sprawdź która to fizycznie lampa");
        fx.post(r);
    }


    // Live controls: throttle BLE writes to avoid flooding GATT queues.
    private void queueLiveRgb(int mask,int r,int g,int b) {
        if(palettePending!=null) ui.removeCallbacks(palettePending);
        final int rr=r,gg=g,bb=b,mm=mask;
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
                    else applyWhiteToMask(mask);
                } else if(s==whiteTemp){
                    manualMode=0;
                    applyWhiteToMask(mask);
                }
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        };
        brightness.setOnSeekBarChangeListener(listener);
        whiteTemp.setOnSeekBarChangeListener(listener);
        red.setOnSeekBarChangeListener(listener);
        green.setOnSeekBarChangeListener(listener);
        blue.setOnSeekBarChangeListener(listener);
    }

    private void setupColorPalette(){
        updatePreview(paletteR,paletteG,paletteB);
        colorPalette.setOnColorChangedListener((r,g,b)->{
            if(manualMode!=1) stopFx(false);
            manualMode=1;
            paletteR=r; paletteG=g; paletteB=b;
            slidersFromPalette=true;
            red.setProgress(r); green.setProgress(g); blue.setProgress(b);
            slidersFromPalette=false;
            updatePreview(r,g,b);
            queueLiveRgb(selectedMask(),r,g,b);
        });
    }

    private void updatePreview(int r,int g,int b){
        colorPreview.setBackgroundColor(android.graphics.Color.rgb(r,g,b));
    }

    private void onTargetChanged(){
        stopFx(false);
        int mask=selectedMask();
        String t=mask==1?"Lampa 1":(mask==2?"Lampa 2":"Obie lampy");
        status.setText("Sterowanie: "+t);
    }

    private interface Action { void run(BleLightController l); }

    private int selectedMask(){
        if(target1.isChecked()) return 1;
        if(target2.isChecked()) return 2;
        return 3;
    }

    private void forMask(int mask, Action a){
        if((mask&1)!=0 && light1.isReady()) a.run(light1);
        if((mask&2)!=0 && light2.isReady()) a.run(light2);
    }

    private void cancelForMask(int mask){
        if((mask&1)!=0) light1.cancelPending();
        if((mask&2)!=0) light2.cancelPending();
    }

    private int scaled(int v){ return Math.max(0,Math.min(255, v*brightness.getProgress()/255)); }
    private void applyRgb(int r,int g,int b){ applyRgbToMask(selectedMask(),r,g,b); }
    private void applyRgbToMask(int mask,int r,int g,int b){ forMask(mask,l->l.rgb(scaled(r),scaled(g),scaled(b))); }
    private void applyWhite(){ applyWhiteToMask(selectedMask()); }
    private void applyWhiteToMask(int mask){
        int t=whiteTemp.getProgress();
        int b99=brightness.getProgress()*99/255;
        int cool=b99*t/255;
        int warm=b99*(255-t)/255;
        forMask(mask,l->l.white(cool,warm));
    }

    private long speed(long slowMs, long fastMs){ int p=fxSpeed.getProgress(); return slowMs - ((slowMs-fastMs)*p/100L); }

    private void stopFx(boolean announce){
        fxToken++;
        if(currentFx!=null) fx.removeCallbacks(currentFx);
        currentFx=null;
        if(palettePending!=null){ ui.removeCallbacks(palettePending); palettePending=null; }
        // Do not wipe unselected lamp queues when switching modes.
        cancelForMask(selectedMask());
        if(announce) status.setText("Efekt zatrzymany");
    }

    private void schedule(Runnable r,long ms,int token){
        if(token!=fxToken) return;
        currentFx=r;
        fx.postDelayed(r,Math.max(70,ms));
    }

    private void startPolice(){
        stopFx(false); final int token=++fxToken; final int mask=selectedMask(); final boolean[] phase={true};
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            if(mask==3){
                if(phase[0]){ if(light1.isReady()) light1.rgb(scaled(255),0,0); if(light2.isReady()) light2.rgb(0,0,scaled(255)); }
                else { if(light1.isReady()) light1.rgb(0,0,scaled(255)); if(light2.isReady()) light2.rgb(scaled(255),0,0); }
            } else {
                if(phase[0]) applyRgbToMask(mask,255,0,0); else applyRgbToMask(mask,0,0,255);
            }
            phase[0]=!phase[0]; schedule(this,speed(460,110),token);
        }}; currentFx=r; fx.post(r); status.setText("POLICE • "+targetLabel(mask));
    }

    private void startDisco(){
        stopFx(false); final int token=++fxToken; final int mask=selectedMask();
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            applyRgbToMask(mask,random.nextInt(256),random.nextInt(256),random.nextInt(256));
            schedule(this,speed(760,120),token);
        }}; currentFx=r; fx.post(r); status.setText("DISCO • "+targetLabel(mask));
    }

    private void startStrobe(){
        stopFx(false); final int token=++fxToken; final int mask=selectedMask(); final boolean[] on={false};
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            on[0]=!on[0];
            if(on[0]) forMask(mask,l->{int v=brightness.getProgress()*99/255; l.white(v,v);}); else forMask(mask,BleLightController::off);
            schedule(this,speed(340,85),token);
        }}; currentFx=r; fx.post(r); status.setText("STROBE • "+targetLabel(mask)+" ⚠");
    }

    private void startFlash(){
        stopFx(false); final int token=++fxToken; final int mask=selectedMask(); final int[] step={0};
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            int s=step[0]++%8;
            if(s==0||s==2||s==4) forMask(mask,l->{int v=brightness.getProgress()*99/255; l.white(v,v);}); else forMask(mask,BleLightController::off);
            schedule(this,(s<5?speed(210,90):speed(950,280)),token);
        }}; currentFx=r; fx.post(r); status.setText("FLASH • "+targetLabel(mask));
    }

    private void startFade(){
        stopFx(false); final int token=++fxToken; final int mask=selectedMask(); fadeHue=0;
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            float[] hsv={fadeHue%360,1f,1f};
            int c=android.graphics.Color.HSVToColor(hsv);
            applyRgbToMask(mask,android.graphics.Color.red(c),android.graphics.Color.green(c),android.graphics.Color.blue(c));
            fadeHue+=5;
            schedule(this,speed(190,85),token);
        }}; currentFx=r; fx.post(r); status.setText("COLOR FADE • "+targetLabel(mask));
    }

    private void startLightning(){
        stopFx(false); final int token=++fxToken; final int mask=selectedMask(); final int[] burst={0};
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            if(burst[0]<=0){
                forMask(mask,BleLightController::off);
                burst[0]=2+random.nextInt(5);
                schedule(this,550+random.nextInt(1700),token);
            } else {
                boolean on=(burst[0]%2==0);
                if(on) forMask(mask,l->{int v=brightness.getProgress()*99/255; l.white(v,v);}); else forMask(mask,BleLightController::off);
                burst[0]--;
                schedule(this,80+random.nextInt(100),token);
            }
        }}; currentFx=r; fx.post(r); status.setText("LIGHTNING • "+targetLabel(mask));
    }

    private void startCandle(){
        stopFx(false); final int token=++fxToken; final int mask=selectedMask();
        Runnable r=new Runnable(){ public void run(){
            if(token!=fxToken) return;
            // Prawdziwie ognisty preset: pomarańcz/czerwień z lekkim, nieregularnym migotaniem.
            int rVal=235+random.nextInt(21);
            int gVal=35+random.nextInt(65);
            int bVal=random.nextInt(18);
            if(random.nextInt(7)==0) gVal=Math.max(35,gVal-30);
            applyRgbToMask(mask,rVal,gVal,bVal);
            schedule(this,speed(290,105)+random.nextInt(70),token);
        }}; currentFx=r; fx.post(r); status.setText("FIRE / CANDLE • "+targetLabel(mask));
    }

    private String targetLabel(int mask){ return mask==1?"Lampa 1":(mask==2?"Lampa 2":"Obie"); }

    private void openUpdates(){
        try {
            Intent intent=new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/grbk420/YN360CONTROL/releases"));
            startActivity(intent);
        } catch(Exception e){
            Toast.makeText(this,"Nie mogę otworzyć strony aktualizacji",Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy(){ super.onDestroy(); stopFx(false); light1.disconnect(); light2.disconnect(); }
}
