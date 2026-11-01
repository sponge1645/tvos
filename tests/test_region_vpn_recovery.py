"""Run the real recovery adapter and durable journal with Android/scheduler recorders."""
from pathlib import Path
import subprocess
import tempfile

sources = {
'android/content/Context.java': '''package android.content;import java.io.File;public class Context {
 public static final String CONNECTIVITY_SERVICE="connectivity";private final File files;
 public Context(File f){files=f;}public Context getApplicationContext(){return this;}public File getFilesDir(){return files;}public Object getSystemService(String name){return null;}}''',
'android/net/ConnectivityManager.java':'package android.net;public class ConnectivityManager {}',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public boolean post(Runnable r){r.run();return true;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long elapsedRealtime(){return 0;}}',
'android/widget/Toast.java':'''package android.widget;import android.content.Context;import java.util.*;public class Toast {
 public static final int LENGTH_LONG=1;public static final List<String> shown=new ArrayList<>();private String text;
 public static Toast makeText(Context c,String t,int d){Toast v=new Toast();v.text=t;return v;}public void show(){shown.add(text);}}''',
'io/tvos/R.java':'package io.tvos;class R {static class string {static final int region_vpn_paused=1,region_vpn_restored=2,region_vpn_restore_failed=3;}}',
'io/tvos/AppStrings.java':'package io.tvos;class AppStrings {static String get(int id){return ""+id;}}',
'io/tvos/ModeJob.java':'''package io.tvos;import android.content.Context;class ModeJob {
 static int schedules;static long delay;static boolean scheduleRestore(Context c,long d){schedules++;delay=d;return true;}}''',
'io/tvos/NetworkStatus.java':'package io.tvos;class NetworkStatus {static boolean detectVpn(android.net.ConnectivityManager m){return false;}}',
'io/tvos/VpnControl.java':'package io.tvos;class VpnControl {static void toggle(android.content.Context c) throws java.io.IOException {throw new java.io.IOException("missing app");}}',
'io/tvos/RegionVpnPause.java':'''package io.tvos;class RegionVpnPause {interface Control {
 boolean connected();void toggle() throws java.io.IOException;long now();void waitStep() throws InterruptedException;}}''',
'io/tvos/RecoveryHarness.java':'''package io.tvos;
import java.io.*;import android.content.Context;import android.widget.Toast;import io.tvos.network.VpnRestoreJournal;
public class RecoveryHarness {
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 static class Control implements RegionVpnPause.Control {
  boolean online;int toggles;long time;String failure="missing";
  public boolean connected(){return online;}public long now(){return time;}
  public void toggle()throws IOException{toggles++;if(failure.equals("missing"))throw new IOException("removed/logout");if(failure.equals("success"))online=true;}
  public void waitStep()throws InterruptedException{time+=1000;if(failure.equals("interrupt"))throw new InterruptedException();}
 }
 static void seed(File dir)throws IOException{new VpnRestoreJournal(dir).pause(()->{},()->{});}
 static void reset(){ModeJob.schedules=0;Toast.shown.clear();}
 static void cycle(File dir,String mode)throws Exception{
  seed(dir);reset();Control c=new Control();c.failure=mode;
  for(int attempt=1;attempt<=5;attempt++){
   try{new RegionVpnRecovery(new Context(dir)).restore(c);throw new AssertionError("must fail");}
   catch(VpnRestoreJournal.RestoreFailure e){check(e.exhausted==(attempt==5),"only fifth failure terminal");}
   check(ModeJob.schedules==Math.min(attempt,4),"only four retries");
   check(ModeJob.delay==30000,"retry delay unchanged");
   check(Toast.shown.size()==(attempt==5?1:0),"no intermediate error toast");
   check(new VpnRestoreJournal(dir).pending()==(attempt<5),"durable intent cleared at limit");
   if(Thread.interrupted())check(mode.equals("interrupt"),"interruption retained");
  }
  check(Toast.shown.get(0).equals("3"),"manual recovery notice");
  for(int i=0;i<5;i++){RegionVpnRecovery.request(new Context(dir));RegionVpnRecovery.recover(new Context(dir));new RegionVpnRecovery(new Context(dir)).restore(c);}
  check(c.toggles==5&&Toast.shown.size()==1&&ModeJob.schedules==4,"stale jobs and repeated requests do not restart cycle");
 }
 public static void main(String[] args)throws Exception{
  File dir=new File(args[0]);cycle(dir,"missing");cycle(dir,"timeout");cycle(dir,"interrupt");
  seed(dir);reset();Control c=new Control();c.failure="success";new RegionVpnRecovery(new Context(dir)).restore(c);
  check(c.toggles==1&&!new VpnRestoreJournal(dir).pending()&&ModeJob.schedules==0&&Toast.shown.toString().equals("[2]"),"successful restoration unchanged");
  seed(dir);reset();c=new Control();c.online=true;new RegionVpnRecovery(new Context(dir)).restore(c);
  check(c.toggles==0&&!new VpnRestoreJournal(dir).pending()&&Toast.shown.toString().equals("[2]"),"already connected never toggled");
  System.out.println("PASS: five persisted attempts, four silent retries, one final notice, missing app/timeouts/interruptions, stale jobs, fresh cycles and success");
 }}'''}
for name in ['RegionVpnRecovery.java','network/VpnRestoreJournal.java']:
 sources['io/tvos/'+name]=Path('app/src/io/tvos',name).read_text()
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory)
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',directory,*[str(p) for p in root.rglob('*.java')]],check=True)
 state=root/'state';state.mkdir()
 subprocess.run(['java','-cp',directory,'io.tvos.RecoveryHarness',str(state)],check=True,timeout=20)
