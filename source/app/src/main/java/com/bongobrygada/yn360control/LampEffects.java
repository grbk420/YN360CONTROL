package com.bongobrygada.yn360control;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Random;

/**
 * Software lighting scenes using only the verified RGB/write protocol.
 * One session can control one or both lamps; changing one lamp detaches it
 * without stopping the other lamp's effect.
 */
public final class LampEffects {
    public enum Kind {
        POLICE, DISCO, STROBE, STROBE_TURBO, FLASH, FADE, LIGHTNING, CANDLE,
        OCEAN, SUNSET, PULSE, TV, NEON, AURORA, BREATHING, SLEEP, DREAM, MOONLIGHT, LAVA, FOREST, DUO_WAVE, STROBE_SNAP, CYBERPUNK, SYNTHWAVE, RAINBOW_FLOW, PLASMA, MATRIX_CODE, NEON_TUNNEL, ARCADE, BOSS_AURA, GALAXY, AQUA_RIPPLE, ICE_CAVE, SPACE_DRIFT, LOFI_NIGHT, COZY_GLOW, DEEP_FOCUS, STARFIELD, DUO_CHASE, CHROMA_SPLIT, NEON_RAVE, LASER_SWAP, TOXIC_GLOW, FIRE_ICE, PIXEL_PARTY, ELECTRIC_STORM, IDENTIFY
    }

    private final BleLightController[] lamps;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Session[] owner = new Session[2];

    public LampEffects(BleLightController one, BleLightController two) {
        lamps = new BleLightController[]{one, two};
    }

    public boolean hasActive(int mask) {
        for (int i=0;i<2;i++) if ((mask & (1<<i)) != 0 && owner[i] != null) return true;
        return false;
    }

    /** Clears only selected devices; any other light continues its current scene. */
    public void stop(int mask) {
        HashSet<Session> touched = new HashSet<>();
        for (int i=0;i<2;i++) {
            int bit = 1<<i;
            if ((mask & bit) == 0) continue;
            Session s = owner[i];
            if (s != null) {
                s.mask &= ~bit;
                owner[i] = null;
                touched.add(s);
            }
            lamps[i].cancelPending();
            lamps[i].setTurboStrobe(false);
        }
        for (Session s: touched) if (s.mask == 0) handler.removeCallbacks(s);
    }

    public int start(Kind kind, int requestedMask, int brightness, int speed,
                     int colorR, int colorG, int colorB) {
        int connectedMask = 0;
        for (int i=0;i<2;i++) if ((requestedMask & (1<<i)) != 0 && lamps[i].isReady()) connectedMask |= 1<<i;
        if (connectedMask == 0) return 0;
        stop(connectedMask);
        Session s = new Session(kind,connectedMask,brightness,speed,colorR,colorG,colorB);
        for (int i=0;i<2;i++) if ((connectedMask & (1<<i)) != 0) {
            owner[i]=s;
            lamps[i].setTurboStrobe(kind==Kind.STROBE_TURBO || kind==Kind.STROBE_SNAP);
        }
        handler.post(s);
        return connectedMask;
    }

    public void changeSpeed(int value) {
        HashSet<Session> unique = new HashSet<>();
        for (Session s: owner) if (s!=null) unique.add(s);
        for (Session s: unique) s.speed = clamp(value,0,100);
    }

    public void close() { stop(3); handler.removeCallbacksAndMessages(null); }

    private static int clamp(int v,int min,int max) { return Math.max(min, Math.min(max,v)); }
    private static long pace(int setting,int slow,int fast) {
        return slow - ((long)(slow-fast)*setting)/100L;
    }

