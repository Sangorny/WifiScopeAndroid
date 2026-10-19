package com.wifiscope.app;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class SurveyView extends View {

    public static class PointMeasurement {
        public float xNorm,yNorm;
        public int rssi;
        public String bssid;
        public long scanTimestampUs;
        public boolean fresh;

        public PointMeasurement(float xNorm,float yNorm,int rssi,String bssid,long scanTimestampUs,boolean fresh) {
            this.xNorm=xNorm; this.yNorm=yNorm; this.rssi=rssi; this.bssid=bssid;
            this.scanTimestampUs=scanTimestampUs; this.fresh=fresh;
        }
    }

    public interface TapListener { void onPlanTap(float xNorm,float yNorm); }

    private Bitmap planBitmap,heatmapBitmap;
    private boolean showHeatmap=false;
    private final List<PointMeasurement> points=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect=new RectF();
    private TapListener tapListener;

    public SurveyView(Context c){super(c);init();}
    public SurveyView(Context c,AttributeSet a){super(c,a);init();}
    public SurveyView(Context c,AttributeSet a,int s){super(c,a,s);init();}

    private void init(){
        setBackgroundColor(Color.rgb(32,36,42));
        textPaint.setColor(Color.BLACK);
        textPaint.setFakeBoldText(true);
    }

    public void setTapListener(TapListener l){tapListener=l;}
    public void setPlanBitmap(Bitmap b){planBitmap=b;heatmapBitmap=null;showHeatmap=false;invalidate();}
    public Bitmap getPlanBitmap(){return planBitmap;}
    public List<PointMeasurement> getPoints(){return points;}

    public void addPoint(PointMeasurement p){points.add(p);heatmapBitmap=null;showHeatmap=false;invalidate();}
    public void removeLastPoint(){if(!points.isEmpty()){points.remove(points.size()-1);heatmapBitmap=null;showHeatmap=false;invalidate();}}
    public void clearPoints(){points.clear();heatmapBitmap=null;showHeatmap=false;invalidate();}
    public void showNormalPlan(){showHeatmap=false;invalidate();}

    public void generateHeatmapFor(String bssid){
        if(planBitmap==null||bssid==null)return;

        ArrayList<PointMeasurement> filtered=new ArrayList<>();
        for(PointMeasurement p:points) if(p.bssid!=null&&p.bssid.equalsIgnoreCase(bssid)) filtered.add(p);
        if(filtered.size()<2)return;

        double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
        for(PointMeasurement p:filtered){min=Math.min(min,p.rssi);max=Math.max(max,p.rssi);}
        if(Math.abs(max-min)<2.0){min-=4.0;max+=4.0;}

        int w=planBitmap.getWidth(),h=planBitmap.getHeight();
        Bitmap hm=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(hm);
        Paint cell=new Paint();
        int step=Math.max(5,Math.max(w,h)/220);

        for(int y=0;y<h;y+=step){
            for(int x=0;x<w;x+=step){
                double num=0,den=0; Integer exact=null;
                for(PointMeasurement p:filtered){
                    double px=p.xNorm*w,py=p.yNorm*h,dx=x-px,dy=y-py,d2=dx*dx+dy*dy;
                    if(d2<9){exact=p.rssi;break;}
                    double dist=Math.sqrt(d2)+1e-6;
                    double weight=1.0/Math.pow(dist,2.2);
                    num+=weight*p.rssi; den+=weight;
                }
                double v=exact!=null?exact:(den>0?num/den:min);
                cell.setColor(rssiColor(v,min,max));
                canvas.drawRect(x,y,Math.min(x+step,w),Math.min(y+step,h),cell);
            }
        }

        Paint pp=new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setAlpha(145);
        canvas.drawBitmap(planBitmap,0,0,pp);
        heatmapBitmap=hm;showHeatmap=true;invalidate();
    }

    private int rssiColor(double rssi,double min,double max){
        int alpha=155;
        float t=(float)((rssi-min)/(max-min));
        t=Math.max(0f,Math.min(1f,t));
        int[] c={
            Color.argb(alpha,210,70,195),
            Color.argb(alpha,70,110,235),
            Color.argb(alpha,70,190,220),
            Color.argb(alpha,70,200,120),
            Color.argb(alpha,235,220,80),
            Color.argb(alpha,245,135,60)
        };
        float s=t*(c.length-1);
        int i=Math.min(c.length-2,(int)Math.floor(s));
        float local=s-i;
        return lerpColor(c[i],c[i+1],local);
    }

    private int lerpColor(int a,int b,float t){
        return Color.argb(
            (int)(Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*t),
            (int)(Color.red(a)+(Color.red(b)-Color.red(a))*t),
            (int)(Color.green(a)+(Color.green(b)-Color.green(a))*t),
            (int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t)
        );
    }

    public Bitmap renderExportBitmap(){
        if(planBitmap==null)return null;
        Bitmap base=(showHeatmap&&heatmapBitmap!=null)?heatmapBitmap:planBitmap;
        Bitmap out=base.copy(Bitmap.Config.ARGB_8888,true);
        Canvas c=new Canvas(out);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint tp=new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK);tp.setFakeBoldText(true);tp.setTextSize(Math.max(22f,out.getWidth()/55f));

        for(PointMeasurement m:points){
            float x=m.xNorm*out.getWidth(),y=m.yNorm*out.getHeight(),r=Math.max(9f,out.getWidth()/120f);
            p.setStyle(Paint.Style.FILL);p.setColor(m.fresh?Color.WHITE:Color.LTGRAY);c.drawCircle(x,y,r,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(Color.BLACK);c.drawCircle(x,y,r,p);
            c.drawText(m.rssi+" dBm"+(m.fresh?"":"*"),x+r+4,y-r,tp);
        }
        return out;
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        Bitmap bm=(showHeatmap&&heatmapBitmap!=null)?heatmapBitmap:planBitmap;
        if(bm==null){paint.setColor(Color.WHITE);paint.setTextSize(34f);canvas.drawText("Carga un plano",40,70,paint);return;}

        float vw=getWidth(),vh=getHeight(),bw=bm.getWidth(),bh=bm.getHeight();
        float scale=Math.min(vw/bw,vh/bh),dw=bw*scale,dh=bh*scale,left=(vw-dw)/2f,top=(vh-dh)/2f;
        imageRect.set(left,top,left+dw,top+dh);
        canvas.drawBitmap(bm,null,imageRect,null);

        for(int i=0;i<points.size();i++){
            PointMeasurement m=points.get(i);
            float x=left+m.xNorm*dw,y=top+m.yNorm*dh;
            paint.setStyle(Paint.Style.FILL);paint.setColor(m.fresh?Color.WHITE:Color.LTGRAY);canvas.drawCircle(x,y,10,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.BLACK);canvas.drawCircle(x,y,10,paint);
            paint.setStyle(Paint.Style.FILL);textPaint.setTextSize(26f);
            canvas.drawText((i+1)+" · "+m.rssi+" dBm"+(m.fresh?"":"*"),x+14,y-10,textPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()!=MotionEvent.ACTION_UP)return true;
        if(planBitmap==null)return true;
        if(!imageRect.contains(e.getX(),e.getY()))return true;
        float xn=(e.getX()-imageRect.left)/imageRect.width();
        float yn=(e.getY()-imageRect.top)/imageRect.height();
        if(tapListener!=null)tapListener.onPlanTap(xn,yn);
        return true;
    }
}
