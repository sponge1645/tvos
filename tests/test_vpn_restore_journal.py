"""Durable VPN intent survives a terminated JVM; restore is serialized and failure-safe."""
from pathlib import Path
import subprocess, tempfile
source=Path('app/src/io/tvos/network/VpnRestoreJournal.java').read_text()
harness='''import io.tvos.network.VpnRestoreJournal;import java.io.*;import java.util.concurrent.atomic.AtomicInteger;
public class JournalHarness {
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 public static void main(String[] args)throws Exception {
 File dir=new File(args[1]);VpnRestoreJournal journal=new VpnRestoreJournal(dir);
 if(args[0].equals("attemptCrash")){journal.restore(()->Runtime.getRuntime().halt(0));throw new AssertionError();}
 if(args[0].equals("attemptFail")){try{journal.restore(()->{throw new IOException("offline");});throw new AssertionError();}catch(VpnRestoreJournal.RestoreFailure expected){return;}}
 if(args[0].equals("crash")){journal.pause(()->{},()->{if(!journal.pending())throw new IOException("not durable");});Runtime.getRuntime().halt(0);}
 Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"JournalHarness","crash",dir.toString()).inheritIO().start();check(child.waitFor()==0&&journal.pending(),"intent survives process termination");
 try{journal.restore(()->{throw new IOException("offline");});throw new AssertionError();}catch(IOException expected){}check(journal.pending(),"failed restore retains durable intent");
 AtomicInteger restores=new AtomicInteger();Thread a=new Thread(()->{try{journal.restore(()->restores.incrementAndGet());}catch(IOException e){throw new RuntimeException(e);}}),b=new Thread(()->{try{new VpnRestoreJournal(dir).restore(()->restores.incrementAndGet());}catch(IOException e){throw new RuntimeException(e);}});a.start();b.start();a.join();b.join();check(restores.get()==1&&!journal.pending(),"simultaneous recovery attempts reconnect once");
 try{journal.pause(()->{throw new IOException("scheduler denied");},()->{throw new AssertionError("must not disconnect");});throw new AssertionError();}catch(IOException expected){}check(!journal.pending(),"scheduler refusal cannot disconnect VPN");
 journal.pause(()->{},()->{});try{new VpnRestoreJournal(dir).pause(()->{},()->{throw new AssertionError();});throw new AssertionError();}catch(IOException expected){}check(journal.pending(),"second pause cannot overwrite pending recovery");
 journal.restore(()->{});check(!journal.restore(()->{throw new AssertionError();}),"completed recovery is idempotent");
 journal.pause(()->{},()->{});
 for(int i=1;i<=5;i++){
  Process failure=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"JournalHarness","attemptFail",dir.toString()).inheritIO().start();
  check(failure.waitFor()==0,"failed attempt child completes");check(journal.pending()==(i<5),"attempt budget survives separate JVMs");
 }
 journal.pause(()->{},()->{});
 for(int i=0;i<5;i++){
  Process crash=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"JournalHarness","attemptCrash",dir.toString()).inheritIO().start();
  check(crash.waitFor()==0&&journal.pending(),"started attempt survives abrupt termination");
 }
 try{journal.restore(()->{throw new AssertionError("sixth attempt forbidden");});throw new AssertionError();}
 catch(VpnRestoreJournal.RestoreFailure expected){check(expected.exhausted&&!journal.pending(),"exhausted crash budget cleared without reconnecting");}
 journal.pause(()->{},()->{});AtomicInteger calls=new AtomicInteger(),terminal=new AtomicInteger();Thread[] failures=new Thread[10];
 for(int i=0;i<failures.length;i++){failures[i]=new Thread(()->{try{new VpnRestoreJournal(dir).restore(()->{calls.incrementAndGet();throw new IOException("offline");});}catch(VpnRestoreJournal.RestoreFailure e){if(e.exhausted)terminal.incrementAndGet();}catch(IOException e){throw new RuntimeException(e);}});failures[i].start();}
 for(Thread failure:failures)failure.join();
 check(calls.get()==5&&terminal.get()==1&&!journal.pending(),"concurrent failures share budget and only one terminal result");
 System.out.println("PASS: legacy marker, persistent budget across JVMs/crashes, bounded concurrent recovery, scheduling refusal and idempotent cleanup");
 }}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d);(root/'VpnRestoreJournal.java').write_text(source);(root/'JournalHarness.java').write_text(harness);(root/'state').mkdir()
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',d,str(root/'VpnRestoreJournal.java'),str(root/'JournalHarness.java')],check=True)
 subprocess.run(['java','-cp',d,'JournalHarness','parent',str(root/'state')],check=True,timeout=20)
