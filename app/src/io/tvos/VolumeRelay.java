package io.tvos;

import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import io.tvos.remote.RawVolumeState;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs as the local ADB shell, not as an app service. EOF/watchdog always stops repeat injection. */
public final class VolumeRelay {
    private VolumeRelay() { }
    private static volatile boolean alive=true;
    private static volatile long heartbeat;
    private static Process events;
    public static void main(String[] args) {
        try { run(args); }
        catch(Exception e){System.out.println("ERROR startup "+e.getClass().getSimpleName()+
                (e.getCause()==null?"":"/"+e.getCause().getClass().getSimpleName()));}
        finally{alive=false;if(events!=null)events.destroy();}
    }
    private static void run(String[] args) throws Exception {
        Object manager;Class<?> type;
        try { type=Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch(ClassNotFoundException e){type=Class.forName("android.hardware.input.InputManager");}
        manager=type.getMethod("getInstance").invoke(null);
        final Object input=manager;
        final Method inject=type.getMethod("injectInputEvent",android.view.InputEvent.class,int.class);
        final RawVolumeState state=new RawVolumeState(new RawVolumeState.Sink(){
            @Override public void step(int direction) {
                if(!alive||SystemClock.elapsedRealtime()-heartbeat>2000)return;
                long now=SystemClock.uptimeMillis();int code=direction>0?24:25;
                try {
                    KeyEvent down=new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,1,0,
                            KeyCharacterDevice.ID,0,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD);
                    boolean ok=Boolean.TRUE.equals(inject.invoke(input,down,0));
                    boolean released=Boolean.TRUE.equals(inject.invoke(input,KeyEvent.changeAction(down,KeyEvent.ACTION_UP),0));
                    if(!ok||!released)throw new IllegalStateException("injection rejected");
                }catch(Exception e){System.out.println("ERROR injection "+e.getClass().getSimpleName());alive=false;}
            }
            @Override public void learned(String token,int direction,String device,int scan) {
                String encoded=Base64.encodeToString(device.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
                System.out.println("LEARNED "+token+" "+direction+" "+scan+" "+encoded);
            }
        });
        if(args.length==1){JSONArray map=new JSONArray(new String(Base64.decode(args[0],Base64.DEFAULT),StandardCharsets.UTF_8));
            for(int n=0;n<map.length();n++){JSONObject item=map.getJSONObject(n);state.map(item.getString("device"),item.getInt("scan"),item.getInt("direction"));}}
        Process list=new ProcessBuilder("getevent","-lp").redirectErrorStream(true).start();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(list.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)state.line(line,SystemClock.elapsedRealtime());}
        if(list.waitFor()!=0)throw new IllegalStateException("getevent permission denied");
        events=new ProcessBuilder("getevent","-t").redirectErrorStream(true).start();
        heartbeat=SystemClock.elapsedRealtime();
        Thread stdin=new Thread(()->{
            try(BufferedReader r=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
                String last="";String line;while(alive&&(line=r.readLine())!=null){
                    String[] fields=line.split(" ");if(fields.length==0||!"PING".equals(fields[0]))continue;
                    heartbeat=SystemClock.elapsedRealtime();
                    int mode=fields.length==3?Integer.parseInt(fields[1]):0;
                    String session=fields.length==3?fields[2]:"";
                    state.learn(mode,session,heartbeat);
                    String current=mode+":"+session;
                    if(mode!=0&&!current.equals(last)&&session.matches("[a-f0-9-]{36}"))System.out.println("LEARNING "+session);
                    last=current;
                }
            }catch(Exception ignored){}finally{alive=false;state.cancel();events.destroy();}
        },"volume-owner");stdin.setDaemon(true);stdin.start();
        Thread clock=new Thread(()->{
            try{while(alive){long now=SystemClock.elapsedRealtime();if(now-heartbeat>2000){alive=false;break;}state.tick(now);Thread.sleep(state.delayMillis());}}
            catch(InterruptedException e){Thread.currentThread().interrupt();}finally{state.cancel();events.destroy();}
        },"volume-repeat");clock.setDaemon(true);clock.start();
        System.out.println("READY");
        try(BufferedReader r=new BufferedReader(new InputStreamReader(events.getInputStream(),StandardCharsets.UTF_8))){String line;while(alive&&(line=r.readLine())!=null)state.line(line,SystemClock.elapsedRealtime());}
        finally{alive=false;state.cancel();events.destroy();}
    }
    private static final class KeyCharacterDevice {static final int ID=-1;}
}
