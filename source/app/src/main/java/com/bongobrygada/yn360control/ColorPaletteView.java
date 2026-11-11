package com.bongobrygada.yn360control;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Circular HSV palette. Wheel bitmap is cached; finger movements change colour immediately. */
public final class ColorPaletteView extends View {
    public interface OnColorChangedListener { void onColorChanged(int r,int g,int b); }
    private final Paint image=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap wheel;
    private float hue=0f, saturation=1f;
    private OnColorChangedListener listener;
    private float downX, downY;
    private boolean choosingColor=false, scrollingGesture=false;
    private final int touchSlop;
    private static final int SIZE=360;
    public ColorPaletteView(Context c){super(c);touchSlop=ViewConfiguration.get(c).getScaledTouchSlop();init();}
    public ColorPaletteView(Context c,AttributeSet a){super(c,a);touchSlop=ViewConfiguration.get(c).getScaledTouchSlop();init();}
    public ColorPaletteView(Context c,AttributeSet a,int s){super(c,a,s);touchSlop=ViewConfiguration.get(c).getScaledTouchSlop();init();}
    private void init(){setContentDescription("Koło kolorów RGB. Przesuwaj palcem, aby wybrać kolor.");}
    private float dp(float x){return x*getResources().getDisplayMetrics().density;}
    public void setOnColorChangedListener(OnColorChangedListener l){listener=l;}
    public void setColor(int r,int g,int b){
        float[] hsv=new float[3];Color.RGBToHSV(r,g,b,hsv);
        if(hsv[1]>.001f)hue=hsv[0];saturation=hsv[1];invalidate();
    }
    private void ensureWheel(){
        if(wheel!=null)return;
        int[] pix=new int[SIZE*SIZE];float c=(SIZE-1)*.5f;
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
            float dx=(x-c)/c,dy=(y-c)/c;
            float s=(float)Math.sqrt(dx*dx+dy*dy);
            if(s>1f)continue;
            float h=(float)((Math.toDegrees(Math.atan2(dy,dx))+360d)%360d);
            pix[y*SIZE+x]=Color.HSVToColor(new float[]{h,s,1f});
        }
        wheel=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
        wheel.setPixels(pix,0,SIZE,0,0,SIZE,SIZE);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);ensureWheel();
        float cx=getWidth()*.5f,cy=getHeight()*.5f;
        float radius=Math.max(dp(20),Math.min(getWidth(),getHeight())*.5f-dp(15));
        paint.setColor(Color.rgb(80,88,137));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(7));
        canvas.drawCircle(cx,cy,radius+dp(7),paint);
        canvas.drawBitmap(wheel,null,new RectF(cx-radius,cy-radius,cx+radius,cy+radius),image);
        paint.setColor(Color.rgb(214,225,252));paint.setStrokeWidth(dp(1));
        canvas.drawCircle(cx,cy,radius+dp(1),paint);
        double a=Math.toRadians(hue);
        float x=cx+(float)Math.cos(a)*radius*saturation,y=cy+(float)Math.sin(a)*radius*saturation;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.HSVToColor(new float[]{hue,saturation,1f}));
        canvas.drawCircle(x,y,dp(11),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(Color.BLACK);paint.setStrokeWidth(dp(4));
        canvas.drawCircle(x,y,dp(13),paint);
        paint.setColor(Color.WHITE);paint.setStrokeWidth(dp(2.5f));
        canvas.drawCircle(x,y,dp(11),paint);
    }
    private void chooseAt(float x,float y){
        float dx=x-getWidth()*.5f,dy=y-getHeight()*.5f;
        float radius=Math.max(dp(20),Math.min(getWidth(),getHeight())*.5f-dp(15));
        float distance=(float)Math.sqrt(dx*dx+dy*dy);
        saturation=Math.min(1f,distance/radius);
        if(distance>dp(2))hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360d)%360d);
        int color=Color.HSVToColor(new float[]{hue,saturation,1f});
        if(listener!=null)listener.onColorChanged(Color.red(color),Color.green(color),Color.blue(color));
        invalidate();
    }

    /** Tap chooses a colour; a horizontal drag edits; vertical swipes scroll the whole page. */
    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=e.getX();downY=e.getY();
                choosingColor=false;scrollingGesture=false;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                choosingColor=false;scrollingGesture=true;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                return false;
            case MotionEvent.ACTION_MOVE:
                if(scrollingGesture)return false;
                float dx=e.getX()-downX,dy=e.getY()-downY;
                if(!choosingColor){
                    if(Math.abs(dy)>touchSlop && Math.abs(dy)>Math.abs(dx)){
                        scrollingGesture=true;
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                    if(Math.abs(dx)>touchSlop && Math.abs(dx)>Math.abs(dy)*1.2f){
                        choosingColor=true;
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if(choosingColor)chooseAt(e.getX(),e.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if(!scrollingGesture){
                    float distance=(float)Math.hypot(e.getX()-downX,e.getY()-downY);
                    if(choosingColor || distance<=touchSlop)chooseAt(e.getX(),e.getY());
                    performClick();
                }
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                choosingColor=false;scrollingGesture=false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                choosingColor=false;scrollingGesture=false;
                return true;
            default:return true;
        }
    }

    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){
        super.onDetachedFromWindow();
        if(wheel!=null){wheel.recycle();wheel=null;}
    }
}
