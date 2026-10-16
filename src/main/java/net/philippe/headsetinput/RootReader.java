package net.philippe.headsetinput;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class RootReader implements AutoCloseable {
    static final class Device {
        final String path,id,name; final int roles;
        Device(String line) {
            String[] p=line.split("\t");
            path=p[1];id=p[2];name=unhex(p[3]);roles=Integer.parseInt(p[4]);
        }
        String kind() { return ((roles&1)!=0?"Controller ":"")+((roles&2)!=0?"Keyboard ":"")+((roles&4)!=0?"Mouse":""); }
    }
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor();
    private final Process process;
    private final StringBuilder error=new StringBuilder();
    private volatile boolean closed;
    RootReader(Context context, Set<String> ids, boolean list) throws IOException {
        // Android's process launcher does not search PATH, and KernelSU only
        // intercepts this exact path. A bare "su" fails before any grant prompt.
        List<String> command=new ArrayList<>(Arrays.asList("/system/bin/su","0",
            context.getApplicationInfo().nativeLibraryDir+"/libheadset-reader.so",list?"--list":"--read"));
        if(!list) {
            if(ids.isEmpty())throw new IOException("Select at least one peripheral");
            for(String id:ids) {
                if(!id.matches("[a-f0-9:-]{1,1699}"))throw new IOException("Invalid peripheral identity; scan again");
                command.add(id);
            }
        }
        process=new ProcessBuilder(command).start();
        Thread errors=new Thread(() -> {
            try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getErrorStream(),StandardCharsets.UTF_8))) {
                String line; while((line=r.readLine())!=null) synchronized(error) {
                    if(error.length()<2000)error.append(line).append('\n');
                }
            } catch(IOException ignored) { }
        },"root-reader-errors"); errors.setDaemon(true);errors.start();
    }
    static List<Device> scan(Context context) throws Exception {
        try(RootReader reader=new RootReader(context,Collections.emptySet(),true)) {
            ScheduledFuture<?> timeout=TIMER.schedule(reader::close,20,TimeUnit.SECONDS);
            try(BufferedReader lines=reader.lines()) {
                List<Device> result=new ArrayList<>(); String line;
                while((line=lines.readLine())!=null)if(line.startsWith("D\t"))result.add(new Device(line));
                if(reader.process.waitFor()!=0 || reader.closed)throw new IOException("Root scan failed or timed out. Open KernelSU, grant Input Forwarder in its superuser list, then scan again. "+reader.error());
                return result;
            } finally { timeout.cancel(false); }
        }
    }
    BufferedReader lines() { return new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)); }
    String error() { synchronized(error) {return error.toString().trim();} }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        try {process.getOutputStream().close();}catch(IOException ignored){}
        // stdin EOF is the normal shutdown. Escalate only this owned su process.
        TIMER.schedule(() -> {if(process.isAlive())process.destroy();},1,TimeUnit.SECONDS);
        TIMER.schedule(() -> {if(process.isAlive())process.destroyForcibly();},3,TimeUnit.SECONDS);
    }
    private static String unhex(String text) {
        byte[] b=new byte[text.length()/2];
        for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(text.substring(2*i,2*i+2),16);
        return new String(b,StandardCharsets.UTF_8);
    }
}
