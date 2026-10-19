package com.wifiscope.sensor;

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
import android.widget.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_PERMS=101,REQ_PLAN=102,REQ_EXPORT=103;
    private WifiManager wifiManager; private TextView txtStatus,txtSelected; private Spinner spinner; private SurveyView surveyView;
    private List<ScanResult> latest=new ArrayList<>(); private final List<ScanResult> spinnerResults=new ArrayList<>(); private Bitmap pendingExport;
    private final BroadcastReceiver scanReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){loadResults();}};

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);
        wifiManager=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);txtStatus=findViewById(R.id.txtStatus);txtSelected=findViewById(R.id.txtSelected);spinner=findViewById(R.id.spinnerNetworks);surveyView=findViewById(R.id.surveyView);
        findViewById(R.id.btnPermissions).setOnClickListener(v->requestNeeded());findViewById(R.id.btnLoadPlan).setOnClickListener(v->choosePlan());findViewById(R.id.btnScan).setOnClickListener(v->scanWifi());findViewById(R.id.btnHeatmap).setOnClickListener(v->generateHeatmap());findViewById(R.id.btnNormal).setOnClickListener(v->surveyView.showNormalPlan());findViewById(R.id.btnUndo).setOnClickListener(v->{surveyView.removeLastPoint();txtStatus.setText("Último punto eliminado.");});findViewById(R.id.btnExport).setOnClickListener(v->exportPng());
        surveyView.setTapListener(this::recordPoint);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){if(pos>=0&&pos<spinnerResults.size()){ScanResult r=spinnerResults.get(pos);txtSelected.setText("Seleccionada: "+ssid(r)+" | "+r.BSSID+" | "+r.level+" dBm");}}});
        registerReceiver(scanReceiver,new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));if(!hasPermissions())txtStatus.setText("Concede permisos antes del primer escaneo.");
    }
    private boolean hasPermissions(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return false;return Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED;}
    private void requestNeeded(){ArrayList<String> p=new ArrayList<>();if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.NEARBY_WIFI_DEVICES);if(p.isEmpty())txtStatus.setText("Permisos concedidos.");else requestPermissions(p.toArray(new String[0]),REQ_PERMS);}
    private void choosePlan(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,REQ_PLAN);}
    private boolean locationEnabled(){try{android.location.LocationManager lm=(android.location.LocationManager)getSystemService(Context.LOCATION_SERVICE);if(Build.VERSION.SDK_INT>=28)return lm.isLocationEnabled();return Settings.Secure.getInt(getContentResolver(),Settings.Secure.LOCATION_MODE)!=Settings.Secure.LOCATION_MODE_OFF;}catch(Exception e){return false;}}
    private void scanWifi(){if(!hasPermissions()){requestNeeded();return;}if(!wifiManager.isWifiEnabled()){txtStatus.setText("Activa el Wi‑Fi.");return;}if(!locationEnabled()){txtStatus.setText("Android exige tener activada la ubicación para escanear Wi‑Fi.");return;}txtStatus.setText("Escaneando...");if(!wifiManager.startScan()){loadResults();txtStatus.setText("Android ha limitado el escaneo activo. Uso los últimos resultados disponibles.");}}
    private void loadResults(){try{List<ScanResult> r=wifiManager.getScanResults();if(r==null)r=new ArrayList<>();ArrayList<ScanResult> s=new ArrayList<>(r);Collections.sort(s,(a,b)->Integer.compare(b.level,a.level));latest=s;spinnerResults.clear();spinnerResults.addAll(s);ArrayList<String> labels=new ArrayList<>();for(ScanResult x:s)labels.add(ssid(x)+" | "+x.BSSID+" | "+x.level+" dBm | Ch "+channel(x.frequency));spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));txtStatus.setText("Detectados "+s.size()+" BSSID.");}catch(SecurityException e){txtStatus.setText("Sin permiso para leer resultados Wi‑Fi.");}}
    private String ssid(ScanResult r){String s=r.SSID;return s==null||s.trim().isEmpty()||"<unknown ssid>".equalsIgnoreCase(s)?"<oculta>":s;}
    private int channel(int f){if(f==2484)return 14;if(f>=2412&&f<=2472)return(f-2407)/5;if(f>=5000&&f<=5900)return(f-5000)/5;if(f>=5955&&f<=7115)return(f-5950)/5;return 0;}
    private ScanResult selected(){int p=spinner.getSelectedItemPosition();return p>=0&&p<spinnerResults.size()?spinnerResults.get(p):null;}
    private void recordPoint(float x,float y){if(surveyView.getPlanBitmap()==null){txtStatus.setText("Carga primero un plano.");return;}ScanResult r=selected();if(r==null){txtStatus.setText("Escanea y selecciona una red antes de marcar un punto.");return;}int rssi=r.level;for(ScanResult z:latest)if(z.BSSID!=null&&z.BSSID.equalsIgnoreCase(r.BSSID)){rssi=z.level;break;}surveyView.addPoint(new SurveyView.PointMeasurement(x,y,rssi,r.BSSID));txtStatus.setText("Punto guardado: "+ssid(r)+" · "+rssi+" dBm");}
    private void generateHeatmap(){ScanResult r=selected();if(r==null){txtStatus.setText("Selecciona un BSSID.");return;}int c=0;for(SurveyView.PointMeasurement p:surveyView.getPoints())if(p.bssid.equalsIgnoreCase(r.BSSID))c++;if(c<2){txtStatus.setText("Necesitas al menos 2 puntos del mismo BSSID; mejor 5 o más.");return;}surveyView.generateHeatmapFor(r.BSSID);txtStatus.setText("Mapa generado con "+c+" puntos.");}
    private void exportPng(){Bitmap b=surveyView.renderExportBitmap();if(b==null){txtStatus.setText("No hay plano para exportar.");return;}pendingExport=b;String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.getDefault()).format(new Date());Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/png");i.putExtra(Intent.EXTRA_TITLE,"WiFiScope_"+stamp+".png");startActivityForResult(i,REQ_EXPORT);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null)return;if(req==REQ_PLAN){Uri uri=data.getData();try(InputStream in=getContentResolver().openInputStream(uri)){Bitmap b=BitmapFactory.decodeStream(in);if(b!=null){surveyView.setPlanBitmap(b);txtStatus.setText("Plano cargado. Escanea y selecciona un BSSID.");}}catch(Exception e){txtStatus.setText("Error al cargar plano: "+e.getMessage());}}else if(req==REQ_EXPORT&&pendingExport!=null){Uri uri=data.getData();try(OutputStream out=getContentResolver().openOutputStream(uri)){pendingExport.compress(Bitmap.CompressFormat.PNG,100,out);txtStatus.setText("Mapa exportado.");}catch(Exception e){txtStatus.setText("Error al exportar: "+e.getMessage());}pendingExport=null;}}
    @Override protected void onDestroy(){try{unregisterReceiver(scanReceiver);}catch(Exception ignored){}super.onDestroy();}
}
