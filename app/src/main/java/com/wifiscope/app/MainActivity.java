package com.wifiscope.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 101;
    private static final int REQ_PLAN = 102;
    private static final int REQ_EXPORT = 103;

    /*
     * 7 muestras separadas 450 ms.
     * Duración aproximada: 2,7 segundos.
     */
    private static final int SAMPLE_COUNT = 7;
    private static final long SAMPLE_INTERVAL_MS = 450;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;

    private TextView txtConnection;
    private TextView txtLive;
    private TextView txtStatus;

    private SurveyView surveyView;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean measuring = false;

    private float pendingX;
    private float pendingY;

    private final ArrayList<Integer> rssiSamples =
            new ArrayList<>();

    private final ArrayList<String> bssidSamples =
            new ArrayList<>();

    private final ArrayList<String> ssidSamples =
            new ArrayList<>();

    private int sampleIndex = 0;

    private Bitmap pendingExport;

    private final Runnable liveUpdater =
            new Runnable() {
                @Override
                public void run() {

                    updateCurrentConnectionDisplay();

                    handler.postDelayed(
                            this,
                            1000
                    );
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_main
        );

        wifiManager =
                (WifiManager)
                        getApplicationContext()
                                .getSystemService(
                                        WIFI_SERVICE
                                );

        connectivityManager =
                (ConnectivityManager)
                        getSystemService(
                                CONNECTIVITY_SERVICE
                        );

        txtConnection =
                findViewById(
                        R.id.txtConnection
                );

        txtLive =
                findViewById(
                        R.id.txtLive
                );

        txtStatus =
                findViewById(
                        R.id.txtStatus
                );

        surveyView =
                findViewById(
                        R.id.surveyView
                );

        findViewById(
                R.id.btnPermissions
        ).setOnClickListener(
                v -> requestNeededPermissions()
        );

        findViewById(
                R.id.btnLoadPlan
        ).setOnClickListener(
                v -> choosePlan()
        );

        findViewById(
                R.id.btnRefreshWifi
        ).setOnClickListener(
                v -> updateCurrentConnectionDisplay()
        );

        findViewById(
                R.id.btnHeatmap
        ).setOnClickListener(
                v -> generateHeatmap()
        );

        findViewById(
                R.id.btnClearHeatmap
        ).setOnClickListener(
                v -> surveyView.showNormalPlan()
        );

        findViewById(
                R.id.btnUndo
        ).setOnClickListener(
                v -> {

                    if (measuring) {
                        txtStatus.setText(
                                "Espera a que termine la medición."
                        );
                        return;
                    }

                    surveyView.removeLastPoint();

                    txtStatus.setText(
                            "Último punto eliminado."
                    );
                }
        );

        findViewById(
                R.id.btnExport
        ).setOnClickListener(
                v -> exportPng()
        );

        surveyView.setTapListener(
                this::beginPointMeasurement
        );

        if (!hasPermissions()) {

            txtStatus.setText(
                    "Concede los permisos antes de medir."
            );

        } else {

            txtStatus.setText(
                    "Carga el plano y toca tu posición para medir."
            );
        }

        handler.post(
                liveUpdater
        );
    }

    private boolean hasPermissions() {

        if (
                checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION
                )
                        !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            return false;
        }

        if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES
                )
                        !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            return false;
        }

        return true;
    }

    private void requestNeededPermissions() {

        ArrayList<String> permissions =
                new ArrayList<>();

        if (
                checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION
                )
                        !=
                        PackageManager.PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission.ACCESS_FINE_LOCATION
            );
        }

        if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES
                )
                        !=
                        PackageManager.PERMISSION_GRANTED
        ) {

            permissions.add(
                    Manifest.permission.NEARBY_WIFI_DEVICES
            );
        }

        if (permissions.isEmpty()) {

            txtStatus.setText(
                    "Permisos concedidos."
            );

            updateCurrentConnectionDisplay();

            return;
        }

        requestPermissions(
                permissions.toArray(
                        new String[0]
                ),
                REQ_PERMS
        );
    }

    private WifiInfo getCurrentWifiInfo() {

        if (!hasPermissions()) {
            return null;
        }

        try {

            if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.S
            ) {

                Network network =
                        connectivityManager
                                .getActiveNetwork();

                if (network == null) {
                    return null;
                }

                NetworkCapabilities caps =
                        connectivityManager
                                .getNetworkCapabilities(
                                        network
                                );

                if (caps == null) {
                    return null;
                }

                if (
                        !caps.hasTransport(
                                NetworkCapabilities
                                        .TRANSPORT_WIFI
                        )
                ) {
                    return null;
                }

                Object info =
                        caps.getTransportInfo();

                if (info instanceof WifiInfo) {
                    return (WifiInfo) info;
                }
            }

            /*
             * Fallback para Android anteriores
             * y algunos fabricantes.
             */
            return wifiManager
                    .getConnectionInfo();

        } catch (SecurityException e) {

            return null;
        }
    }

    private String cleanSsid(
            WifiInfo info
    ) {

        if (info == null) {
            return null;
        }

        String ssid =
                info.getSSID();

        if (ssid == null) {
            return null;
        }

        if (
                ssid.startsWith("\"") &&
                ssid.endsWith("\"") &&
                ssid.length() >= 2
        ) {

            ssid =
                    ssid.substring(
                            1,
                            ssid.length() - 1
                    );
        }

        if (
                "<unknown ssid>"
                        .equalsIgnoreCase(
                                ssid
                        )
        ) {

            return null;
        }

        return ssid;
    }

    private boolean validBssid(
            String bssid
    ) {

        if (bssid == null) {
            return false;
        }

        if (
                "02:00:00:00:00:00"
                        .equalsIgnoreCase(
                                bssid
                        )
        ) {
            return false;
        }

        return true;
    }

    private void updateCurrentConnectionDisplay() {

        WifiInfo info =
                getCurrentWifiInfo();

        if (info == null) {

            txtConnection.setText(
                    "Wi‑Fi actual: no conectada"
            );

            txtLive.setText(
                    "BSSID: —   RSSI: —"
            );

            return;
        }

        String ssid =
                cleanSsid(info);

        String bssid =
                info.getBSSID();

        int rssi =
                info.getRssi();

        txtConnection.setText(
                "Wi‑Fi actual: " +
                        (
                                ssid != null
                                        ? ssid
                                        : "desconocida"
                        )
        );

        txtLive.setText(
                "BSSID: " +
                        (
                                validBssid(bssid)
                                        ? bssid
                                        : "—"
                        ) +
                        "   RSSI: " +
                        rssi +
                        " dBm"
        );
    }

    private void choosePlan() {

        if (measuring) {
            return;
        }

        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType(
                "image/*"
        );

        startActivityForResult(
                intent,
                REQ_PLAN
        );
    }

    private void beginPointMeasurement(
            float xNorm,
            float yNorm
    ) {

        if (measuring) {

            txtStatus.setText(
                    "Ya hay una medición en curso."
            );

            return;
        }

        if (
                surveyView
                        .getPlanBitmap()
                        ==
                        null
        ) {

            txtStatus.setText(
                    "Carga primero un plano."
            );

            return;
        }

        if (!hasPermissions()) {

            requestNeededPermissions();

            return;
        }

        WifiInfo current =
                getCurrentWifiInfo();

        if (current == null) {

            txtStatus.setText(
                    "El dispositivo no está conectado por Wi‑Fi."
            );

            return;
        }

        String ssid =
                cleanSsid(current);

        String bssid =
                current.getBSSID();

        if (
                ssid == null ||
                !validBssid(bssid)
        ) {

            txtStatus.setText(
                    "Android no permite leer SSID/BSSID. Revisa permisos y ubicación."
            );

            return;
        }

        measuring = true;

        pendingX = xNorm;
        pendingY = yNorm;

        rssiSamples.clear();
        bssidSamples.clear();
        ssidSamples.clear();

        sampleIndex = 0;

        txtStatus.setText(
                "Midiendo… 0/" +
                        SAMPLE_COUNT
        );

        takeNextSample();
    }

    private void takeNextSample() {

        if (!measuring) {
            return;
        }

        WifiInfo info =
                getCurrentWifiInfo();

        if (info != null) {

            String ssid =
                    cleanSsid(info);

            String bssid =
                    info.getBSSID();

            int rssi =
                    info.getRssi();

            if (
                    ssid != null &&
                    validBssid(bssid) &&
                    rssi < 0 &&
                    rssi > -120
            ) {

                rssiSamples.add(
                        rssi
                );

                bssidSamples.add(
                        bssid
                );

                ssidSamples.add(
                        ssid
                );
            }
        }

        sampleIndex++;

        txtStatus.setText(
                "Midiendo… " +
                        sampleIndex +
                        "/" +
                        SAMPLE_COUNT
        );

        if (
                sampleIndex <
                SAMPLE_COUNT
        ) {

            handler.postDelayed(
                    this::takeNextSample,
                    SAMPLE_INTERVAL_MS
            );

        } else {

            finishPointMeasurement();
        }
    }

    private void finishPointMeasurement() {

        measuring = false;

        if (
                rssiSamples.size() <
                3
        ) {

            txtStatus.setText(
                    "No se obtuvieron suficientes muestras. Punto no guardado."
            );

            return;
        }

        /*
         * Mediana de RSSI:
         * reduce saltos y lecturas aisladas.
         */
        ArrayList<Integer> ordered =
                new ArrayList<>(
                        rssiSamples
                );

        Collections.sort(
                ordered
        );

        int median =
                ordered.get(
                        ordered.size() / 2
                );

        String ssid =
                mostCommon(
                        ssidSamples
                );

        String bssid =
                mostCommon(
                        bssidSamples
                );

        if (
                ssid == null ||
                bssid == null
        ) {

            txtStatus.setText(
                    "No se pudo identificar la red. Punto no guardado."
            );

            return;
        }

        surveyView.addPoint(
                new SurveyView.PointMeasurement(
                        pendingX,
                        pendingY,
                        median,
                        ssid,
                        bssid,
                        System.currentTimeMillis()
                )
        );

        int min =
                Collections.min(
                        ordered
                );

        int max =
                Collections.max(
                        ordered
                );

        boolean roaming =
                differentValues(
                        bssidSamples
                ) >
                        1;

        String message =
                "Punto " +
                        surveyView
                                .getPoints()
                                .size() +
                        ": " +
                        median +
                        " dBm" +
                        " · muestras " +
                        min +
                        "…" +
                        max +
                        " · AP " +
                        bssid;

        if (roaming) {

            message +=
                    " · ROAMING DURANTE MEDICIÓN";
        }

        txtStatus.setText(
                message
        );

        updateCurrentConnectionDisplay();
    }

    private String mostCommon(
            ArrayList<String> values
    ) {

        if (values.isEmpty()) {
            return null;
        }

        HashMap<String, Integer> counts =
                new HashMap<>();

        for (String value : values) {

            if (value == null) {
                continue;
            }

            counts.put(
                    value,
                    counts.getOrDefault(
                            value,
                            0
                    ) +
                            1
            );
        }

        String best = null;
        int bestCount = 0;

        for (
                Map.Entry<String, Integer>
                entry :
                counts.entrySet()
        ) {

            if (
                    entry.getValue() >
                    bestCount
            ) {

                best =
                        entry.getKey();

                bestCount =
                        entry.getValue();
            }
        }

        return best;
    }

    private int differentValues(
            ArrayList<String> values
    ) {

        return
                new java.util.HashSet<>(
                        values
                )
                        .size();
    }

    private String currentSurveySsid() {

        if (
                surveyView
                        .getPoints()
                        .isEmpty()
        ) {

            WifiInfo info =
                    getCurrentWifiInfo();

            return cleanSsid(
                    info
            );
        }

        return
                surveyView
                        .getPoints()
                        .get(0)
                        .ssid;
    }

    private void generateHeatmap() {

        if (measuring) {

            txtStatus.setText(
                    "Espera a que termine la medición."
            );

            return;
        }

        if (
                surveyView
                        .getPoints()
                        .size()
                        <
                        2
        ) {

            txtStatus.setText(
                    "Necesitas al menos 2 puntos. Recomendado: 8-15."
            );

            return;
        }

        String ssid =
                currentSurveySsid();

        if (ssid == null) {

            txtStatus.setText(
                    "No se pudo determinar el SSID."
            );

            return;
        }

        int count = 0;
        int min = 999;
        int max = -999;

        java.util.HashSet<String>
                bssids =
                new java.util.HashSet<>();

        for (
                SurveyView.PointMeasurement p :
                surveyView.getPoints()
        ) {

            if (
                    ssid.equals(
                            p.ssid
                    )
            ) {

                count++;

                min =
                        Math.min(
                                min,
                                p.rssi
                        );

                max =
                        Math.max(
                                max,
                                p.rssi
                        );

                bssids.add(
                        p.bssid
                );
            }
        }

        if (count < 2) {

            txtStatus.setText(
                    "No hay suficientes puntos de la misma Wi‑Fi."
            );

            return;
        }

        surveyView
                .generateHeatmapForSsid(
                        ssid
                );

        txtStatus.setText(
                "Mapa: " +
                        count +
                        " puntos · " +
                        min +
                        "…" +
                        max +
                        " dBm · " +
                        bssids.size() +
                        " AP/BSSID"
        );
    }

    private void exportPng() {

        if (measuring) {
            return;
        }

        Bitmap bitmap =
                surveyView
                        .renderExportBitmap();

        if (bitmap == null) {

            txtStatus.setText(
                    "No hay plano para exportar."
            );

            return;
        }

        pendingExport =
                bitmap;

        String stamp =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.getDefault()
                )
                        .format(
                                new Date()
                        );

        Intent intent =
                new Intent(
                        Intent.ACTION_CREATE_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType(
                "image/png"
        );

        intent.putExtra(
                Intent.EXTRA_TITLE,
                "WiFiScope_" +
                        stamp +
                        ".png"
        );

        startActivityForResult(
                intent,
                REQ_EXPORT
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (
                resultCode != RESULT_OK ||
                data == null
        ) {
            return;
        }

        if (
                requestCode ==
                REQ_PLAN
        ) {

            Uri uri =
                    data.getData();

            try (
                    InputStream in =
                            getContentResolver()
                                    .openInputStream(
                                            uri
                                    )
            ) {

                Bitmap bitmap =
                        BitmapFactory
                                .decodeStream(
                                        in
                                );

                if (bitmap != null) {

                    surveyView
                            .setPlanBitmap(
                                    bitmap
                            );

                    surveyView
                            .clearPoints();

                    txtStatus.setText(
                            "Plano cargado. Toca tu posición para medir la Wi‑Fi actual."
                    );
                }

            } catch (Exception e) {

                txtStatus.setText(
                        "Error al cargar plano: " +
                                e.getMessage()
                );
            }
        }

        if (
                requestCode ==
                        REQ_EXPORT &&
                pendingExport != null
        ) {

            try (
                    OutputStream out =
                            getContentResolver()
                                    .openOutputStream(
                                            data.getData()
                                    )
            ) {

                pendingExport.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        out
                );

                txtStatus.setText(
                        "Mapa exportado."
                );

            } catch (Exception e) {

                txtStatus.setText(
                        "Error al exportar: " +
                                e.getMessage()
                );
            }

            pendingExport =
                    null;
        }
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacksAndMessages(
                null
        );

        super.onDestroy();
    }
}
