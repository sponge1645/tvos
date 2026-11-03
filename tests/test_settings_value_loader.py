"""Run the real metadata cache with deterministic worker and UI queues."""
from pathlib import Path
import subprocess,tempfile
harness='''package io.tvos;import java.util.*;import java.util.concurrent.*;
public class Test {
 static class Worker extends ThreadPoolExecutor {
  final List<Runnable> work=new ArrayList<>();boolean reject;int submissions;
  Worker(){super(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<Runnable>());}
  @Override public void execute(Runnable task){submissions++;if(reject)throw new RejectedExecutionException();work.add(task);}
  @Override public boolean remove(Runnable task){return work.remove(task);}
  void run(){while(!work.isEmpty())work.remove(0).run();}
 }
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  Worker worker=new Worker();List<Runnable> ui=new ArrayList<>();boolean[] active={true};int[] reads={0},updates={0};
  SettingsValueLoader<String> cache=new SettingsValueLoader<>(worker,ui::add,()->active[0],()->updates[0]++);
  for(int i=0;i<1000;i++)check(cache.get("vpn:ru:app","…",()->{reads[0]++;return "VPN app";}).equals("…"),"drawing returns immediately");
  check(reads[0]==0&&worker.work.size()==1,"one background lookup per key, no platform reads on draw");worker.run();check(reads[0]==1&&updates[0]==0,"worker cannot update UI directly");ui.remove(0).run();
  check(cache.get("vpn:ru:app","…",()->{throw new AssertionError();}).equals("VPN app")&&updates[0]==1,"completed label reused across frames");
  cache.get("tv","…",()->"TV");worker.run();cache.clear();ui.remove(0).run();check(updates[0]==1,"cleared view cannot receive stale delivery");
  cache.get("queued","…",()->{throw new AssertionError();});cache.clear();check(worker.work.isEmpty(),"pause removes queued reads");active[0]=false;cache.get("hidden","…",()->{throw new AssertionError();});check(worker.work.isEmpty(),"hidden screen never submits work");active[0]=true;
  for(int i=0;i<40;i++){final int id=i;cache.get("key"+i,"…",()->"value"+id);}check(worker.work.size()==16,"cache and pending reads are bounded");cache.clear();
  worker.reject=true;int submissions=worker.submissions;for(int i=0;i<1000;i++)cache.get("full","…",()->"unused");check(worker.submissions==submissions+1,"overload does not retry per animation frame");worker.reject=false;cache.clear();
  cache.get("error","fallback",()->{throw new SecurityException();});worker.run();ui.remove(0).run();check(cache.get("error","other",()->{throw new AssertionError();}).equals("fallback"),"platform failure becomes a stable fallback");
  SettingsValueLoader<String[]> device=new SettingsValueLoader<>(worker,ui::add,()->true,()->updates[0]++);
  String[] fallback={"—"};device.get("about",fallback,()->new String[]{"TV"});worker.run();ui.remove(0).run();check(device.get("about",fallback,()->{throw new AssertionError();})[0].equals("TV"),"typed device snapshot is cached");
  worker.shutdownNow();System.out.println("PASS: background-only reads, deduplicated animation frames, UI delivery, stale/pause cancellation, bounded cache/queue and failure fallbacks");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);p=root/'io/tvos';p.mkdir(parents=True)
 for name in ['SettingsValueLoader','UiRequest']:(p/(name+'.java')).write_text(Path('app/src/io/tvos/'+name+'.java').read_text())
 (p/'Test.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.Test'],check=True)
