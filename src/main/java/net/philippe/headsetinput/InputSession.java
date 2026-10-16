package net.philippe.headsetinput;

import android.content.Context;
import com.limelight.nvstream.*;
import com.limelight.nvstream.http.*;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import org.xmlpull.v1.*;
import javax.crypto.KeyGenerator;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One owned connection. start/stop native calls run only on the service's worker. */
final class InputSession implements NvConnectionListener, InputMapper.Sink {
    interface Status { void update(String text); }
    private final Context context;
    private final Status status;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile RootReader reader;
    private volatile String failure;
    private final InputMapper mapper=new InputMapper(this);
    private volatile boolean nativeConnected;
    private long events;
    InputSession(Context c,Status s) {context=c;status=s;}
    void cancel() {
        cancelled.set(true);
        RootReader r=reader; if(r!=null)r.close();
        // Interrupt handshake only. Established input is released before native stop.
        if(!nativeConnected)MoonBridge.interruptConnection();
    }
    void run(String host,Set<String> ids) throws Exception {
        try {
            status.update("Checking paired Apollo host…");
            NvHTTP http=HostConfig.http(context,host,true);
            String info=http.getServerInfo(true);
            if(http.getPairState(info)!=PairingManager.PairState.PAIRED)throw new IOException("Apollo no longer recognises this pairing");
            int appId=findRemoteInput(http.getAppListRaw());
            if(cancelled.get())return;
            // Confirm root access before requesting a network session. Open reader only
            // after the handshake so events cannot queue up and replay on connection.
            List<RootReader.Device> available=RootReader.scan(context);
            long gamepads=available.stream().filter(d -> ids.contains(d.id)&&(d.roles&1)!=0).count();
            if(gamepads>1)throw new IOException("Select only one Xbox controller");
            if(available.stream().noneMatch(d -> ids.contains(d.id)))throw new IOException("None of the selected peripherals are connected. Scan devices again.");
            if(cancelled.get())return;
            ConnectionContext cc=new ConnectionContext();
            cc.serverAddress=new ComputerDetails.AddressTuple(host,47989);
            cc.serverAppVersion=http.getServerVersion(info);cc.serverGfeVersion=http.getGfeVersion(info);
            cc.serverCodecModeSupport=(int)http.getServerCodecModeSupport(info);
            cc.isNvidiaServerSoftware=false;
            cc.negotiatedWidth=1280;cc.negotiatedHeight=720;
            KeyGenerator key=KeyGenerator.getInstance("AES");key.init(128);cc.riKey=key.generateKey();
            cc.riKeyId=new SecureRandom().nextInt();
            cc.streamConfig=new StreamConfiguration.Builder().setApp(new NvApp("Remote Input",appId,false))
                .setResolution(1280,720).setRefreshRate(30).setLaunchRefreshRate(30).setBitrate(1000)
                .setEnableSops(false).enableLocalAudioPlayback(true).setAttachedGamepadMask(gamepads>0?1:0)
                .setPersistGamepadsAfterDisconnect(false).build();
            status.update("Connecting to Remote Input…");
            if(!http.launchInputOnly(cc,appId))throw new IOException("Apollo rejected Remote Input");
            if(cancelled.get())return;
            MoonBridge.setupBridge(VIDEO,AUDIO,this);
            byte[] iv=ByteBuffer.allocate(16).putInt(cc.riKeyId).array();
            int result=MoonBridge.startConnection(host,cc.serverAppVersion,cc.serverGfeVersion,cc.rtspSessionUrl,
                cc.serverCodecModeSupport,1280,720,30,1000,1024,StreamConfiguration.STREAM_CFG_AUTO,
                MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),MoonBridge.VIDEO_FORMAT_H264,3000,
                cc.riKey.getEncoded(),iv,VIDEO.getCapabilities(),0,0);
            if(result!=0)throw new IOException(failure!=null?failure:"Connection failed: "+result);
            nativeConnected=true;
            if(cancelled.get())return;
            reader=new RootReader(context,ids,false);
            if(cancelled.get())reader.close();
            status.update("Connected · waiting for selected peripherals");
            try(BufferedReader lines=reader.lines()) {
                String line; long lastUpdate=0;
                while(!cancelled.get() && (line=lines.readLine())!=null) {
                    String[] p=line.split("\t");
                    switch(p[0]) {
                        case "D": mapper.add(p[2],Integer.parseInt(p[4]));break;
                        case "A": mapper.range(p[1],Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]));break;
                        case "E": mapper.event(p[1],Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]));events++;break;
                        case "R": mapper.remove(p[1]);break;
                        default: throw new IOException("Unexpected root reader output");
                    }
                    if(System.currentTimeMillis()-lastUpdate>500) {
                        status.update("Forwarding · "+mapper.deviceCount()+" devices · "+events+" local events");lastUpdate=System.currentTimeMillis();
                    }
                }
                if(!cancelled.get())throw new IOException("Root reader stopped. "+reader.error());
            }
            if(failure!=null)throw new IOException(failure);
        } finally {
            if(reader!=null)reader.close();
            if(nativeConnected) {
                mapper.releaseAll();
                // Allow queued neutral/release reports to leave before disconnecting.
                try {Thread.sleep(100);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                MoonBridge.stopConnection();nativeConnected=false;
            }
            MoonBridge.cleanupBridge();
        }
    }
    static int findRemoteInput(String xml) throws Exception {
        XmlPullParser p=XmlPullParserFactory.newInstance().newPullParser();p.setInput(new StringReader(xml));
        String uuid=null,id=null;boolean inApp=false;
        for(int e=p.getEventType();e!=XmlPullParser.END_DOCUMENT;e=p.next()) {
            if(e==XmlPullParser.START_TAG) {
                if(p.getName().equals("App")){uuid=id=null;inApp=true;}
                else if(inApp&&p.getName().equals("UUID"))uuid=p.nextText();
                else if(inApp&&p.getName().equals("ID"))id=p.nextText();
            } else if(e==XmlPullParser.END_TAG&&p.getName().equals("App")) {
                if("8CB5C136-DA67-4F99-B4A1-F9CD35005CF4".equalsIgnoreCase(uuid)&&id!=null)return Integer.parseInt(id);
                inApp=false;
            }
        }
        throw new IOException("Remote Input is not advertised. Enable Input Only mode in Apollo’s Input settings, and allow this client to list/launch it.");
    }
    @Override public void key(int vk,boolean down,int mods){MoonBridge.sendKeyboardInput((short)(0x8000|vk),(byte)(down?3:4),(byte)mods,(byte)0);}
    @Override public void mouseButton(int button,boolean down){MoonBridge.sendMouseButton((byte)(down?7:8),(byte)button);}
    @Override public void mouseMove(int x,int y){MoonBridge.sendMouseMove((short)x,(short)y);}
    @Override public void scroll(int v,int h){
        while(v!=0){int n=Math.max(-32767,Math.min(32767,v));MoonBridge.sendMouseHighResScroll((short)n);v-=n;}
        while(h!=0){int n=Math.max(-32767,Math.min(32767,h));MoonBridge.sendMouseHighResHScroll((short)n);h-=n;}
    }
    @Override public void gamepad(int b,int lt,int rt,int lx,int ly,int rx,int ry,boolean present){MoonBridge.sendMultiControllerInput((short)0,(short)(present?1:0),b,(byte)lt,(byte)rt,(short)lx,(short)ly,(short)rx,(short)ry);}
    @Override public void stageStarting(String s){status.update("Connecting · "+s);}
    @Override public void stageComplete(String s){}
    @Override public void stageFailed(String s,int ports,int code){failure=s+" failed ("+code+")";}
    @Override public void connectionStarted(){}
    @Override public void connectionTerminated(int code){failure="Apollo disconnected ("+code+")";cancel();}
    @Override public void displayMessage(String text){failure=text;status.update(text);}
    @Override public void displayTransientMessage(String text){status.update(text);}
    @Override public void connectionStatusUpdate(int s){}
    @Override public void rumble(short n,short l,short h){}
    @Override public void rumbleTriggers(short n,short l,short r){}
    @Override public void setHdrMode(boolean on,byte[] data){}
    @Override public void setMotionEventState(short n,byte t,short hz){}
    @Override public void setControllerLED(short n,byte r,byte g,byte b){}
    private static final VideoDecoderRenderer VIDEO=new VideoDecoderRenderer(){
        // Apollo's input-only session sends one dummy frame during negotiation.
        public int setup(int f,int w,int h,int fps){return 0;}
        public void start(){} public void stop(){} public void cleanup(){}
        public int getCapabilities(){return MoonBridge.CAPABILITY_DIRECT_SUBMIT;}
        public void setHdrMode(boolean e,byte[] d){}
        public int submitDecodeUnit(byte[] d,int l,int t,int n,int ft,char latency,long r,long q){return 0;}
    };
    private static final AudioRenderer AUDIO=new AudioRenderer(){
        public int setup(MoonBridge.AudioConfiguration c,int s,int f){return 0;}
        public void start(){}public void stop(){}public void cleanup(){}public void playDecodedAudio(short[] d){}
    };
}
