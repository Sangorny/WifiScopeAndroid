package com.wifiscope.app;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    private static final int REQ_PERMS=101, REQ_PLAN=102, REQ_EXPORT=103;
    private static final long FRESH_SCAN_MAX_AGE_US = 12_000_000L;

    private WifiManager wifiManager;
    private TextView txtStatus, txtSelected;
    private Spinner spinner;
    private SurveyView surveyView;

    private final List<ScanResult> spinnerResults=new ArrayList<>();
    private List<ScanResult> latest=new ArrayList<>();
    private Bitmap pendingExport;

    private Float pendingX=null, pendingY=null;
    private String pendingBssid=null, pendingSsid=null;
    private boolean waitingForTapScan=false;

    private final BroadcastReceiver scanReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean updated=intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false);
            loadResults();
            if (waitingForTapScan) finishPendingPoint(updated);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        txtStatus=findViewById(R.id.txtStatus);
        txtSelected=findViewById(R.id.txtSelected);
        spinner=findViewById(R.id.spinnerNetworks);
        surveyView=findViewById(R.id.surveyView);

        findViewById(R.id.btnPermissions).setOnClickListener(v->requestPermissionsNeeded());
        findViewById(R.id.btnLoadPlan).setOnClickListener(v->choosePlan());
        findViewById(R.id.btnScan).setOnClickListener(v->scanWifiManual());
        findViewById(R.id.btnHeatmap).setOnClickListener(v->generateHeatmap());
        findViewById(R.id.btnClearHeatmap).setOnClickListener(v->surveyView.showNormalPlan());
        findViewById(R.id.btnUndo).setOnClickListener(v->{ surveyView.removeLastPoint(); txtStatus.setText("Último punto eliminado."); });
        findViewById(R.id.btnExport).setOnClickListener(v->exportPng());

        surveyView.setTapListener(this::requestPointMeasurement);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> p) {}
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos>=0 && pos<spinnerResults.size()) {
                    ScanResult r=spinnerResults.get(pos);
                    txtSelected.setText("Seleccionada: "+safeSsid(r)+" | "+r.BSSID+" | "+r.level+" dBm | Ch "+channel(r.frequency));
                }
            }
        });

        registerReceiver(scanReceiver,new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        if (!hasPermissions()) txtStatus.setText("Concede permisos antes del primer escaneo.");
    }

    private boolean hasPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNeeded() {
        ArrayList<String> p=new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (p.isEmpty()) txtStatus.setText("Permisos concedidos.");
        else requestPermissions(p.toArray(new String[0]),REQ_PERMS);
    }

    private void choosePlan() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i,REQ_PLAN);
    }

    private boolean canScan() {
        if (!hasPermissions()) { requestPermissionsNeeded(); return false; }
        if (!wifiManager.isWifiEnabled()) { txtStatus.setText("Activa el Wi‑Fi."); return false; }
        if (!locationEnabled()) { txtStatus.setText("Android exige tener activada la ubicación para escanear Wi‑Fi."); return false; }
        return true;
    }

    private void scanWifiManual() {
        if (!canScan()) return;
        txtStatus.setText("Escaneando redes…");
        boolean accepted=wifiManager.startScan();
        if (!accepted) {
            loadResults();
            txtStatus.setText("Android ha limitado el escaneo activo. Se muestran los últimos resultados disponibles.");
        }
    }

    private void requestPointMeasurement(float xNorm,float yNorm) {
        if (surveyView.getPlanBitmap()==null) { txtStatus.setText("Carga primero un plano."); return; }

        ScanResult selected=selectedResult();
        if (selected==null) { txtStatus.setText("Escanea y selecciona un BSSID."); return; }
        if (!canScan()) return;

        pendingX=xNorm;
        pendingY=yNorm;
        pendingBssid=selected.BSSID;
        pendingSsid=safeSsid(selected);
        waitingForTapScan=true;

        txtStatus.setText("Midiendo "+pendingSsid+"… espera al refresco Wi‑Fi.");
        boolean accepted=wifiManager.startScan();

        if (!accepted) {
            loadResults();
            finishPendingPoint(false);
        }
    }

    private void finishPendingPoint(boolean scanReportedUpdated) {
        if (!waitingForTapScan || pendingBssid==null || pendingX==null || pendingY==null) return;

        ScanResult match=null;
        for (ScanResult r:latest) {
            if (r.BSSID!=null && r.BSSID.equalsIgnoreCase(pendingBssid)) { match=r; break; }
        }

        if (match==null) {
            txtStatus.setText("No se encontró el BSSID seleccionado. Punto no guardado.");
            clearPending();
            return;
        }

        long nowUs=SystemClock.elapsedRealtimeNanos()/1000L;
        long ageUs=nowUs-match.timestamp;
        boolean freshByTimestamp=ageUs>=0 && ageUs<=FRESH_SCAN_MAX_AGE_US;
        boolean fresh=scanReportedUpdated && freshByTimestamp;

        surveyView.addPoint(new SurveyView.PointMeasurement(
            pendingX,pendingY,match.level,match.BSSID,match.timestamp,fresh
        ));

        txtStatus.setText("Punto guardado: "+pendingSsid+" · "+match.level+" dBm · "+(fresh?"lectura nueva":"lectura en caché (*)"));
        clearPending();
    }

    private void clearPending() {
        waitingForTapScan=false;
        pendingX=null; pendingY=null; pendingBssid=null; pendingSsid=null;
    }

    private boolean locationEnabled() {
        try {
            android.location.LocationManager lm=(android.location.LocationManager)getSystemService(Context.LOCATION_SERVICE);
            if (Build.VERSION.SDK_INT>=28) return lm.isLocationEnabled();
            return Settings.Secure.getInt(getContentResolver(),Settings.Secure.LOCATION_MODE)!=Settings.Secure.LOCATION_MODE_OFF;
        } catch(Exception e) { return false; }
    }

    private void loadResults() {
        try {
            List<ScanResult> r=wifiManager.getScanResults();
            if (r==null) r=new ArrayList<>();
            ArrayList<ScanResult> sorted=new ArrayList<>(r);
            sorted.sort((a,b)->Integer.compare(b.level,a.level));
            latest=sorted;

            String keepBssid=null;
            ScanResult selected=selectedResult();
            if (selected!=null) keepBssid=selected.BSSID;

            spinnerResults.clear();
            spinnerResults.addAll(sorted);

            ArrayList<String> labels=new ArrayList<>();
            int keepPos=-1;
            for (int i=0;i<sorted.size();i++) {
                ScanResult x=sorted.get(i);
                labels.add(safeSsid(x)+" | "+x.BSSID+" | "+x.level+" dBm | Ch "+channel(x.frequency));
                if (keepBssid!=null && keepBssid.equalsIgnoreCase(x.BSSID)) keepPos=i;
            }

            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);
            spinner.setAdapter(adapter);
            if (keepPos>=0) spinner.setSelection(keepPos);
        } catch(SecurityException e) {
            txtStatus.setText("Sin permiso para leer resultados Wi‑Fi.");
        }
    }

    private String safeSsid(ScanResult r) {
        String s=r.SSID;
        return (s==null || s.trim().isEmpty() || "<unknown ssid>".equalsIgnoreCase(s)) ? "<oculta>" : s;
    }

    private int channel(int f) {
        if (f==2484) return 14;
        if (f>=2412 && f<=2472) return (f-2407)/5;
        if (f>=5000 && f<=5900) return (f-5000)/5;
        if (f>=5955 && f<=7115) return (f-5950)/5;
        return 0;
    }

    private ScanResult selectedResult() {
        int pos=spinner.getSelectedItemPosition();
        return (pos>=0 && pos<spinnerResults.size()) ? spinnerResults.get(pos) : null;
    }

    private void generateHeatmap() {
        ScanResult r=selectedResult();
        if (r==null) { txtStatus.setText("Selecciona un BSSID."); return; }

        int count=0,freshCount=0,min=999,max=-999;
        for (SurveyView.PointMeasurement p:surveyView.getPoints()) {
            if (p.bssid.equalsIgnoreCase(r.BSSID)) {
                count++;
                if (p.fresh) freshCount++;
                min=Math.min(min,p.rssi);
                max=Math.max(max,p.rssi);
            }
        }

        if (count<2) { txtStatus.setText("Necesitas al menos 2 puntos; recomendado 5-10."); return; }

        surveyView.generateHeatmapFor(r.BSSID);
        String warning=(max-min<3) ? " · AVISO: variación RSSI muy baja ("+(max-min)+" dB)" : "";
        txtStatus.setText("Mapa: "+count+" puntos · frescos "+freshCount+"/"+count+" · rango "+min+"…"+max+" dBm"+warning);
    }

    private void exportPng() {
        Bitmap b=surveyView.renderExportBitmap();
        if (b==null) { txtStatus.setText("No hay plano para exportar."); return; }
        pendingExport=b;
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.getDefault()).format(new Date());
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_TITLE,"WiFiScope_"+stamp+".png");
        startActivityForResult(i,REQ_EXPORT);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (resultCode!=RESULT_OK || data==null) return;

        if (requestCode==REQ_PLAN) {
            try(InputStream in=getContentResolver().openInputStream(data.getData())) {
                Bitmap b=BitmapFactory.decodeStream(in);
                if (b!=null) {
                    surveyView.setPlanBitmap(b);
                    surveyView.clearPoints();
                    txtStatus.setText("Plano cargado. Escanea y selecciona un BSSID.");
                }
            } catch(Exception e) { txtStatus.setText("Error al cargar plano: "+e.getMessage()); }
        }

        if (requestCode==REQ_EXPORT && pendingExport!=null) {
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                pendingExport.compress(Bitmap.CompressFormat.PNG,100,out);
                txtStatus.setText("Mapa exportado.");
            } catch(Exception e) { txtStatus.setText("Error al exportar: "+e.getMessage()); }
            pendingExport=null;
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(scanReceiver); } catch(Exception ignored){}
        super.onDestroy();
    }
}
