"""Execute actual Android volume adapter with fake platform/ADB boundaries and real runner/store."""
from pathlib import Path
import subprocess
import tempfile
files={
'io/tvos/RemoteVolume.java':Path('app/src/io/tvos/RemoteVolume.java').read_text(),
'io/tvos/remote/VolumeHoldRunner.java':Path('app/src/io/tvos/remote/VolumeHoldRunner.java').read_text(),
'io/tvos/remote/VolumeHoldStore.java':Path('app/src/io/tvos/remote/VolumeHoldStore.java').read_text(),
'android/os/Build.java':'package android.os;public class Build {public static class VERSION {public static int SDK_INT=33;}}',
'android/os/PowerManager.java':'package android.os;public class PowerManager {public boolean interactive=true;public boolean isInteractive(){return interactive;}}',
'android/content/Intent.java':'package android.content;public class Intent {public static final String ACTION_SCREEN_OFF="off",ACTION_SCREEN_ON="on";public final String action;public String pkg;public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}}',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter {public IntentFilter(String a){}public void addAction(String a){}}',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/res/AssetManager.java':'package android.content.res;public class AssetManager {public int reads;public java.io.InputStream open(String n)throws java.io.IOException {if(!n.equals("volume-hold.jar"))throw new AssertionError();reads++;return new java.io.ByteArrayInputStream(new byte[]{1,2,3});}}',
'android/content/Context.java':'''package android.content;public class Context {
 public static final int RECEIVER_NOT_EXPORTED=4;public static final String POWER_SERVICE="power";
 public final java.io.File dir;public int flags=-1,unregister;public BroadcastReceiver receiver;
 public final android.os.PowerManager power=new android.os.PowerManager();public final android.content.res.AssetManager assets=new android.content.res.AssetManager();
 public final java.util.concurrent.BlockingQueue<String> states=new java.util.concurrent.LinkedBlockingQueue<>();
 public Context(java.io.File d){dir=d;}public Context getApplicationContext(){return this;}public java.io.File getFilesDir(){return dir;}public String getPackageName(){return "io.tvos";}
 public Object getSystemService(String s){return power;}public android.content.res.AssetManager getAssets(){return assets;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String permission,Object handler){if(!permission.equals("io.tvos.permission.VOLUME_CONTROL"))throw new AssertionError();receiver=r;flags=0;return null;}public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String permission,Object handler,int mode){if(!permission.equals("io.tvos.permission.VOLUME_CONTROL"))throw new AssertionError();receiver=r;flags=mode;return null;}
 public void unregisterReceiver(BroadcastReceiver r){if(receiver!=r)throw new AssertionError();receiver=null;unregister++;}
 public void sendBroadcast(Intent i){if(!"io.tvos".equals(i.pkg))throw new AssertionError();states.add(io.tvos.remote.VolumeHoldStore.status(dir));}
 public void fire(String a){receiver.onReceive(this,new Intent(a));}
 public void await(String expected)throws Exception {long end=System.nanoTime()+2000000000L;while(System.nanoTime()<end){String state=states.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS);if(expected.equals(state))return;}throw new AssertionError("Missing status "+expected);}
}''',
'android/util/Base64.java':'package android.util;public class Base64 {public static final int NO_WRAP=2;public static String encodeToString(byte[] bytes,int flags){return java.util.Base64.getEncoder().encodeToString(bytes);}}',
'io/tvos/AppLog.java':'package io.tvos;final class AppLog {static void w(String s){throw new AssertionError(s);}}',
'io/tvos/Adb.java':'''package io.tvos;import java.io.*;import java.nio.charset.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
final class Adb implements Closeable {
 static final List<Adb> links=Collections.synchronizedList(new ArrayList<Adb>());static final AtomicInteger live=new AtomicInteger(),max=new AtomicInteger();
 final CountDownLatch stopped=new CountDownLatch(1);boolean closed;String command;
 interface Stream {boolean active();void output(byte[] b)throws IOException;}
 static Adb connect(android.content.Context c,int timeout,Object approval){Adb a=new Adb();links.add(a);return a;}
 void stream(String c,Stream s)throws IOException {
  command=c;int count=live.incrementAndGet();max.set(Math.max(max.get(),count));
  try {s.output("READY device helper mode=".getBytes(StandardCharsets.US_ASCII));s.output("audio: ready\\n".getBytes(StandardCharsets.US_ASCII));
   synchronized(this){while(!closed&&s.active())try{wait(100);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
  }finally {live.decrementAndGet();stopped.countDown();}
 }
 public synchronized void close(){closed=true;notifyAll();}
}''',
'io/tvos/BindingHarness.java':'''package io.tvos;import io.tvos.remote.*;import android.content.*;import java.nio.file.*;import java.io.*;import java.util.*;import java.util.concurrent.*;
public class BindingHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  for(int sdk:new int[]{28,33}){
   android.os.Build.VERSION.SDK_INT=sdk;Adb.links.clear();Adb.max.set(0);Path dir=Files.createTempDirectory("volume-binding-");Context c=new Context(dir.toFile());RemoteVolume volume=new RemoteVolume(c);
   try{
    volume.start();c.await("OFF");check(Adb.links.isEmpty()&&c.flags==(sdk>=33?4:0),"default-off, receiver export policy");
    VolumeHoldStore.setEnabled(c.dir,true);c.fire(RemoteVolume.CHANGE);c.await("READY");check(Adb.links.size()==1,"first connection");
    Adb first=Adb.links.get(0);check(first.command.contains("printf '%s' 'AQID'")&&first.command.contains("audio quiet")&&first.command.contains("trap 'rm -f /data/local/tmp/tvos-volume-"),"bundled helper, exact quiet audio mode and scoped shell cleanup");
    c.power.interactive=false;c.fire(Intent.ACTION_SCREEN_OFF);check(first.stopped.await(2,TimeUnit.SECONDS),"screen-off ends stream");c.await("OFF");check(Adb.links.size()==1,"no connection while screen off");
    c.power.interactive=true;c.fire(Intent.ACTION_SCREEN_ON);c.await("READY");check(Adb.links.size()==2,"screen-on resumes");
    VolumeHoldStore.setEnabled(c.dir,false);c.fire(RemoteVolume.CHANGE);check(Adb.links.get(1).stopped.await(2,TimeUnit.SECONDS),"setting off closes connection");c.await("OFF");
    VolumeHoldStore.setEnabled(c.dir,true);c.fire(RemoteVolume.CHANGE);c.await("READY");Adb interrupted=Adb.links.get(2);volume.interrupted();check(interrupted.stopped.await(2,TimeUnit.SECONDS),"feedback interruption cancels old input");c.await("READY");check(Adb.links.size()==4,"enabled fix resumes after interruption without a screen event");
    Adb last=Adb.links.get(Adb.links.size()-1);volume.close();check(last.stopped.await(2,TimeUnit.SECONDS),"service destruction ends stream");c.await("WAITING");volume.close();
    check(c.unregister==1&&c.receiver==null&&Adb.live.get()==0&&Adb.max.get()==1,"receiver released once, no parallel sessions");check(c.assets.reads==1,"asset read/encoding reused for reconnects");
   }finally{volume.close();try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
  }
  System.out.println("PASS: actual Android adapter on SDK28/33, default-off, private receiver, bundled asset transfer/cache, fragmented readiness, screen/setting/service stop, reconnect and single-session ownership");
 }
}'''}
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.BindingHarness'],check=True,timeout=15)

import xml.etree.ElementTree as ET
manifest=ET.parse('app/AndroidManifest.xml').getroot()
a='{http://schemas.android.com/apk/res/android}'
assert any(p.get(a+'name')=='io.tvos.permission.VOLUME_CONTROL' and p.get(a+'protectionLevel')=='signature' for p in manifest.findall('permission'))
assert any(p.get(a+'name')=='io.tvos.permission.VOLUME_CONTROL' for p in manifest.findall('uses-permission'))
