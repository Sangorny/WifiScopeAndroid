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
        public float xNorm;
        public float yNorm;
        public int rssi;
        public String ssid;
        public String bssid;
        public long timestamp;

        public PointMeasurement(
                float xNorm,
                float yNorm,
                int rssi,
                String ssid,
                String bssid,
                long timestamp
        ) {
            this.xNorm = xNorm;
            this.yNorm = yNorm;
            this.rssi = rssi;
            this.ssid = ssid;
            this.bssid = bssid;
            this.timestamp = timestamp;
        }
    }

    public interface TapListener {
        void onPlanTap(float xNorm, float yNorm);
    }

    private Bitmap planBitmap;
    private Bitmap heatmapBitmap;
    private boolean showHeatmap = false;

    private final List<PointMeasurement> points = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();

    private TapListener tapListener;

    public SurveyView(Context c) {
        super(c);
        init();
    }

    public SurveyView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public SurveyView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        setBackgroundColor(Color.rgb(32, 36, 42));
        textPaint.setColor(Color.BLACK);
        textPaint.setFakeBoldText(true);
    }

    public void setTapListener(TapListener l) {
        tapListener = l;
    }

    public void setPlanBitmap(Bitmap b) {
        planBitmap = b;
        heatmapBitmap = null;
        showHeatmap = false;
        invalidate();
    }

    public Bitmap getPlanBitmap() {
        return planBitmap;
    }

    public List<PointMeasurement> getPoints() {
        return points;
    }

    public void addPoint(PointMeasurement p) {
        points.add(p);
        heatmapBitmap = null;
        showHeatmap = false;
        invalidate();
    }

    public void removeLastPoint() {
        if (!points.isEmpty()) {
            points.remove(points.size() - 1);
            heatmapBitmap = null;
            showHeatmap = false;
            invalidate();
        }
    }

    public void clearPoints() {
        points.clear();
        heatmapBitmap = null;
        showHeatmap = false;
        invalidate();
    }

    public void showNormalPlan() {
        showHeatmap = false;
        invalidate();
    }

    public void generateHeatmapForSsid(String ssid) {

        if (planBitmap == null || ssid == null) {
            return;
        }

        ArrayList<PointMeasurement> filtered = new ArrayList<>();

        for (PointMeasurement p : points) {
            if (p.ssid != null && p.ssid.equals(ssid)) {
                filtered.add(p);
            }
        }

        if (filtered.size() < 2) {
            return;
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (PointMeasurement p : filtered) {
            min = Math.min(min, p.rssi);
            max = Math.max(max, p.rssi);
        }

        /*
         * Si el rango es muy pequeño, solo ampliamos la escala visual.
         * No modificamos las mediciones.
         */
        if (Math.abs(max - min) < 4.0) {
            double center = (max + min) / 2.0;
            min = center - 4.0;
            max = center + 4.0;
        }

        int w = planBitmap.getWidth();
        int h = planBitmap.getHeight();

        Bitmap hm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(hm);

        Paint cell = new Paint();
        int step = Math.max(5, Math.max(w, h) / 220);

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {

                double num = 0.0;
                double den = 0.0;
                Integer exact = null;

                for (PointMeasurement p : filtered) {

                    double px = p.xNorm * w;
                    double py = p.yNorm * h;

                    double dx = x - px;
                    double dy = y - py;
                    double d2 = dx * dx + dy * dy;

                    if (d2 < 9.0) {
                        exact = p.rssi;
                        break;
                    }

                    double dist = Math.sqrt(d2) + 0.000001;
                    double weight = 1.0 / Math.pow(dist, 2.2);

                    num += weight * p.rssi;
                    den += weight;
                }

                double value = exact != null
                        ? exact
                        : (den > 0 ? num / den : min);

                cell.setColor(rssiColor(value, min, max));

                canvas.drawRect(
                        x,
                        y,
                        Math.min(x + step, w),
                        Math.min(y + step, h),
                        cell
                );
            }
        }

        Paint planPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        planPaint.setAlpha(145);

        canvas.drawBitmap(planBitmap, 0, 0, planPaint);

        heatmapBitmap = hm;
        showHeatmap = true;

        invalidate();
    }

    private int rssiColor(double rssi, double min, double max) {

        int alpha = 155;

        float t = (float)((rssi - min) / (max - min));
        t = Math.max(0f, Math.min(1f, t));

        int[] colors = {
                Color.argb(alpha, 210, 70, 195),
                Color.argb(alpha, 70, 110, 235),
                Color.argb(alpha, 70, 190, 220),
                Color.argb(alpha, 70, 200, 120),
                Color.argb(alpha, 235, 220, 80),
                Color.argb(alpha, 245, 135, 60)
        };

        float scaled = t * (colors.length - 1);

        int index = Math.min(
                colors.length - 2,
                (int)Math.floor(scaled)
        );

        float local = scaled - index;

        return lerpColor(
                colors[index],
                colors[index + 1],
                local
        );
    }

    private int lerpColor(int a, int b, float t) {

        return Color.argb(
                (int)(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int)(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int)(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int)(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t)
        );
    }

    public Bitmap renderExportBitmap() {

        if (planBitmap == null) {
            return null;
        }

        Bitmap base =
                showHeatmap && heatmapBitmap != null
                        ? heatmapBitmap
                        : planBitmap;

        Bitmap out =
                base.copy(
                        Bitmap.Config.ARGB_8888,
                        true
                );

        Canvas canvas = new Canvas(out);

        Paint pointPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        Paint labelPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        labelPaint.setColor(Color.BLACK);
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextSize(
                Math.max(
                        22f,
                        out.getWidth() / 55f
                )
        );

        for (PointMeasurement m : points) {

            float x = m.xNorm * out.getWidth();
            float y = m.yNorm * out.getHeight();
            float radius =
                    Math.max(
                            9f,
                            out.getWidth() / 120f
                    );

            pointPaint.setStyle(Paint.Style.FILL);
            pointPaint.setColor(Color.WHITE);

            canvas.drawCircle(
                    x,
                    y,
                    radius,
                    pointPaint
            );

            pointPaint.setStyle(Paint.Style.STROKE);
            pointPaint.setStrokeWidth(3);
            pointPaint.setColor(Color.BLACK);

            canvas.drawCircle(
                    x,
                    y,
                    radius,
                    pointPaint
            );

            canvas.drawText(
                    m.rssi + " dBm",
                    x + radius + 4,
                    y - radius,
                    labelPaint
            );
        }

        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        Bitmap bitmap =
                showHeatmap && heatmapBitmap != null
                        ? heatmapBitmap
                        : planBitmap;

        if (bitmap == null) {

            paint.setColor(Color.WHITE);
            paint.setTextSize(34f);

            canvas.drawText(
                    "Carga un plano",
                    40,
                    70,
                    paint
            );

            return;
        }

        float vw = getWidth();
        float vh = getHeight();

        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();

        float scale =
                Math.min(
                        vw / bw,
                        vh / bh
                );

        float dw = bw * scale;
        float dh = bh * scale;

        float left = (vw - dw) / 2f;
        float top = (vh - dh) / 2f;

        imageRect.set(
                left,
                top,
                left + dw,
                top + dh
        );

        canvas.drawBitmap(
                bitmap,
                null,
                imageRect,
                null
        );

        for (int i = 0; i < points.size(); i++) {

            PointMeasurement m =
                    points.get(i);

            float x =
                    left +
                    m.xNorm *
                    dw;

            float y =
                    top +
                    m.yNorm *
                    dh;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);

            canvas.drawCircle(
                    x,
                    y,
                    10,
                    paint
            );

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(Color.BLACK);

            canvas.drawCircle(
                    x,
                    y,
                    10,
                    paint
            );

            paint.setStyle(Paint.Style.FILL);

            textPaint.setTextSize(25f);

            canvas.drawText(
                    (i + 1) +
                            " · " +
                            m.rssi +
                            " dBm",
                    x + 14,
                    y - 10,
                    textPaint
            );
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {

        if (e.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }

        if (planBitmap == null) {
            return true;
        }

        if (!imageRect.contains(e.getX(), e.getY())) {
            return true;
        }

        float xNorm =
                (e.getX() - imageRect.left) /
                imageRect.width();

        float yNorm =
                (e.getY() - imageRect.top) /
                imageRect.height();

        if (tapListener != null) {
            tapListener.onPlanTap(
                    xNorm,
                    yNorm
            );
        }

        return true;
    }
}
