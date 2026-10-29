"""Actual coalescing scheduler and explicit Seena broadcast, including shell fallback."""
from pathlib import Path
import tempfile,subprocess
files={
'android/content/Context.java':'package android.content;public class Context {public int sends;public boolean denied;public Context getApplicationContext(){return this;}public void sendBroadcast(Intent i){if(!i.action.equals("android.media.tv.action.INITIALIZE_PROGRAMS")||!i.component.pkg.equals("com.seena.app"))throw new AssertionError("scoped request");sends++;if(denied)throw new SecurityException();}}',
'android/content/Intent.java':'package android.content;public class Intent {public String action;public ComponentName component;public Intent(String a){action=a;}public Intent setComponent(ComponentName c){component=c;return this;}}',
'android/content/ComponentName.java':'package android.content;public class ComponentName {public String pkg;public ComponentName(String p,String c){pkg=p;if(!c.equals("com.seena.app.tv.TvChannelInitializerReceiver"))throw new AssertionError("receiver");}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long time;public static long elapsedRealtime(){return time;}}',
'android/util/Log.java':'package android.util;public class Log {public static int i(String t,String m){return 0;}public static int w(String t,String m){return 0;}}',
'io/tvos/Adb.java':'''package io.tvos;class Adb implements AutoCloseable {static int calls,closes;static String response="Broadcast completed: result=0";static Adb connect(Object c,int timeout,Object listener){calls++;return new Adb();}String shell(String command){if(!command.equals("am broadcast -n com.seena.app/com.seena.app.tv.TvChannelInitializerReceiver -a android.media.tv.action.INITIALIZE_PROGRAMS"))throw new AssertionError("command");return response;}public void close(){closes++;}}''',
'io/tvos/SeenaChannelRefresh.java':Path('app/src/io/tvos/SeenaChannelRefresh.java').read_text(),
'io/tvos/RefreshHarness.java':'''package io.tvos;import android.content.Context;import android.os.SystemClock;import java.util.concurrent.*;public class RefreshHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static class Scheduler extends ScheduledThreadPoolExecutor {Runnable task;long delay;int count;Scheduler(){super(1);}public ScheduledFuture<?> schedule(Runnable r,long d,TimeUnit u){check(task==null,"one pending task");task=r;delay=u.toMillis(d);count++;return null;}void run(){Runnable r=task;task=null;r.run();}}
 public static void main(String[] args)throws Exception {
 Scheduler s=new Scheduler();SeenaChannelRefresh refresh=new SeenaChannelRefresh(s);Context c=new Context();
 refresh.enqueue(c);check(s.delay==0,"first resume requests immediately");refresh.enqueue(c);check(s.count==1,"concurrent resumes coalesced");s.run();check(c.sends==1&&Adb.calls==0,"ordinary explicit broadcast preferred");
 SystemClock.time=10000;refresh.enqueue(c);check(s.delay==80000,"cooldown defers rather than drops latest request");refresh.enqueue(c);check(s.count==2,"queued request never postponed by more resumes");SystemClock.time=90000;s.run();check(c.sends==2,"deferred request delivered");
 c.denied=true;SeenaChannelRefresh.send(c);check(Adb.calls==1&&Adb.closes==1,"protected action falls back to tested shell command and closes connection");
 Adb.response="SecurityException";try{SeenaChannelRefresh.send(c);throw new AssertionError("failure expected");}catch(java.io.IOException expected){}check(Adb.closes==2,"failed shell connection closed");
 check(!SeenaChannelRefresh.accepted("Permission Denial Broadcast completed")&&!SeenaChannelRefresh.accepted(null),"failed broadcasts rejected");s.shutdownNow();System.out.println("PASS: Seena explicit receiver, app/shell routes, coalescing, deferred cooldown and failure cleanup");
 }}'''}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.RefreshHarness'],check=True,timeout=20)
