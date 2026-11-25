package com.ac7916.stethoscope;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Two separate channel traces; each contains only 32 samples per packet. */
public final class WaveformView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] a=new int[32], b=new int[32];
    private boolean valid=false;
    private final int teal=Color.rgb(72,216,194), blue=Color.rgb(117,167,255);
    public WaveformView(Context context){super(context);setMinimumHeight(dp(224));}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    public void setData(MicPacket packet){
        a=packet.wave0.clone();b=packet.wave1.clone();valid=true;invalidate();
    }
    public void clearData(){valid=false;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float w=getWidth(),h=getHeight();
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(Color.rgb(42,58,73));
        for(int i=1;i<8;i++) {float x=w*i/8;canvas.drawLine(x,0,x,h,p);}
        for(int i=1;i<8;i++){float y=h*i/8;canvas.drawLine(0,y,w,y,p);}
        float firstCenter=h*.25f,secondCenter=h*.75f;
        p.setColor(Color.rgb(68,91,109));canvas.drawLine(0,firstCenter,w,firstCenter,p);canvas.drawLine(0,secondCenter,w,secondCenter,p);
        if(valid){drawTrace(canvas,a,firstCenter,h*.20f,w,teal);drawTrace(canvas,b,secondCenter,h*.20f,w,blue);}
        else{
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(137,155,174));p.setTextSize(dp(13));
            canvas.drawText("尚无 AE3C 波形数据",dp(16),h/2,p);
        }
    }
    private void drawTrace(Canvas c,int[] data,float y0,float scale,float w,int color){
        Path path=new Path();
        for(int i=0;i<data.length;i++){
            float x=dp(8)+(w-dp(16))*i/(data.length-1f);
            float y=y0-data[i]*scale/128f;
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeJoin(Paint.Join.ROUND);p.setColor(color);
        c.drawPath(path,p);
    }
}
