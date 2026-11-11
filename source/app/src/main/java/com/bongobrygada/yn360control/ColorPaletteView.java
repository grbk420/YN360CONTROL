package com.bongobrygada.yn360control;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Circular HSV palette. Wheel bitmap is cached; finger movements change colour immediately. */
public final class ColorPaletteView extends View {
    public interface OnColorChangedListener { void onColorChanged(int r,int g,int b); }
    private final Paint image=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap wheel;
    private float hue=0f, saturation=1f;
    private OnColorChangedListener listener;
    private static final int SIZE=360;
    public ColorPaletteView(Context c){super(c);init();}
    public ColorPaletteView(Context c,AttributeSet a){super(c,a);init();}
    public ColorPaletteView(Context c,AttributeSet a,int s){super(c,a,s);init();}
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
    @Override public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN && getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
            if(action==MotionEvent.ACTION_CANCEL)return true;
        }
        if(action!=MotionEvent.ACTION_DOWN && action!=MotionEvent.ACTION_MOVE && action!=MotionEvent.ACTION_UP)return true;
        float dx=e.getX()-getWidth()*.5f,dy=e.getY()-getHeight()*.5f;
        float radius=Math.max(dp(20),Math.min(getWidth(),getHeight())*.5f-dp(15));
        float distance=(float)Math.sqrt(dx*dx+dy*dy);
        saturation=Math.min(1f,distance/radius);
        if(distance>dp(2))hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360d)%360d);
        int color=Color.HSVToColor(new float[]{hue,saturation,1f});
        if(listener!=null)listener.onColorChanged(Color.red(color),Color.green(color),Color.blue(color));
        invalidate();return true;
    }
    @Override protected void onDetachedFromWindow(){
        super.onDetachedFromWindow();
        if(wheel!=null){wheel.recycle();wheel=null;}
    }
}
