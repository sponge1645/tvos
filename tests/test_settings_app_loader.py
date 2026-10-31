"""Run installed-app lookup against firmware failures, cancellation and queued stale delivery."""
from pathlib import Path
import subprocess,tempfile
files={
'android/content/Intent.java':'''package android.content; public class Intent {public static final String ACTION_MAIN="main",CATEGORY_LEANBACK_LAUNCHER="tv";public Intent(String s){}public Intent addCategory(String s){return this;}}''',
'android/content/Context.java':'''package android.content;import android.content.pm.PackageManager;public class Context {public final PackageManager pm=new PackageManager();public PackageManager getPackageManager(){return pm;}public String getPackageName(){return "io.tvos";}}''',
'android/content/pm/ResolveInfo.java':'''package android.content.pm;public class ResolveInfo {public static class ActivityInfo {public String packageName;}public ActivityInfo activityInfo;public String label;public boolean fail;public ResolveInfo(String p,String l){activityInfo=new ActivityInfo();activityInfo.packageName=p;label=l;}public CharSequence loadLabel(PackageManager pm){if(fail)throw new SecurityException();return label;}}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;import java.util.*;import java.util.concurrent.*;import android.content.Intent;public class PackageManager {public List<ResolveInfo> rows=new ArrayList<>();public volatile boolean fail;public volatile CountDownLatch gate,entered;public List<ResolveInfo> queryIntentActivities(Intent i,int flags){if(Thread.currentThread().getName().equals("main"))throw new AssertionError("UI lookup");CountDownLatch e=entered,g=gate;if(e!=null)e.countDown();if(g!=null)try{g.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}if(fail)throw new SecurityException();return rows;}}''',
'android/os/Handler.java':'''package android.os;import java.util.concurrent.*;public class Handler {public final BlockingQueue<Runnable> tasks=new LinkedBlockingQueue<>();public boolean post(Runnable r){tasks.add(r);return true;}public void next()throws Exception{Runnable r=tasks.poll(3,TimeUnit.SECONDS);if(r==null)throw new AssertionError("no delivery");r.run();}}''',
'io/tvos/AppLog.java':'''package io.tvos;class AppLog {static void unexpected(String s,RuntimeException e){}}''',
'io/tvos/LoaderTest.java':'''package io.tvos;
import java.util.*;import java.util.concurrent.*;import android.content.*;import android.content.pm.*;import android.os.*;
public class LoaderTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  java.lang.reflect.Field field=SettingsAppLoader.class.getDeclaredField("IO");field.setAccessible(true);ThreadPoolExecutor io=(ThreadPoolExecutor)field.get(null);
  try{
   Context c=new Context();Handler h=new Handler();boolean[] active={true};SettingsAppLoader loader=new SettingsAppLoader(c,h,()->active[0]);
   ResolveInfo bad=new ResolveInfo("bad","Bad");bad.fail=true;ResolveInfo noActivity=new ResolveInfo("missing","Missing");noActivity.activityInfo=null;
   c.pm.rows=Arrays.asList(new ResolveInfo("z","Zulu"),new ResolveInfo("a","Alpha"),new ResolveInfo("a","Duplicate"),new ResolveInfo("io.tvos","Self"),bad,null,noActivity);
   List<List<String[]>> deliveries=new ArrayList<>();loader.load(deliveries::add);h.next();
   check(deliveries.size()==1&&deliveries.get(0).size()==2,"dedup/self/null/bad label policy");check(deliveries.get(0).get(0)[0].equals("a"),"alphabetical sorting");
   // Complete the worker before pausing; already-posted UI results must still be rejected.
   loader.load(deliveries::add);Runnable stale=h.tasks.poll(3,TimeUnit.SECONDS);check(stale!=null,"worker posted");loader.cancel();stale.run();check(deliveries.size()==1,"cancel rejects already-posted delivery");
   c.pm.gate=new CountDownLatch(1);c.pm.entered=new CountDownLatch(1);loader.load(deliveries::add);check(c.pm.entered.await(3,TimeUnit.SECONDS),"first lookup entered");
   loader.load(deliveries::add);loader.load(deliveries::add);check(io.getQueue().size()==1,"replaced queued work removed");c.pm.gate.countDown();h.next();check(deliveries.size()==2,"only latest request delivered");
   c.pm.gate=c.pm.entered=null;loader.load(deliveries::add);stale=h.tasks.poll(3,TimeUnit.SECONDS);active[0]=false;stale.run();check(deliveries.size()==2,"hidden view rejects delivery");
   loader.load(deliveries::add);check(h.tasks.isEmpty(),"hidden view starts no work");active[0]=true;c.pm.fail=true;loader.load(deliveries::add);h.next();check(deliveries.get(2).isEmpty(),"firmware query denial delivers safe empty list");
   System.out.println("PASS: worker-only package queries, sorting, deduplication, malformed rows, label/query failures, pause and replacement cancellation");
  }finally{io.shutdownNow();}
 }
}'''}
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name in ['UiRequest','SettingsAppLoader']:files['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.LoaderTest'],check=True,timeout=15)
