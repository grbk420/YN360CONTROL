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
        OCEAN, SUNSET, PULSE, TV, NEON, AURORA, BREATHING, SLEEP, DREAM, MOONLIGHT, LAVA, FOREST, DUO_WAVE, STROBE_SNAP, CYBERPUNK, SYNTHWAVE, RAINBOW_FLOW, PLASMA, MATRIX_CODE, NEON_TUNNEL, ARCADE, BOSS_AURA, GALAXY, AQUA_RIPPLE, ICE_CAVE, SPACE_DRIFT, LOFI_NIGHT, COZY_GLOW, DEEP_FOCUS, STARFIELD, DUO_CHASE, CHROMA_SPLIT, IDENTIFY
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
                lamps[i].rgb(dim(r),dim(g),dim(b));
        }

        private void emitHue(int mask,float hue,float sat,float value){
            int col=Color.HSVToColor(new float[]{(hue+360)%360,sat,value});
            rgb(mask,Color.red(col),Color.green(col),Color.blue(col));
        }

        /** All new scenes use monotonic uptime: smooth time-based colour changes even if BLE jitters. */
        private void renderSmoothScene(){
            double seconds=(SystemClock.uptimeMillis()-startedAt)/1000d;
            double t=seconds*(.52d+.0125d*speed);
            for(int i=0;i<2;i++){
                int bit=1<<i;
                if((mask&bit)==0 || !lamps[i].isReady())continue;
                double p=t+i*1.35d;
                double a=wave(p), b=wave(p*.69d+2.1d), c=wave(p*.41d+4.1d);
                int rr=0,gg=0,bb=0;
                switch(kind){
                    case CYBERPUNK:
                        rr=byteValue(67+166*a);gg=byteValue(27+119*(1-a));bb=byteValue(170+68*c);
                        break;
                    case SYNTHWAVE:
                        rr=byteValue(151+100*b);gg=byteValue(21+96*a);bb=byteValue(91+133*(1-a));
                        break;
                    case RAINBOW_FLOW:
                        emitHue(bit,(float)((t*27d+i*64d)%360d),.92f,.95f);
                        continue;
                    case PLASMA:
                        rr=byteValue(39+203*wave(p*1.11));
                        gg=byteValue(26+178*wave(p*.83+1.8));
                        bb=byteValue(59+194*wave(p*.65+3.2));
                        break;
                    case MATRIX_CODE:
                        rr=byteValue(3+13*b);gg=byteValue(47+157*wave(p*.97+i*1.1));
                        bb=byteValue(6+24*c);
                        break;
                    case NEON_TUNNEL:
                        rr=byteValue(28+197*a);gg=byteValue(28+174*(1-a));
                        bb=byteValue(186+69*b);
                        break;
                    case ARCADE:
                        rr=byteValue(60+172*wave(p+.5));
                        gg=byteValue(40+182*wave(p+2.6));
                        bb=byteValue(78+171*wave(p+4.5));
                        break;
                    case BOSS_AURA:
                        rr=byteValue(128+114*wave(p*.9));gg=byteValue(13+79*b);
                        bb=byteValue(6+29*c);
                        break;
                    case GALAXY:
                        rr=byteValue(27+92*b);gg=byteValue(20+64*c);
                        bb=byteValue(97+137*a);
                        break;
                    case AQUA_RIPPLE:
                        rr=byteValue(5+17*b);gg=byteValue(81+139*a);
                        bb=byteValue(134+118*wave(p*.86+2.2));
                        break;
                    case ICE_CAVE:
                        rr=byteValue(24+114*c);gg=byteValue(103+133*a);
                        bb=byteValue(153+102*b);
                        break;
                    case SPACE_DRIFT:
                        rr=byteValue(24+106*a);gg=byteValue(20+97*b);
                        bb=byteValue(69+160*c);
                        break;
                    case LOFI_NIGHT:
                        rr=byteValue(83+89*wave(p*.49));gg=byteValue(34+65*wave(p*.5+1.1));
                        bb=byteValue(28+62*wave(p*.47+2.8));
                        break;
                    case COZY_GLOW:
                        rr=byteValue(83+52*b);gg=byteValue(25+36*a);
                        bb=byteValue(3+13*c);
                        break;
                    case DEEP_FOCUS:
                        rr=byteValue(5+17*c);gg=byteValue(35+38*a);
                        bb=byteValue(54+46*b);
                        break;
                    case STARFIELD:
                        rr=byteValue(12+22*wave(p*.45));gg=byteValue(12+34*wave(p*.38+1.7));
                        bb=byteValue(39+77*wave(p*.33+2.3));
                        break;
                    case DUO_CHASE:
                        emitHue(bit,(float)((t*25d+i*180d)%360d),.82f,.84f);
                        continue;
                    case CHROMA_SPLIT:
                        rr=byteValue(i==0?44+168*a:36+114*c);
                        gg=byteValue(i==0?24+123*b:87+137*a);
                        bb=byteValue(i==0?133+120*c:68+140*b);
                        break;
                    default:break;
                }
                rgb(bit,rr,gg,bb);
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
                case CHROMA_SPLIT: {
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