    private static double wave(double x){return .5d+.5d*Math.sin(x);}
    private static int byteValue(double x){return clamp((int)Math.round(x),0,255);}
    private static final int[] PALETTE_CYBERPUNK={0xFF00DC,0x00F0FF,0x5400FF,0xFF0059};
    private static final int[] PALETTE_SYNTHWAVE={0xFF5B00,0xFF007F,0x5C00FF,0x00B5FF,0xFFB000};
    private static final int[] PALETTE_RAINBOW_FLOW={0xFF1200,0xFF9800,0xF5F500,0x08FA50,0x00BCFF,0x3D00FF,0xFF00A8};
    private static final int[] PALETTE_PLASMA={0x00FFB8,0x3D00FF,0xFF00BA,0x0A7BFF,0xF300FF};
    private static final int[] PALETTE_MATRIX_CODE={0x05A800,0x02FF3B,0x01801A,0x4AFF00,0x04E543};
    private static final int[] PALETTE_NEON_TUNNEL={0x00EEFF,0xFF00CC,0x3B00FF,0x00FFAA};
    private static final int[] PALETTE_ARCADE={0xFF0027,0xFFD300,0x27FF00,0x00D7FF,0x7B00FF,0xFF0098};
    private static final int[] PALETTE_BOSS_AURA={0xFF1A00,0xFF6000,0xFFBF00,0xC40006};
    private static final int[] PALETTE_GALAXY={0x3200B7,0x6500FF,0x0068FF,0xA300FA,0x0024E7};
    private static final int[] PALETTE_AQUA_RIPPLE={0x00FFD5,0x00A6FF,0x003CFF,0x00ED91};
    private static final int[] PALETTE_ICE_CAVE={0x00BBFF,0x1661FF,0x00F3FF,0x3252D9};
    private static final int[] PALETTE_SPACE_DRIFT={0x4900E4,0x006FFF,0x00CCB9,0x7800F5,0x001EA8};
    private static final int[] PALETTE_LOFI_NIGHT={0xE75B00,0xDE2B79,0x7421CE,0xFF9500};
    private static final int[] PALETTE_COZY_GLOW={0xFF7900,0xFFB300,0xFF3E00,0xE67C00};
    private static final int[] PALETTE_DEEP_FOCUS={0x0040D9,0x008EBD,0x00A886,0x00389A};
    private static final int[] PALETTE_STARFIELD={0x2500E5,0x6000AD,0x0052EE,0x8B00E0};
    private static final int[] PALETTE_DUO_CHASE={0xFF00A8,0x00EFFF,0xFFD400,0x3300FF};
    private static final int[] PALETTE_CHROMA_SPLIT={0xFF0054,0x00D9FF,0xFFAE00,0x15F500};
    private static final int[] PALETTE_NEON_RAVE={0x00EAFF,0xFF00F6,0x0EFF00,0xFF9A00,0x5400FF};
    private static final int[] PALETTE_LASER_SWAP={0xFF0047,0x00F4F8,0xFF5400,0x09FF31};
    private static final int[] PALETTE_TOXIC_GLOW={0x65FF00,0x00B31D,0xF0E900,0x13FA32,0x4C00C8};
    private static final int[] PALETTE_FIRE_ICE={0xFF3300,0xFF9B00,0x008BFF,0x00E9FA};
    private static final int[] PALETTE_PIXEL_PARTY={0xFF005D,0x00ECFF,0xF3FF00,0x04F83E,0x8D00FF};
    private static final int[] PALETTE_ELECTRIC_STORM={0x1000FF,0x0075FF,0x00B9FF,0x5F00F0,0x00D5D9};
    private static int[] sceneColors(Kind kind){
        switch(kind){
            case CYBERPUNK:return PALETTE_CYBERPUNK;
            case SYNTHWAVE:return PALETTE_SYNTHWAVE;
            case RAINBOW_FLOW:return PALETTE_RAINBOW_FLOW;
            case PLASMA:return PALETTE_PLASMA;
            case MATRIX_CODE:return PALETTE_MATRIX_CODE;
            case NEON_TUNNEL:return PALETTE_NEON_TUNNEL;
            case ARCADE:return PALETTE_ARCADE;
            case BOSS_AURA:return PALETTE_BOSS_AURA;
            case GALAXY:return PALETTE_GALAXY;
            case AQUA_RIPPLE:return PALETTE_AQUA_RIPPLE;
            case ICE_CAVE:return PALETTE_ICE_CAVE;
            case SPACE_DRIFT:return PALETTE_SPACE_DRIFT;
            case LOFI_NIGHT:return PALETTE_LOFI_NIGHT;
            case COZY_GLOW:return PALETTE_COZY_GLOW;
            case DEEP_FOCUS:return PALETTE_DEEP_FOCUS;
            case STARFIELD:return PALETTE_STARFIELD;
            case DUO_CHASE:return PALETTE_DUO_CHASE;
            case CHROMA_SPLIT:return PALETTE_CHROMA_SPLIT;
            case NEON_RAVE:return PALETTE_NEON_RAVE;
            case LASER_SWAP:return PALETTE_LASER_SWAP;
            case TOXIC_GLOW:return PALETTE_TOXIC_GLOW;
            case FIRE_ICE:return PALETTE_FIRE_ICE;
            case PIXEL_PARTY:return PALETTE_PIXEL_PARTY;
            case ELECTRIC_STORM:return PALETTE_ELECTRIC_STORM;
            default:return PALETTE_CYBERPUNK;
        }
    }

