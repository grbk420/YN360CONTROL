package com.bongobrygada.yn360control;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ColorPaletteView extends View {
    public interface OnColorChangedListener { void onColorChanged(int r, int g, int b); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue = 0f;
    private float saturation = 1f;
    private OnColorChangedListener listener;

    public ColorPaletteView(Context c){ super(c); init(); }
    public ColorPaletteView(Context c, AttributeSet a){ super(c,a); init(); }
    public ColorPaletteView(Context c, AttributeSet a, int s){ super(c,a,s); init(); }

    private void init(){
        marker.setStyle(Paint.Style.STROKE);
        marker.setStrokeWidth(5f);
        marker.setColor(Color.WHITE);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setOnColorChangedListener(OnColorChangedListener l){ listener=l; }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float w=getWidth(), h=getHeight();
        if(w<=0 || h<=0) return;
        int[] colors={
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        };
        Shader hueShader=new LinearGradient(0,0,w,0,colors,null,Shader.TileMode.CLAMP);
        Shader satShader=new LinearGradient(0,0,0,h,Color.WHITE,Color.TRANSPARENT,Shader.TileMode.CLAMP);
        paint.setShader(new ComposeShader(hueShader,satShader,PorterDuff.Mode.SCREEN));
        canvas.drawRoundRect(0,0,w,h,22f,22f,paint);
        paint.setShader(null);

        float x=(hue/360f)*w;
        float y=saturation*h;
        marker.setColor(Color.BLACK); marker.setStrokeWidth(9f);
        canvas.drawCircle(x,y,15f,marker);
        marker.setColor(Color.WHITE); marker.setStrokeWidth(5f);
        canvas.drawCircle(x,y,15f,marker);
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()!=MotionEvent.ACTION_DOWN && e.getAction()!=MotionEvent.ACTION_MOVE && e.getAction()!=MotionEvent.ACTION_UP) return true;
        float x=Math.max(0,Math.min(getWidth(),e.getX()));
        float y=Math.max(0,Math.min(getHeight(),e.getY()));
        hue=getWidth()==0?0f:(x/getWidth())*360f;
        saturation=getHeight()==0?1f:(y/getHeight());
        int c=Color.HSVToColor(new float[]{hue,saturation,1f});
        if(listener!=null) listener.onColorChanged(Color.red(c),Color.green(c),Color.blue(c));
        invalidate();
        return true;
    }
}
