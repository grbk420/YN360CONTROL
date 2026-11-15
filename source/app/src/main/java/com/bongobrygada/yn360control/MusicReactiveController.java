package com.bongobrygada.yn360control;

import android.graphics.Color;
import android.os.SystemClock;

/** Real-time audio-reactive RGB. Analyses short PCM frames IN MEMORY only.
 * No recording is stored, uploaded, transmitted or exposed to other apps.
 * Each lamp must have been explicitly enabled using the Wlacz button.
 */
public final class MusicReactiveController {
    public static final int BASS_REACTOR=0, CLUB_DUO=1, SPECTRUM=2,
            AMBIENT_AUDIO=3, DROP_HUNTER=4,
            KICK_SWAP=5, HIHAT_SPARK=6, BASS_RIPPLE=7, NEON_STEPS=8,
            VU_METER=9, COLOR_JUMP=10, STROBE_SYNC=11, ENERGY_WAVE=12;
    public static final String[] PALETTE_NAMES={
        "Classic • oryginał","Cyber Neon • cyjan/róż","Fire • pomarańcz/czerwień",
        "Ocean • błękit/turkus","Toxic • zieleń/żółty",
        "Rave Rainbow • wielobarwny","Ultra Violet • fiolet/niebieski",
        "Sunset • koral/róż"
    };
    private static final float[][] PALETTE_HUES={
        {16f,210f,300f,55f},
        {315f,180f,260f,350f},
        {17f,42f,2f,60f},
        {198f,174f,228f,152f},
        {108f,65f,141f,85f},
        {20f,180f,280f,328f},
        {267f,223f,310f,198f},
        {345f,18f,44f,305f}
    };
    private final BleLightController[] lamps;
    private volatile int activeMask=0;
    private final int[] mode={BASS_REACTOR,BASS_REACTOR};
    private volatile int sensitivity=65,brightness=185,palette=0;
    private double low=0,mid=0,prev=0,avgBass=.016,avgHigh=.015,avgRms=.018;
    private long lastKick=0,lastHat=0,lastFrame=0,lastDrop=0;
    private int kickCounter=0;
    private float ambientHue=196f;
    private volatile int activity=0;

    public MusicReactiveController(BleLightController lamp1,BleLightController lamp2){
        lamps=new BleLightController[]{lamp1,lamp2};
    }
    public synchronized void start(int mask,int newMode,int newSensitivity,int newBrightness){
        for(int i=0;i<2;i++)if((mask&(1<<i))!=0){
            if(lamps[i].isReady()&&lamps[i].isUserEnabled()){
                activeMask|=(1<<i);
                mode[i]=Math.max(0,Math.min(12,newMode));
            }
        }
        sensitivity=Math.max(0,Math.min(100,newSensitivity));
        brightness=Math.max(20,Math.min(255,newBrightness));
    }
    public synchronized void stop(int mask){activeMask&=~mask;}
    public synchronized boolean isActive(){return activeMask!=0;}
    public int getMask(){return activeMask;}
    public int getActivity(){return activity;}
    public void setSensitivity(int value){sensitivity=Math.max(0,Math.min(100,value));}
    /** Live palette selection does not restart the microphone service. */
    public void setPalette(int selected){palette=Math.max(0,Math.min(PALETTE_HUES.length-1,selected));}
    public int getPalette(){return palette;}
    public synchronized int getMode(int lampIndex){
        return lampIndex>=0&&lampIndex<2&&(activeMask&(1<<lampIndex))!=0?mode[lampIndex]:-1;
    }

