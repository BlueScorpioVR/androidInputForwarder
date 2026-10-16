package net.philippe.headsetinput;

import android.app.*;
import com.limelight.R;
import android.content.*;
import android.os.*;
import java.util.*;

public final class InputService extends Service {
    public static final String START="net.philippe.headsetinput.START", STOP="net.philippe.headsetinput.STOP";
    static volatile String status="Stopped";
    static volatile boolean running;
    private volatile InputSession session;
    private PowerManager.WakeLock wake;
    private final Handler main=new Handler(Looper.getMainLooper());
    private long notified;
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("input","Input forwarding",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || STOP.equals(intent.getAction())) {
            if(session!=null){status="Stopping…";session.cancel();}else stopSelf();
            return START_NOT_STICKY;
        }
        if(!START.equals(intent.getAction()) || running)return START_NOT_STICKY;
        running=true;status="Starting…";
        startForeground(1,notification());
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"HeadsetInput:session");
        wake.acquire();
        String host=HostConfig.host(this);
        Set<String> ids=new HashSet<>(HostConfig.prefs(this).getStringSet("devices",Collections.emptySet()));
        session=new InputSession(getApplicationContext(),text -> {
            status=text;
            if(System.currentTimeMillis()-notified>2000) {
                notified=System.currentTimeMillis();
                main.post(() -> {if(running)getSystemService(NotificationManager.class).notify(1,notification());});
            }
        });
        InputSession owned=session;
        new Thread(() -> {
            String result="Stopped";
            try {owned.run(host,ids);}catch(Exception e){result="Stopped · "+e.getMessage();}
            final String finalResult=result;
            main.post(() -> {
                status=finalResult;session=null;running=false;
                if(wake!=null && wake.isHeld())wake.release();
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            });
        },"apollo-input-session").start();
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,InputService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"input").setSmallIcon(R.drawable.ic_stat_forward)
            .setContentTitle("Input Forwarder").setContentText(status).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Stop forwarding",stop).build()).build();
    }
    @Override public void onDestroy() {
        if(session!=null)session.cancel();
        if(wake!=null&&wake.isHeld())wake.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
