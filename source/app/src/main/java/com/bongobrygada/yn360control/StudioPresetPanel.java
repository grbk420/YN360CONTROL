package com.bongobrygada.yn360control;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact reusable preset cards; no third-party UI libraries needed. */
final class StudioPresetPanel {
    static final class Entry {
        final String name, subtitle;
        final int accent;
        final Runnable action;
        Entry(String name, String subtitle, int accent, Runnable action) {
            this.name=name; this.subtitle=subtitle; this.accent=accent; this.action=action;
        }
    }
    private static int dp(Activity a,float v) {
        return Math.round(v*a.getResources().getDisplayMetrics().density);
    }
    private static GradientDrawable background(Activity a,int start,int end,int stroke,int radius){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{start,end});
        g.setCornerRadius(dp(a,radius));
        g.setStroke(dp(a,1),stroke);
        return g;
    }
    static void insert(Activity activity,int containerId,String heading,String subtitle,Entry... entries){
        LinearLayout host=activity.findViewById(containerId);
        if(host==null)return;
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(activity,14);
        panel.setPadding(pad,pad,pad,pad);
        panel.setBackground(background(activity,0xFF222040,0xFF101F34,0xFF514B86,20));
        LinearLayout.LayoutParams panelParams=new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
        panelParams.bottomMargin=dp(activity,12);

        TextView title=new TextView(activity);
        title.setText(heading);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setTextColor(0xFFF7F5FF);
        title.setLetterSpacing(.07f);
        panel.addView(title);

        TextView caption=new TextView(activity);
        caption.setText(subtitle);
        caption.setTextSize(11.5f);
        caption.setTextColor(0xFFB9C4DB);
        caption.setPadding(0,dp(activity,2),0,dp(activity,9));
        panel.addView(caption);

        for(int i=0;i<entries.length;i+=2){
            LinearLayout row=new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin=dp(activity,8);
            for(int j=i;j<Math.min(i+2,entries.length);j++){
                Entry entry=entries[j];
                Button b=new Button(activity);
                b.setAllCaps(false);
                b.setText(entry.name+"\n"+entry.subtitle);
                b.setTextSize(12.5f);
                b.setTextColor(Color.WHITE);
                b.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
                b.setPadding(dp(activity,12),dp(activity,5),dp(activity,6),dp(activity,5));
                b.setMinHeight(0);
                b.setMinimumHeight(0);
                b.setSingleLine(false);
                GradientDrawable normal=background(activity,0xFF28304C,0xFF20253B,entry.accent,14);
                b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),normal,null));
                b.setOnClickListener(v->entry.action.run());
                LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(activity,64),1f);
                if(j>i)bp.leftMargin=dp(activity,8);
                row.addView(b,bp);
            }
            if(entries.length%2==1 && i==entries.length-1){
                TextView spacer=new TextView(activity);
                LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(activity,1),1f);
                sp.leftMargin=dp(activity,8);
                row.addView(spacer,sp);
            }
            panel.addView(row,rowParams);
        }
        host.addView(panel,0,panelParams);
    }
}