    private final class Session implements Runnable {
        final Kind kind;
        volatile int mask;
        final int brightness;
        volatile int speed;
        final int userR,userG,userB;
        int step=0;
        int burst=0;
        float hue=0f;
        final long startedAt=SystemClock.uptimeMillis();

        Session(Kind k,int m,int b,int s,int r,int g,int bl) {
            kind=k; mask=m; brightness=clamp(b,0,255);
            speed=clamp(s,0,100);
            userR=clamp(r,0,255); userG=clamp(g,0,255); userB=clamp(bl,0,255);
        }

        private int dim(int v) { return clamp((v * brightness + 127)/255,0,255); }

        private void rgb(int m,int r,int g,int b) {
            for(int i=0;i<2;i++) if ((m & (1<<i)) != 0 && lamps[i].isReady())
                lamps[i].sceneRgb(dim(r),dim(g),dim(b));
        }

        private void emitHue(int mask,float hue,float sat,float value){
            int col=Color.HSVToColor(new float[]{(hue+360)%360,sat,value});
            rgb(mask,Color.red(col),Color.green(col),Color.blue(col));
        }

        /** Saturated HSV keyframe interpolation avoids the washed-out white midpoint
         * produced when additive RGB channels are interpolated independently. */
        private void renderSmoothScene(){
            final double elapsed=(SystemClock.uptimeMillis()-startedAt)/1000d;
            final int[] colors=sceneColors(kind);
            final boolean calm=kind==Kind.COZY_GLOW||kind==Kind.DEEP_FOCUS||
                kind==Kind.LOFI_NIGHT||kind==Kind.STARFIELD||
                kind==Kind.GALAXY||kind==Kind.SPACE_DRIFT;
            final boolean gaming=kind==Kind.ARCADE||kind==Kind.PIXEL_PARTY||
                kind==Kind.NEON_RAVE||kind==Kind.LASER_SWAP||kind==Kind.DUO_CHASE;
            final double perSecond=(calm?.085d:gaming?.19d:.135d)+
                speed*(calm?.0011d:.0021d);
            for(int i=0;i<2;i++){
                int bit=1<<i;
                if((mask&bit)==0 || !lamps[i].isReady())continue;
                final double offset=i==0?0d:kind==Kind.FIRE_ICE?2d:
                    (kind==Kind.DUO_CHASE||kind==Kind.CHROMA_SPLIT||
                     kind==Kind.LASER_SWAP?1.6d:.78d);
                if(kind==Kind.RAINBOW_FLOW){
                    emitHue(bit,(float)((elapsed*(14d+speed*.25d)+offset*70d)%360d),.99f,.93f);
                    continue;
                }
                double location=elapsed*perSecond+offset;
                int whole=(int)Math.floor(location);
                double fraction=location-whole;
                int from=Math.floorMod(whole,colors.length);
                int to=(from+1)%colors.length;
                double eased=.5d-.5d*Math.cos(Math.PI*fraction);
                if(kind==Kind.PIXEL_PARTY){
                    eased=fraction<.52d?0d:
                        .5d-.5d*Math.cos(Math.PI*(fraction-.52d)/.48d);
                }
                float[] first=new float[3],second=new float[3];
                Color.colorToHSV(0xFF000000|colors[from],first);
                Color.colorToHSV(0xFF000000|colors[to],second);
                float delta=(second[0]-first[0]+540f)%360f-180f;
                float hue=(first[0]+(float)(delta*eased)+360f)%360f;
                float sat=Math.max(calm?.90f:.96f,
                    (float)(first[1]*(1d-eased)+second[1]*eased));
                float value=calm?.49f:.90f;
                if(kind==Kind.MATRIX_CODE)
                    value=.57f+(float)(.27d*(.5d+.5d*Math.sin(elapsed*1.8+i)));
                if(kind==Kind.COZY_GLOW)
                    value=.40f+(float)(.12d*(.5d+.5d*Math.sin(elapsed*.7)));
                if(kind==Kind.PIXEL_PARTY)value=.96f;
                int color=Color.HSVToColor(new float[]{hue,Math.min(1f,sat),Math.min(1f,value)});
                rgb(bit,Color.red(color),Color.green(color),Color.blue(color));
            }
        }

