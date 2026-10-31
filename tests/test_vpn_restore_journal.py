"""Durable VPN intent survives a terminated JVM; restore is serialized and failure-safe."""
from pathlib import Path
import subprocess, tempfile
source=Path('app/src/io/tvos/network/VpnRestoreJournal.java').read_text()
harness='''import io.tvos.network.VpnRestoreJournal;import java.io.*;import java.util.concurrent.atomic.AtomicInteger;
public class JournalHarness {
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 public static void main(String[] args)throws Exception {
 File dir=new File(args[1]);VpnRestoreJournal journal=new VpnRestoreJournal(dir);
 if(args[0].equals("crash")){journal.pause(()->{},()->{if(!journal.pending())throw new IOException("not durable");});Runtime.getRuntime().halt(0);}
 Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"JournalHarness","crash",dir.toString()).inheritIO().start();check(child.waitFor()==0&&journal.pending(),"intent survives process termination");
 try{journal.restore(()->{throw new IOException("offline");});throw new AssertionError();}catch(IOException expected){}check(journal.pending(),"failed restore retains durable intent");
 AtomicInteger restores=new AtomicInteger();Thread a=new Thread(()->{try{journal.restore(()->restores.incrementAndGet());}catch(IOException e){throw new RuntimeException(e);}}),b=new Thread(()->{try{new VpnRestoreJournal(dir).restore(()->restores.incrementAndGet());}catch(IOException e){throw new RuntimeException(e);}});a.start();b.start();a.join();b.join();check(restores.get()==1&&!journal.pending(),"simultaneous recovery attempts reconnect once");
 try{journal.pause(()->{throw new IOException("scheduler denied");},()->{throw new AssertionError("must not disconnect");});throw new AssertionError();}catch(IOException expected){}check(!journal.pending(),"scheduler refusal cannot disconnect VPN");
 journal.pause(()->{},()->{});try{new VpnRestoreJournal(dir).pause(()->{},()->{throw new AssertionError();});throw new AssertionError();}catch(IOException expected){}check(journal.pending(),"second pause cannot overwrite pending recovery");
 journal.restore(()->{});check(!journal.restore(()->{throw new AssertionError();}),"completed recovery is idempotent");
 System.out.println("PASS: VPN recovery after process death, failed scheduling/restoration and concurrent recovery");
 }}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d);(root/'VpnRestoreJournal.java').write_text(source);(root/'JournalHarness.java').write_text(harness);(root/'state').mkdir()
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',d,str(root/'VpnRestoreJournal.java'),str(root/'JournalHarness.java')],check=True)
 subprocess.run(['java','-cp',d,'JournalHarness','parent',str(root/'state')],check=True,timeout=20)
