package net.philippe.headsetinput;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.limelight.nvstream.http.*;
import java.security.SecureRandom;
import java.util.*;

/** Small 2D panel that remains usable inside Android XR's spatial desktop. */
public final class MainActivity extends Activity {
    private static final int INK=0xff233b55, BASE=0xffedf3f8, BLUE=0xff245e91, MUTED=0xff52677c;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView state,message;
    private EditText host;
    private LinearLayout devices;
    private Button pair,scan,start,stop;
    private boolean working;
    private Set<String> selected;
    private final List<CheckBox> checks=new ArrayList<>();
    private final Runnable refresh=new Runnable(){public void run(){
        state.setText(InputService.status);
        boolean busy=InputService.running||working;
        start.setEnabled(!busy&&!selected.isEmpty());stop.setEnabled(InputService.running);
        pair.setEnabled(!busy);scan.setEnabled(!busy);host.setEnabled(!busy);
        for(CheckBox c:checks)c.setEnabled(!busy);
        handler.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        selected=new HashSet<>(HostConfig.prefs(this).getStringSet("devices",Collections.emptySet()));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BASE);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(28),dp(28),dp(28),dp(28));
        scroll.addView(panel);setContentView(scroll);
        panel.addView(text("Input Forwarder",32,INK,true));
        panel.addView(text("Your Bluetooth peripherals. Your PC.\nSteam Link keeps handling VR.",17,MUTED,false));
        state=text(InputService.status,23,INK,true);state.setPadding(0,dp(24),0,dp(12));panel.addView(state);
        LinearLayout actions=row();
        start=button("Start forwarding",BLUE);stop=button("Stop",0xff9b383c);
        actions.addView(start,new LinearLayout.LayoutParams(0,dp(64),2));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(64),1);sp.leftMargin=dp(12);actions.addView(stop,sp);panel.addView(actions);
        panel.addView(text("Starts only when you choose. Keeps running when you leave this panel. Stop here or from the notification.",15,MUTED,false));
        panel.addView(heading("Apollo PC"));
        host=new EditText(this);host.setSingleLine();host.setText(HostConfig.host(this));host.setTextSize(19);
        host.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        host.setHint("PC IP address or hostname");host.setContentDescription("Apollo PC address");panel.addView(host);
        pair=button("Pair with PC",BLUE);panel.addView(pair);
        panel.addView(text("Enable Input Only mode in Apollo’s Input settings. Pairing shows a PIN to enter on the PC.",15,MUTED,false));
        panel.addView(heading("Peripherals"));
        scan=button("Scan devices (root)",BLUE);panel.addView(scan);
        devices=new LinearLayout(this);devices.setOrientation(LinearLayout.VERTICAL);panel.addView(devices);
        devices.addView(text(selected.isEmpty()?"Scan and select your Xbox controller, keyboard and mouse.":selected.size()+" saved selections. Scan to review them.",16,MUTED,false));
        panel.addView(text("Only selected devices are forwarded. Android still receives their input. One Xbox controller is supported; rumble is not yet implemented.",15,MUTED,false));
        message=text("",16,INK,false);message.setPadding(0,dp(20),0,0);panel.addView(message);
        panel.addView(text("Built with Moonlight · GPL-3.0\nLocal event counts show capture activity, not confirmation from the PC.",13,MUTED,false));
        pair.setOnClickListener(v -> pair());scan.setOnClickListener(v -> scan());
        start.setOnClickListener(v -> {
            if(!saveHost())return;
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
            startForegroundService(new Intent(this,InputService.class).setAction(InputService.START));
        });
        stop.setOnClickListener(v -> startService(new Intent(this,InputService.class).setAction(InputService.STOP)));
    }
    private boolean saveHost() {
        String address=host.getText().toString().trim();
        if(address.isEmpty()||address.contains("/")||address.contains(" ")){message.setText("Enter an IP address or hostname, without http:// or a path.");return false;}
        HostConfig.prefs(this).edit().putString("host",address).commit();return true;
    }
    private void pair() {
        if(!saveHost())return;
        String address=HostConfig.host(this);
        String pin=String.format(Locale.US,"%04d",new SecureRandom().nextInt(10000));
        working=true;message.setText("Pairing PIN: "+pin+"\nEnter this PIN in Apollo on your PC.");
        new Thread(() -> {
            String result;
            try {
                NvHTTP http=HostConfig.http(this,address,false);
                http.boundPairingWait();
                String info=http.getServerInfo(true);
                if(http.getPairState(info)==PairingManager.PairState.PAIRED && HostConfig.certificate(this,address)!=null)result="Already paired with "+address;
                else {
                    PairingManager pm=http.getPairingManager();
                    PairingManager.PairState state=pm.pair(info,pin);
                    if(state!=PairingManager.PairState.PAIRED)throw new IllegalStateException("Pairing result: "+state);
                    HostConfig.saveCertificate(this,address,pm.getPairedCert());result="Paired with "+address;
                }
            }catch(Exception e){result="Pairing failed: "+e.getMessage();}
            String finalResult=result;
            runOnUiThread(() -> {working=false;message.setText(finalResult);});
        },"apollo-pair").start();
    }
    private void scan() {
        working=true;message.setText("Scanning… If this fails, grant Input Forwarder in KernelSU’s superuser list. This KernelSU build does not show a prompt first.");
        new Thread(() -> {
            try {
                List<RootReader.Device> found=RootReader.scan(this);
                runOnUiThread(() -> {
                    devices.removeAllViews();checks.clear();
                    Set<String> seen=new HashSet<>();
                    for(RootReader.Device d:found) {
                        seen.add(d.id);CheckBox check=new CheckBox(this);check.setText(d.name+"\n"+d.kind()+" · "+d.path);
                        check.setTextSize(17);check.setTextColor(INK);check.setMinHeight(dp(68));check.setChecked(selected.contains(d.id));
                        check.setOnCheckedChangeListener((b,on) -> {if(on)selected.add(d.id);else selected.remove(d.id);saveSelection();});
                        checks.add(check);devices.addView(check);
                    }
                    for(String id:new HashSet<>(selected))if(!seen.contains(id)) {
                        CheckBox missing=new CheckBox(this);missing.setText("Saved device (disconnected) — uncheck to forget");missing.setChecked(true);
                        missing.setOnCheckedChangeListener((b,on)->{if(on)selected.add(id);else selected.remove(id);saveSelection();});checks.add(missing);devices.addView(missing);
                    }
                    if(found.isEmpty())devices.addView(text("No supported peripherals found. Connect them over Bluetooth, then scan again.",16,MUTED,false));
                    working=false;message.setText("Found "+found.size()+" input devices. Select only the peripherals you want to forward.");
                });
            }catch(Exception e){runOnUiThread(()->{working=false;message.setText(e.getMessage());});}
        },"input-scan").start();
    }
    private void saveSelection(){HostConfig.prefs(this).edit().putStringSet("devices",new HashSet<>(selected)).apply();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private TextView text(String t,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));v.setPadding(0,dp(6),0,dp(6));return v;}
    private TextView heading(String t){TextView v=text(t,21,INK,true);v.setPadding(0,dp(24),0,dp(8));return v;}
    private Button button(String t,int color){Button b=new Button(this);b.setText(t);b.setTextSize(18);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setMinHeight(dp(58));GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(10));b.setBackground(bg);b.setPadding(dp(16),dp(10),dp(16),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(58));lp.topMargin=dp(8);lp.bottomMargin=dp(8);b.setLayoutParams(lp);return b;}
}