    /** PCM 16-bit mono 16 kHz: estimate kick energy and high-frequency accents. */
    public void process(short[] pcm,int n){
        if(n<=0||activeMask==0)return;
        double bassSum=0,highSum=0,rmsSum=0;
        for(int j=0;j<n;j++){
            double x=pcm[j]/32768d;
            // Simple IIR frequency bands (~ bass < 120Hz; treble > 300Hz).
            low+=.04d*(x-low);
            mid+=.16d*(x-mid);
            double bass=low,high=x-mid;
            bassSum+=bass*bass;
            highSum+=high*high;
            rmsSum+=x*x;
            prev=x;
        }
        double bass=Math.sqrt(bassSum/n),high=Math.sqrt(highSum/n),
                rms=Math.sqrt(rmsSum/n);
        long now=SystemClock.uptimeMillis();
        double formerBass=avgBass,formerHigh=avgHigh,formerRms=avgRms;
        avgBass=.965d*avgBass+.035d*bass;
        avgHigh=.96d*avgHigh+.04d*high;
        avgRms=.964d*avgRms+.036d*rms;

        double threshold=1.95d-sensitivity*.009d;
        boolean kick=bass>.0065d && bass>Math.max(.004,formerBass)*threshold
            && now-lastKick>235;
        boolean hat=high>.007d && high>Math.max(.006,formerHigh)*(threshold+.25d)
            && now-lastHat>145;
        if(kick){lastKick=now;kickCounter++;}
        if(hat)lastHat=now;
        double energy=Math.max(0,Math.min(1.0d,
            (rms-Math.min(.13,formerRms*.70d))*
            (sensitivity*.06d+4.7d)));
        activity=(int)Math.min(100,energy*100);
        if(now-lastFrame<145)return; // BLE: at most about 7 new RGB frames/s per lamp
        lastFrame=now;
        // Hold a detected onset briefly: the BLE frame gate must not miss a kick.
        boolean kickActive=now-lastKick<170;
        boolean hatActive=now-lastHat<170;

        for(int i=0;i<2;i++){
            int bit=1<<i;
            if((activeMask&bit)==0)continue;
            if(!lamps[i].isReady() || !lamps[i].isUserEnabled()){
                stop(bit);continue;
            }
            int m=mode[i];
            float hue=196f, sat=.95f;
            double level=.10+.48*energy;
            if(m==BASS_REACTOR){
                hue=kickActive?16f:210f;
                level=kickActive?.98:.09+.29*energy;
            }else if(m==CLUB_DUO){
                hue=i==0?355f:188f;
                boolean accent=(i==0?kickActive:hatActive);
                level=accent?.98:.07+.22*energy;
            }else if(m==SPECTRUM){
                // Highs and bass deliberately control different colour families.
                double ratio=bass/(bass+high+.0001d);
                hue=(float)(175d+165d*ratio+(i==1?70d:0d));
                level=.16+.72*energy;
            }else if(m==AMBIENT_AUDIO){
                ambientHue=(ambientHue+.35f+.60f*(float)energy)%360f;
                hue=(ambientHue+(i==1?115f:0f))%360f;
                level=.14+.45*energy;
            }else if(m==DROP_HUNTER){
                boolean surge=kickActive && bass>formerBass*1.85d;
                if(surge && now-lastDrop>800)lastDrop=now;
                boolean drop=now-lastDrop<220;
                hue=drop?48f:(i==0?284f:195f);
                level=drop?.98:.12+.36*energy;
            }else if(m==KICK_SWAP){
                // Every bass onset advances colour; one lamp alternates with the other.
                hue=(kickCounter+i)%2==0?184f:318f;
                level=kickActive?.98:.04;
            }else if(m==HIHAT_SPARK){
                hue=hatActive?45f:262f;
                level=hatActive?.98:.06+.20*energy;
                sat=hatActive?.10f:.98f;
            }else if(m==BASS_RIPPLE){
                double decay=Math.max(0,1-(now-lastKick)/580d);
                hue=(float)((185+kickCounter*48+i*120)%360);
                level=.06+.91*decay*decay;
            }else if(m==NEON_STEPS){
                hue=(float)((kickCounter*67+i*140)%360);
                level=kickActive?.95:.13+.24*energy;
            }else if(m==VU_METER){
                hue=(float)(220d-185d*energy+(i==1?45d:0d));
                level=.06+.90*energy;
            }else if(m==COLOR_JUMP){
                hue=(float)((kickCounter*91+i*160)%360);
                level=kickActive?.99:.08+.16*energy;
            }else if(m==STROBE_SYNC){
                hue=kickCounter%2==0?35f:294f;
                level=kickActive?.99:.012;
                sat=kickActive?.17f:.98f;
            }else if(m==ENERGY_WAVE){
                hue=(float)((ambientHue+kickCounter*7+i*110)%360);
                level=.12+.70*Math.sqrt(energy);
            }
            // Palette governs hues across *all* microphone modes, but the mode
            // remains responsible for deciding WHEN and HOW the lamp reacts.
            int selected=palette;
            if(selected>0){
                float[] hues=PALETTE_HUES[selected];
                int index=(kickCounter+(m==SPECTRUM?2:0)+(m==DROP_HUNTER?1:0))%4;
                float primary=hues[(index+i)%4];
                float alternate=hues[(index+1+i)%4];
                if(m==BASS_REACTOR||m==KICK_SWAP||m==BASS_RIPPLE||
                   m==STROBE_SYNC||m==COLOR_JUMP)
                    hue=kickActive?primary:alternate;
                else if(m==CLUB_DUO)
                    hue=i==0?primary:alternate;
                else if(m==SPECTRUM||m==VU_METER)
                    hue=(primary+(float)(bass/(bass+high+.0001d))*68f)%360f;
                else if(m==AMBIENT_AUDIO||m==ENERGY_WAVE)
                    hue=(primary+(ambientHue%28f))%360f;
                else hue=primary;
                if(m!=HIHAT_SPARK&&m!=STROBE_SYNC)sat=.97f;
            }
            level=Math.max(.015,Math.min(1d,level));
            int c=Color.HSVToColor(new float[]{(hue+360)%360,sat,
                Math.min(1f,(float)(level*brightness/255d))});
            lamps[i].sceneRgb(Color.red(c),Color.green(c),Color.blue(c));
        }
    }
}
