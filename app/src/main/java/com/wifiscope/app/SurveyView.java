package com.wifiscope.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
        public String bssid;

        public PointMeasurement(float xNorm, float yNorm, int rssi, String bssid) {
            this.xNorm = xNorm;
            this.yNorm = yNorm;
            this.rssi = rssi;
            this.bssid = bssid;
        }
    }

    public interface TapListener {
        void onPlanTap(float xNorm, float yNorm);
    }

    private Bitmap planBitmap;
    private Bitmap heatmapBitmap;

    private boolean showHeatmap = false;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<PointMeasurement> points = new ArrayList<>();

    private final RectF imageRect = new RectF();

    private TapListener tapListener;

    public SurveyView(Context context) {
        super(context);
        init();
    }

    public SurveyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SurveyView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(Color.rgb(32, 36, 42));

        paint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(28f);
        textPaint.setFakeBoldText(true);
    }

    public void setTapListener(TapListener listener) {
        this.tapListener = listener;
    }

    public void setPlanBitmap(Bitmap bitmap) {
        this.planBitmap = bitmap;
        this.heatmapBitmap = null;
        this.showHeatmap = false;

        invalidate();
    }

    public Bitmap getPlanBitmap() {
        return planBitmap;
    }

    public void addPoint(PointMeasurement point) {
        points.add(point);

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

    public List<PointMeasurement> getPoints() {
        return points;
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

    public void generateHeatmapFor(String bssid) {

        if (planBitmap == null) {
            return;
        }

        ArrayList<PointMeasurement> filtered = new ArrayList<>();

        for (PointMeasurement p : points) {
            if (
                p.bssid != null &&
                bssid != null &&
                p.bssid.equalsIgnoreCase(bssid)
            ) {
                filtered.add(p);
            }
        }

        if (filtered.size() < 2) {
            return;
        }

        int width = planBitmap.getWidth();
        int height = planBitmap.getHeight();

        double minRssi = Double.MAX_VALUE;
        double maxRssi = -Double.MAX_VALUE;

        for (PointMeasurement p : filtered) {

            if (p.rssi < minRssi) {
                minRssi = p.rssi;
            }

            if (p.rssi > maxRssi) {
                maxRssi = p.rssi;
            }
        }

        /*
         * Si todos los puntos tienen prácticamente el mismo RSSI,
         * ampliamos artificialmente un poco el rango para evitar
         * que todo el mapa quede exactamente del mismo color.
         */
        if (Math.abs(maxRssi - minRssi) < 2.0) {
            minRssi -= 3.0;
            maxRssi += 3.0;
        }

        Bitmap heatmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(heatmap);

        Paint cellPaint = new Paint();

        /*
         * Tamaño de las celdas del mapa.
         * Más pequeño = mapa más suave pero más lento.
         */
        int step = Math.max(
            5,
            Math.max(width, height) / 220
        );

        for (int y = 0; y < height; y += step) {

            for (int x = 0; x < width; x += step) {

                double numerator = 0.0;
                double denominator = 0.0;

                Integer exact = null;

                for (PointMeasurement p : filtered) {

                    float px = p.xNorm * width;
                    float py = p.yNorm * height;

                    double dx = x - px;
                    double dy = y - py;

                    double distanceSquared =
                        dx * dx +
                        dy * dy;

                    /*
                     * Si estamos prácticamente encima
                     * de una medición, usamos su valor real.
                     */
                    if (distanceSquared < 9.0) {
                        exact = p.rssi;
                        break;
                    }

                    double distance =
                        Math.sqrt(distanceSquared) +
                        0.000001;

                    /*
                     * Interpolación IDW
                     */
                    double weight =
                        1.0 /
                        Math.pow(distance, 2.2);

                    numerator +=
                        weight *
                        p.rssi;

                    denominator +=
                        weight;
                }

                double value;

                if (exact != null) {
                    value = exact;
                } else {

                    if (denominator == 0.0) {
                        continue;
                    }

                    value =
                        numerator /
                        denominator;
                }

                int color = rssiColor(
                    value,
                    minRssi,
                    maxRssi
                );

                cellPaint.setColor(color);

                canvas.drawRect(
                    x,
                    y,
                    Math.min(x + step, width),
                    Math.min(y + step, height),
                    cellPaint
                );
            }
        }

        /*
         * Superponemos el plano para seguir viendo
         * pasillos, paredes y referencias.
         */
        Paint planPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

        planPaint.setAlpha(150);

        canvas.drawBitmap(
            planBitmap,
            0,
            0,
            planPaint
        );

        heatmapBitmap = heatmap;
        showHeatmap = true;

        invalidate();
    }

    /*
     * Paleta continua:
     *
     * peor señal
     * magenta
     * ↓
     * azul
     * ↓
     * cian
     * ↓
     * verde
     * ↓
     * amarillo
     * ↓
     * naranja
     * mejor señal
     */
    private int rssiColor(
        double rssi,
        double minRssi,
        double maxRssi
    ) {

        int alpha = 150;

        if (Math.abs(maxRssi - minRssi) < 0.1) {
            maxRssi =
                minRssi +
                1.0;
        }

        float t =
            (float)(
                (rssi - minRssi) /
                (maxRssi - minRssi)
            );

        t = clamp(
            t,
            0f,
            1f
        );

        int magenta =
            Color.argb(
                alpha,
                210,
                70,
                195
            );

        int blue =
            Color.argb(
                alpha,
                70,
                110,
                235
            );

        int cyan =
            Color.argb(
                alpha,
                70,
                190,
                220
            );

        int green =
            Color.argb(
                alpha,
                70,
                200,
                120
            );

        int yellow =
            Color.argb(
                alpha,
                235,
                220,
                80
            );

        int orange =
            Color.argb(
                alpha,
                245,
                135,
                60
            );

        if (t < 0.20f) {

            return lerpColor(
                magenta,
                blue,
                t / 0.20f
            );

        } else if (t < 0.40f) {

            return lerpColor(
                blue,
                cyan,
                (t - 0.20f) /
                0.20f
            );

        } else if (t < 0.60f) {

            return lerpColor(
                cyan,
                green,
                (t - 0.40f) /
                0.20f
            );

        } else if (t < 0.80f) {

            return lerpColor(
                green,
                yellow,
                (t - 0.60f) /
                0.20f
            );

        } else {

            return lerpColor(
                yellow,
                orange,
                (t - 0.80f) /
                0.20f
            );
        }
    }

    private float clamp(
        float value,
        float min,
        float max
    ) {

        return Math.max(
            min,
            Math.min(
                max,
                value
            )
        );
    }

    private int lerp(
        int start,
        int end,
        float amount
    ) {

        return (int)(
            start +
            (end - start) *
            amount
        );
    }

    private int lerpColor(
        int color1,
        int color2,
        float amount
    ) {

        amount =
            clamp(
                amount,
                0f,
                1f
            );

        int alpha =
            lerp(
                Color.alpha(color1),
                Color.alpha(color2),
                amount
            );

        int red =
            lerp(
                Color.red(color1),
                Color.red(color2),
                amount
            );

        int green =
            lerp(
                Color.green(color1),
                Color.green(color2),
                amount
            );

        int blue =
            lerp(
                Color.blue(color1),
                Color.blue(color2),
                amount
            );

        return Color.argb(
            alpha,
            red,
            green,
            blue
        );
    }

    public Bitmap renderExportBitmap() {

        if (planBitmap == null) {
            return null;
        }

        Bitmap base;

        if (
            showHeatmap &&
            heatmapBitmap != null
        ) {
            base = heatmapBitmap;
        } else {
            base = planBitmap;
        }

        Bitmap output =
            base.copy(
                Bitmap.Config.ARGB_8888,
                true
            );

        Canvas canvas =
            new Canvas(output);

        Paint pointPaint =
            new Paint(
                Paint.ANTI_ALIAS_FLAG
            );

        Paint labelPaint =
            new Paint(
                Paint.ANTI_ALIAS_FLAG
            );

        labelPaint.setColor(
            Color.BLACK
        );

        labelPaint.setFakeBoldText(
            true
        );

        labelPaint.setTextSize(
            Math.max(
                22f,
                output.getWidth() /
                55f
            )
        );

        for (
            PointMeasurement m :
            points
        ) {

            float x =
                m.xNorm *
                output.getWidth();

            float y =
                m.yNorm *
                output.getHeight();

            float radius =
                Math.max(
                    9f,
                    output.getWidth() /
                    120f
                );

            pointPaint.setStyle(
                Paint.Style.FILL
            );

            pointPaint.setColor(
                Color.WHITE
            );

            canvas.drawCircle(
                x,
                y,
                radius,
                pointPaint
            );

            pointPaint.setStyle(
                Paint.Style.STROKE
            );

            pointPaint.setStrokeWidth(
                3f
            );

            pointPaint.setColor(
                Color.BLACK
            );

            canvas.drawCircle(
                x,
                y,
                radius,
                pointPaint
            );

            pointPaint.setStyle(
                Paint.Style.FILL
            );

            canvas.drawText(
                m.rssi +
                " dBm",
                x +
                radius +
                4,
                y -
                radius,
                labelPaint
            );
        }

        return output;
    }

    @Override
    protected void onDraw(
        Canvas canvas
    ) {

        super.onDraw(
            canvas
        );

        Bitmap bitmap;

        if (
            showHeatmap &&
            heatmapBitmap != null
        ) {

            bitmap =
                heatmapBitmap;

        } else {

            bitmap =
                planBitmap;
        }

        if (bitmap == null) {

            paint.setColor(
                Color.WHITE
            );

            paint.setTextSize(
                34f
            );

            canvas.drawText(
                "Carga un plano",
                40,
                70,
                paint
            );

            return;
        }

        float viewWidth =
            getWidth();

        float viewHeight =
            getHeight();

        float bitmapWidth =
            bitmap.getWidth();

        float bitmapHeight =
            bitmap.getHeight();

        float scale =
            Math.min(
                viewWidth /
                bitmapWidth,
                viewHeight /
                bitmapHeight
            );

        float drawWidth =
            bitmapWidth *
            scale;

        float drawHeight =
            bitmapHeight *
            scale;

        float left =
            (
                viewWidth -
                drawWidth
            ) /
            2f;

        float top =
            (
                viewHeight -
                drawHeight
            ) /
            2f;

        imageRect.set(
            left,
            top,
            left +
            drawWidth,
            top +
            drawHeight
        );

        canvas.drawBitmap(
            bitmap,
            null,
            imageRect,
            null
        );

        for (
            int i = 0;
            i < points.size();
            i++
        ) {

            PointMeasurement m =
                points.get(i);

            float x =
                left +
                m.xNorm *
                drawWidth;

            float y =
                top +
                m.yNorm *
                drawHeight;

            paint.setStyle(
                Paint.Style.FILL
            );

            paint.setColor(
                Color.WHITE
            );

            canvas.drawCircle(
                x,
                y,
                10f,
                paint
            );

            paint.setStyle(
                Paint.Style.STROKE
            );

            paint.setStrokeWidth(
                2f
            );

            paint.setColor(
                Color.BLACK
            );

            canvas.drawCircle(
                x,
                y,
                10f,
                paint
            );

            paint.setStyle(
                Paint.Style.FILL
            );

            textPaint.setTextSize(
                26f
            );

            canvas.drawText(
                (i + 1) +
                " · " +
                m.rssi +
                " dBm",
                x +
                14,
                y -
                10,
                textPaint
            );
        }
    }

    @Override
    public boolean onTouchEvent(
        MotionEvent event
    ) {

        if (
            event.getAction() !=
            MotionEvent.ACTION_UP
        ) {
            return true;
        }

        if (
            planBitmap == null
        ) {
            return true;
        }

        float x =
            event.getX();

        float y =
            event.getY();

        if (
            !imageRect.contains(
                x,
                y
            )
        ) {
            return true;
        }

        float xNorm =
            (
                x -
                imageRect.left
            ) /
            imageRect.width();

        float yNorm =
            (
                y -
                imageRect.top
            ) /
            imageRect.height();

        if (
            tapListener != null
        ) {

            tapListener.onPlanTap(
                xNorm,
                yNorm
            );
        }

        return true;
    }
}