        @Override public void run() {
            if(mask == 0) return;
            long delay;
            switch(kind) {
                case IDENTIFY: {
                    if (step >= 6) {
                        for(int i=0;i<2;i++) if(owner[i]==this) owner[i]=null;
                        return;
                    }
                    if ((step & 1)==0) rgb(mask,255,255,255);
                    else rgb(mask,0,0,0);
                    step++;
                    delay=320;
                    break;
                }
                case STROBE_TURBO: {
                    // Target: ~6 Hz at max with short illuminated phase.
                    // Values are scheduling requests; real BLE/firmware timing may be slower.
                    boolean lit=(step++ & 1)==0;
                    rgb(mask,lit?255:0,lit?255:0,lit?255:0);
                    delay=lit?60:pace(speed,400,105);
                    break;
                }
                case STROBE_SNAP: {
                    // Lower frequency, longer dark interval; clearer frame separation.
                    boolean lit=(step++ & 1)==0;
                    rgb(mask,lit?255:0,lit?255:0,lit?255:0);
                    delay=lit?60:pace(speed,510,175);
                    break;
                }
                case STROBE: {
                    // Do not switch between RGB/white/off modes every 100ms.
                    // RGB lit/black frames work over the same established command.
                    boolean lit=(step++ % 2)==0;
                    rgb(mask,lit?255:0,lit?255:0,lit?255:0);
                    delay=pace(speed,540,150); // About 3.3 complete flashes/second max over current BLE queue
                    break;
                }
                case FLASH: {
                    int position=step++ % 8;
                    boolean lit=(position==0 || position==2 || position==4);
                    rgb(mask,lit?255:0,lit?235:0,lit?210:0);
                    delay=(position==5 || position==6)
                        ?pace(speed,1250,580):pace(speed,320,185);
                    break;
                }
                case POLICE: {
                    boolean swap=(step++ % 2)==1;
                    if ((mask & 3)==3) {
                        rgb(1,swap?0:255,0,swap?255:0);
                        rgb(2,swap?255:0,0,swap?0:255);
                    } else {
                        rgb(mask,swap?0:255,0,swap?255:0);
                    }
                    delay=pace(speed,660,240);
                    break;
                }
                case DISCO: {
                    hue=random.nextInt(360);
                    int c=Color.HSVToColor(new float[]{hue,.95f,1f});
                    rgb(mask,Color.red(c),Color.green(c),Color.blue(c));
                    step++;
                    delay=pace(speed,960,260);
                    break;
                }
                case FADE: {
                    hue=(hue+2f)%360; // smaller increments for smoother transitions
                    int c=Color.HSVToColor(new float[]{hue,.95f,1f});
                    rgb(mask,Color.red(c),Color.green(c),Color.blue(c));
                    delay=pace(speed,270,150);
                    break;
                }
                case LIGHTNING: {
                    if(burst<=0) {
                        rgb(mask,0,0,0);
                        burst=4+random.nextInt(4);
                        delay=850+random.nextInt(1700);
                    } else {
                        boolean bright=(burst%2)==0;
                        rgb(mask,bright?245:0,bright?248:0,bright?255:0);
                        burst--;
                        delay=180+random.nextInt(100);
                    }
                    break;
                }
                case CANDLE: {
                    // Soft amber flicker, deliberately avoiding white LED-like flashes.
                    float glow=(float)((Math.sin(step++*.57)+1d)*.5d);
                    float level=.64f+.25f*glow+.11f*random.nextFloat();
                    if(random.nextInt(18)==0) level*=.76f;
                    int rr=clamp(Math.round(255*level),0,255);
                    int gg=clamp(Math.round((89+random.nextInt(38))*level),0,155);
                    int bb=random.nextInt(5);
                    rgb(mask,rr,gg,bb);
                    delay=pace(speed,480,260)+random.nextInt(55);
                    break;
                }
                case OCEAN: {
                    float wave=(float)((Math.sin(step++ * .11)+1)*.5);
                    rgb(mask,(int)(8+18*wave),(int)(75+115*wave),(int)(175+80*wave));
                    delay=pace(speed,340,180);
                    break;
                }
                case SUNSET: {
                    float wave=(float)((Math.sin(step++ * .075)+1)*.5);
                    rgb(mask,255,(int)(48+95*wave),(int)(2+58*wave));
                    delay=pace(speed,390,210);
                    break;
                }
                case PULSE: {
                    float wave=(float)((Math.sin(step++ * .24)+1)*.5);
                    float strength=.12f+.88f*wave;
                    rgb(mask,(int)(userR*strength),(int)(userG*strength),(int)(userB*strength));
                    delay=pace(speed,300,170);
                    break;
                }
                case TV: {
                    int base=145+random.nextInt(110);
                    rgb(mask,base,Math.min(255,base+6),Math.min(255,base+22));
                    delay=pace(speed,480,190)+random.nextInt(150);
                    break;
                }
                case NEON: {
                    float wave=(float)((Math.sin(step++*.12)+1)*.5);
                    rgb(mask,(int)(40+215*wave),(int)(210-170*wave),255);
                    delay=pace(speed,370,195);
                    break;
                }
                case AURORA: {
                    // Slowly flowing cyan / indigo / violet, no hard colour jumps.
                    double phase=step++*.047;
                    int rr=(int)(50+53*(1+Math.sin(phase))*.5);
                    int gg=(int)(50+110*(1+Math.sin(phase+.9))*.5);
                    int bb=(int)(115+125*(1+Math.sin(phase+2.1))*.5);
                    rgb(mask,rr,gg,bb);
                    delay=pace(speed,360,195);
                    break;
                }
                case BREATHING: {
                    // Warm, extremely gentle breathing light, approximately 12-second cycles.
                    double wave=(Math.sin(step++*.105-Math.PI/2)+1d)*.5d;
                    float strength=(float)(.10+.55*wave);
                    rgb(mask,Math.round(238*strength),Math.round(110*strength),Math.round(42*strength));
                    delay=pace(speed,310,200);
                    break;
                }
                case SLEEP: {
                    // 15-minute fading amber light. Finishes with OFF only for session-owned lamps.
                    long elapsed=SystemClock.uptimeMillis()-startedAt;
                    if(elapsed>=900000L){
                        for(int i=0;i<2;i++)if(owner[i]==this){
                            owner[i]=null;
                            if(lamps[i].isReady())lamps[i].off();
                        }
                        mask=0;return;
                    }
                    float left=Math.max(0f,1f-elapsed/900000f);
                    float level=left*left;
                    rgb(mask,Math.round(96*level),Math.round(28*level),Math.round(7*level));
                    delay=1100;
                    break;
                }
                case DREAM: {
                    double p=step++*.037;
                    rgb(mask,49+(int)(33*(1+Math.sin(p))*.5),
                        24+(int)(24*(1+Math.sin(p+1.6))*.5),
                        86+(int)(51*(1+Math.sin(p+2.7))*.5));
                    delay=pace(speed,430,235);
                    break;
                }
                case MOONLIGHT: {
                    double p=step++*.035;
                    double swell=(1+Math.sin(p))*.5;
                    rgb(mask,4+(int)(3*swell),9+(int)(8*swell),30+(int)(24*swell));
                    delay=pace(speed,530,270);
                    break;
                }
                case LAVA: {
                    double p=step++*.063;
                    double lava=(1+Math.sin(p))*.5;
                    rgb(mask,130+(int)(70*lava),16+(int)(76*lava),3+(int)(7*lava));
                    delay=pace(speed,470,230);
                    break;
                }
                case FOREST: {
                    double p=step++*.044;
                    double greenWave=(1+Math.sin(p))*.5;
                    double cyanWave=(1+Math.sin(p+1.6))*.5;
                    rgb(mask,7+(int)(8*cyanWave),54+(int)(90*greenWave),
                        22+(int)(48*cyanWave));
                    delay=pace(speed,490,260);
                    break;
                }
                case DUO_WAVE: {
                    hue=(hue+1.7f)%360f;
                    int a=Color.HSVToColor(new float[]{hue,.82f,.8f});
                    int b=Color.HSVToColor(new float[]{(hue+180f)%360f,.82f,.8f});
                    if((mask&3)==3){
                        rgb(1,Color.red(a),Color.green(a),Color.blue(a));
                        rgb(2,Color.red(b),Color.green(b),Color.blue(b));
                    }else rgb(mask,Color.red(a),Color.green(a),Color.blue(a));
                    delay=pace(speed,390,220);
                    break;
                }
                case CYBERPUNK:
                case SYNTHWAVE:
                case RAINBOW_FLOW:
                case PLASMA:
                case MATRIX_CODE:
                case NEON_TUNNEL:
                case ARCADE:
                case BOSS_AURA:
                case GALAXY:
                case AQUA_RIPPLE:
                case ICE_CAVE:
                case SPACE_DRIFT:
                case LOFI_NIGHT:
                case COZY_GLOW:
                case DEEP_FOCUS:
                case STARFIELD:
                case DUO_CHASE:
                case CHROMA_SPLIT:
                case NEON_RAVE:
                case LASER_SWAP:
                case TOXIC_GLOW:
                case FIRE_ICE:
                case PIXEL_PARTY:
                case ELECTRIC_STORM: {
                    renderSmoothScene();
                    delay=pace(speed,310,165);
                    break;
                }
                default: return;
            }
            if(mask!=0) handler.postDelayed(this,Math.max(
                    (kind==Kind.STROBE_TURBO || kind==Kind.STROBE_SNAP)?60:150,delay));
        }
    }
}
