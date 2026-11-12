package io.tvos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owns one authenticated local-shell relay only while the accessibility service is connected. */
final class AdbVolumeKeys implements AutoCloseable {
    interface Learned { void accept(String token,int direction,String device,int scan); }
    private final Context context;
    private final Learned learned;
    private final java.util.function.Consumer<String> ready;
    private final java.util.function.Consumer<Boolean> relayState;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean active=true;
    private volatile Adb connection;
    private volatile String session="";
    private volatile int direction;
    private volatile long until;
    private final Thread worker;
    AdbVolumeKeys(Context context,Learned learned,java.util.function.Consumer<String> ready,java.util.function.Consumer<Boolean> relayState) {
        this.context=context;this.learned=learned;this.ready=ready;this.relayState=relayState;
        worker=new Thread(this::run,"tvos-volume-adb");worker.setDaemon(true);worker.start();
    }
    void learn(String token,int mode,long expiry) { session=token;direction=mode;until=expiry; }
    void save(int mode,String device,int scan) {
        try {
            JSONArray old=new JSONArray(context.getSharedPreferences("volume_keys",Context.MODE_PRIVATE).getString("mapping","[]"));
            JSONArray next=new JSONArray();
            for(int n=0;n<old.length();n++)if(old.getJSONObject(n).getInt("direction")!=mode)next.put(old.getJSONObject(n));
            next.put(new JSONObject().put("device",device).put("scan",scan).put("direction",mode));
            context.getSharedPreferences("volume_keys",Context.MODE_PRIVATE).edit().putString("mapping",next.toString()).apply();
        }catch(Exception e){AppLog.w("Volume mapping save failed");}
    }
    private void run() {
        while(active){
            try(Adb adb=Adb.connect(context,1500,()->{})) {
                connection=adb;if(!active)return;
                String mapping=context.getSharedPreferences("volume_keys",Context.MODE_PRIVATE).getString("mapping","[]");
                String encoded=Base64.encodeToString(mapping.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
                String apk=context.getPackageCodePath().replace("'","'\\''");
                final StringBuilder pending=new StringBuilder();
                adb.stream("CLASSPATH='"+apk+"' app_process / io.tvos.VolumeRelay '"+encoded+"' 2>&1",new Adb.Stream(){
                    @Override public boolean active(){return active;}
                    @Override public String input(){
                        String token=session;int mode=direction;
                        return SystemClock.elapsedRealtime()<until&&!token.isEmpty()&&(mode==1||mode==-1)
                                ? "PING "+mode+" "+token+"\n" : "PING\n";
                    }
                    @Override public void output(byte[] bytes) {
                        pending.append(new String(bytes,StandardCharsets.UTF_8));
                        if(pending.length()>8192){pending.setLength(0);return;}
                        int end;while((end=pending.indexOf("\n"))>=0){String line=pending.substring(0,end).trim();pending.delete(0,end+1);receive(line);}
                    }
                });
            }catch(Exception e){if(active)AppLog.w("Volume ADB relay: "+e.getClass().getSimpleName());}
            finally{connection=null;main.post(()->{if(active)relayState.accept(false);});}
            if(active)try{Thread.sleep(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }
    }
    private void receive(String line) {
        if("READY".equals(line)){AppLog.w("Volume ADB relay ready");main.post(()->{if(active)relayState.accept(true);});return;}
        if(line.startsWith("LEARNING ")) {
            String token=line.substring(9);
            if(token.matches("[a-f0-9-]{36}"))main.post(()->{if(active)ready.accept(token);});
            return;
        }
        if(line.startsWith("ERROR ")){AppLog.w("Volume ADB relay: "+line);return;}
        if(!line.startsWith("LEARNED "))return;
        try {
            String[] fields=line.split(" ",5);if(fields.length!=5)return;
            String token=fields[1];int mode=Integer.parseInt(fields[2]),scan=Integer.parseInt(fields[3]);
            String device=new String(Base64.decode(fields[4],Base64.DEFAULT),StandardCharsets.UTF_8);
            if(!token.matches("[a-f0-9-]{36}")||device.isEmpty()||device.length()>256||scan<1||scan>65535||(mode!=1&&mode!=-1))return;
            main.post(()->{if(active)learned.accept(token,mode,device,scan);});
        }catch(RuntimeException ignored){}
    }
    @Override public void close(){active=false;Adb adb=connection;if(adb!=null)adb.close();worker.interrupt();}
}
