package com.wifiscope.sensor;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class SurveyView extends View {
    public static class PointMeasurement {
        public float xNorm, yNorm;
        public int rssi;
        public String bssid;
        public PointMeasurement(float xNorm,float yNorm,int rssi,String bssid){this.xNorm=xNorm;this.yNorm=yNorm;this.rssi=rssi;this.bssid=bssid;}
    }
    public interface TapListener { void onPlanTap(float xNorm,float yNorm); }
    private Bitmap planBitmap, heatmapBitmap;
    private boolean showHeatmap=false;
    private final List<PointMeasurement> points=new ArrayList<>();
    private final RectF imageRect=new RectF();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private TapListener tapListener;

    public SurveyView(Context c){super(c);init();}
    public SurveyView(Context c,AttributeSet a){super(c,a);init();}
    private void init(){ setBackgroundColor(Color.rgb(32,36,42)); textPaint.setColor(Color.BLACK); textPaint.setFakeBoldText(true); }
    public void setTapListener(TapListener l){tapListener=l;}
    public void setPlanBitmap(Bitmap b){planBitmap=b;heatmapBitmap=null;showHeatmap=false;points.clear();invalidate();}
    public Bitmap getPlanBitmap(){return planBitmap;}
    public List<PointMeasurement> getPoints(){return points;}
    public void addPoint(PointMeasurement p){points.add(p);heatmapBitmap=null;showHeatmap=false;invalidate();}
    public void removeLastPoint(){if(!points.isEmpty()){points.remove(points.size()-1);heatmapBitmap=null;showHeatmap=false;invalidate();}}
    public void showNormalPlan(){showHeatmap=false;invalidate();}

    private int rssiColor(double rssi){
        int a=145;
        if(rssi>=-55) return Color.argb(a,30,190,70);
        if(rssi>=-67) return Color.argb(a,220,205,35);
        if(rssi>=-75) return Color.argb(a,245,145,25);
        return Color.argb(a,215,55,45);
    }

    public void generateHeatmapFor(String bssid){
        if(planBitmap==null)return;
        ArrayList<PointMeasurement> f=new ArrayList<>();
        for(PointMeasurement p:points) if(p.bssid!=null&&p.bssid.equalsIgnoreCase(bssid)) f.add(p);
        if(f.size()<2)return;
        int w=planBitmap.getWidth(), h=planBitmap.getHeight();
        Bitmap hm=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(hm); Paint cell=new Paint();
        int step=Math.max(6,Math.max(w,h)/180);
        for(int y=0;y<h;y+=step){for(int x=0;x<w;x+=step){
            double num=0,den=0; Integer exact=null;
            for(PointMeasurement p:f){
                double px=p.xNorm*w, py=p.yNorm*h, dx=x-px, dy=y-py, d2=dx*dx+dy*dy;
                if(d2<9){exact=p.rssi;break;}
                double wt=1.0/Math.pow(Math.sqrt(d2)+1e-6,2.2); num+=wt*p.rssi; den+=wt;
            }
            double v=exact!=null?exact:num/den; cell.setColor(rssiColor(v));
            c.drawRect(x,y,Math.min(x+step,w),Math.min(y+step,h),cell);
        }}
        Paint pp=new Paint(Paint.ANTI_ALIAS_FLAG); pp.setAlpha(155); c.drawBitmap(planBitmap,0,0,pp);
        heatmapBitmap=hm; showHeatmap=true; invalidate();
    }

    public Bitmap renderExportBitmap(){
        if(planBitmap==null)return null;
        Bitmap base=(showHeatmap&&heatmapBitmap!=null)?heatmapBitmap:planBitmap;
        Bitmap out=base.copy(Bitmap.Config.ARGB_8888,true); Canvas c=new Canvas(out);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG), tp=new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK); tp.setTextSize(Math.max(20,out.getWidth()/55f)); tp.setFakeBoldText(true);
        for(PointMeasurement m:points){
            float x=m.xNorm*out.getWidth(), y=m.yNorm*out.getHeight(), r=Math.max(9,out.getWidth()/120f);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawCircle(x,y,r,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(Color.BLACK);c.drawCircle(x,y,r,p);p.setStyle(Paint.Style.FILL);
            c.drawText(m.rssi+" dBm",x+r+4,y-r,tp);
        }
        return out;
    }

    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);Bitmap bm=(showHeatmap&&heatmapBitmap!=null)?heatmapBitmap:planBitmap;
        if(bm==null){paint.setColor(Color.WHITE);paint.setTextSize(34);canvas.drawText("Carga un plano",40,70,paint);return;}
        float vw=getWidth(),vh=getHeight(),bw=bm.getWidth(),bh=bm.getHeight(),s=Math.min(vw/bw,vh/bh),dw=bw*s,dh=bh*s,left=(vw-dw)/2,top=(vh-dh)/2;
        imageRect.set(left,top,left+dw,top+dh);canvas.drawBitmap(bm,null,imageRect,null);
        for(int i=0;i<points.size();i++){PointMeasurement m=points.get(i);float x=left+m.xNorm*dw,y=top+m.yNorm*dh;
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawCircle(x,y,10,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.BLACK);canvas.drawCircle(x,y,10,paint);paint.setStyle(Paint.Style.FILL);
            textPaint.setTextSize(25);canvas.drawText((i+1)+" · "+m.rssi+" dBm",x+14,y-10,textPaint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_UP)return true;if(planBitmap==null)return true;float x=e.getX(),y=e.getY();if(!imageRect.contains(x,y))return true;
        if(tapListener!=null)tapListener.onPlanTap((x-imageRect.left)/imageRect.width(),(y-imageRect.top)/imageRect.height());return true;}
}
