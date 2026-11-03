"""Execute actual wizard request scheduling/cancellation, including stale delivery and queue removal."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/SetupActivity.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);depth=1;i=b+1
 while depth:depth+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
body='\n'.join(method(s) for s in ['    private void cancelWork()', '    private void task('])
harness='''package io.tvos;import java.util.*;import java.util.concurrent.*;
public class SetupWorkTest {
 final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<Runnable>());UiRequest request;int epoch,errors;volatile boolean active=true;String regionStatus="";
 static class R {static class string {static final int settings_setup_lookup_failed=1;}}
 String text(int id){return "lookup failed";}void updateRegionText(){errors++;}
 static class Main {final Queue<Runnable> jobs=new ConcurrentLinkedQueue<>();void post(Runnable r){jobs.add(r);}void drain(){Runnable r;while((r=jobs.poll())!=null)r.run();}}
 final Main main=new Main();
'''+body+'''
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {SetupWorkTest w=new SetupWorkTest();List<String> results=new ArrayList<>();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
 try {w.task(()->{started.countDown();release.await();return ()->results.add("stale");});check(started.await(3,TimeUnit.SECONDS),"first lookup starts");w.task(()->()->results.add("discarded queued"));w.task(()->()->results.add("latest"));check(w.io.getQueue().size()==1,"superseded queued request removed");release.countDown();w.io.submit(()->{}).get(3,TimeUnit.SECONDS);w.main.drain();check(results.equals(Arrays.asList("latest")),"only current page/request is delivered");
 w.task(()->()->results.add("late UI"));w.io.submit(()->{}).get(3,TimeUnit.SECONDS);w.cancelWork();w.main.drain();check(results.size()==1,"navigation cancels already posted delivery");
 w.task(()->{throw new java.io.IOException();});w.io.submit(()->{}).get(3,TimeUnit.SECONDS);w.main.drain();check(w.errors==1&&w.regionStatus.equals("lookup failed"),"lookup failure remains recoverable in UI");
 w.task(()->()->results.add("paused"));w.io.submit(()->{}).get(3,TimeUnit.SECONDS);w.active=false;w.main.drain();check(results.size()==1,"paused wizard never shows late dialog");
 }finally{release.countDown();w.cancelWork();w.io.shutdownNow();check(w.io.awaitTermination(3,TimeUnit.SECONDS),"worker released");}
 System.out.println("PASS: actual wizard async methods, bounded latest queue, stale network/UI rejection, recoverable errors, pause and worker teardown");}
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);p=root/'io/tvos/SetupWorkTest.java';p.parent.mkdir(parents=True);p.write_text(harness)
 other=root/'io/tvos/UiRequest.java';other.write_text(Path('app/src/io/tvos/UiRequest.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',tmp,str(p),str(other)],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SetupWorkTest'],check=True,timeout=15)
