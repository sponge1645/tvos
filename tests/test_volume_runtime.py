"""Execute actual cross-process storage and serialized connection/retry state without Android."""
from pathlib import Path
import subprocess
import tempfile

harness=r'''import io.tvos.remote.*;import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.function.*;
public class VolumeRuntimeHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static final class Work implements ScheduledFuture<Object> {
  final Runnable run;final long due;boolean cancelled;Work(Runnable r,long d){run=r;due=d;}
  public boolean cancel(boolean interrupt){cancelled=true;return true;}public boolean isCancelled(){return cancelled;}public boolean isDone(){return cancelled;}
  public Object get(){throw new UnsupportedOperationException();}public Object get(long t,TimeUnit u){throw new UnsupportedOperationException();}
  public long getDelay(TimeUnit u){return u.convert(due,TimeUnit.MILLISECONDS);}public int compareTo(Delayed d){return Long.compare(due,((Work)d).due);}
 }
 static final class QueueExecutor extends AbstractExecutorService implements ScheduledExecutorService {
  long now;boolean closed;final PriorityQueue<Work> queue=new PriorityQueue<>();
  public ScheduledFuture<?> schedule(Runnable r,long d,TimeUnit u){Work w=new Work(r,now+u.toMillis(d));queue.add(w);return w;}
  public <V>ScheduledFuture<V> schedule(Callable<V> c,long d,TimeUnit u){throw new UnsupportedOperationException();}
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable r,long a,long b,TimeUnit u){throw new UnsupportedOperationException();}
  public void execute(Runnable r){schedule(r,0,TimeUnit.MILLISECONDS);}public void shutdown(){closed=true;}
  public List<Runnable> shutdownNow(){closed=true;queue.clear();return Collections.emptyList();}public boolean isShutdown(){return closed;}public boolean isTerminated(){return closed;}public boolean awaitTermination(long t,TimeUnit u){return closed;}
  void next(){Work w=queue.remove();now=Math.max(now,w.due);if(!w.cancelled)w.run.run();}
  void drain(){for(int i=0;!queue.isEmpty();i++){check(i<30,"unbounded retries");next();}}
 }
 static final class Link implements VolumeHoldRunner.Connection {
  int closes;Runnable body;Runnable ready;BooleanSupplier active;
  public void run(BooleanSupplier a,Runnable r)throws IOException {active=a;ready=r;if(body!=null)body.run();else throw new IOException("closed");}
  public void close(){closes++;}
 }
 public static void main(String[] args)throws Exception {
  Path directory=Files.createTempDirectory("volume-store-");File root=directory.toFile();
  try {
   check(!VolumeHoldStore.enabled(root)&&VolumeHoldStore.status(root).isEmpty(),"missing defaults");
   VolumeHoldStore.setEnabled(root,true);check(VolumeHoldStore.enabled(root),"cross-instance persisted true");
   VolumeHoldStore.setEnabled(root,false);check(!VolumeHoldStore.enabled(root),"persisted false");
   for(String s:new String[]{"OFF","WAITING","STARTING","READY","ERROR"}){VolumeHoldStore.setStatus(root,s);check(VolumeHoldStore.status(root).equals(s),"status persistence");}
   boolean invalid=false;try{VolumeHoldStore.setStatus(root,"bogus");}catch(IllegalArgumentException e){invalid=true;}check(invalid&&VolumeHoldStore.status(root).equals("ERROR"),"invalid state never replaces file");
   try(java.util.stream.Stream<Path> entries=Files.list(directory)){check(entries.count()==2,"no atomic temporary files retained");}
   Files.delete(directory.resolve("volume-hold.enabled"));Files.createDirectory(directory.resolve("volume-hold.enabled"));Files.write(directory.resolve("volume-hold.enabled/child"),new byte[]{1});
   boolean failed=false;try{VolumeHoldStore.setEnabled(root,true);}catch(IOException e){failed=true;}check(failed&&!VolumeHoldStore.enabled(root),"failed commit keeps old state");
  } finally {try(java.util.stream.Stream<Path> paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
  QueueExecutor q=new QueueExecutor();List<String> status=new ArrayList<>();AtomicInteger opens=new AtomicInteger();
  VolumeHoldRunner runner=new VolumeHoldRunner(()->{opens.incrementAndGet();throw new IOException("offline");},status::add,q,()->q.now);
  runner.update(false);q.drain();check(opens.get()==0&&status.equals(Arrays.asList("OFF")),"off opens nothing");
  runner.update(true);q.drain();check(opens.get()==5&&status.get(status.size()-1).equals("ERROR")&&q.now==18000,"exact retry burst and backoff");
  runner.update(true);q.next();check(opens.get()==6,"explicit restart resets burst");runner.update(false);q.drain();check(opens.get()==6&&status.get(status.size()-1).equals("OFF"),"disable cancels delayed retry");runner.close();runner.close();runner.update(true);check(q.queue.isEmpty(),"closed runner stays closed");
  for(boolean healthy:new boolean[]{false,true}){
   QueueExecutor ex=new QueueExecutor();AtomicInteger n=new AtomicInteger();List<String> states=new ArrayList<>();
   VolumeHoldRunner r=new VolumeHoldRunner(()->{int at=n.incrementAndGet();if(at!=5)throw new IOException();Link l=new Link();l.body=()->{l.ready.run();ex.now+=healthy?30000:1000;};return l;},states::add,ex,()->ex.now);
   r.update(true);ex.drain();check(n.get()==(healthy?10:5),"only a stable 30-second session resets retry budget");r.close();
  }
  QueueExecutor ex=new QueueExecutor();Link l=new Link();VolumeHoldRunner[] r=new VolumeHoldRunner[1];List<String> states=new ArrayList<>();
  r[0]=new VolumeHoldRunner(()->l,states::add,ex,()->ex.now);l.body=()->{r[0].update(false);l.ready.run();};r[0].update(true);ex.drain();
  check(l.closes>=1&&!states.contains("READY")&&states.get(states.size()-1).equals("OFF")&&!l.active.getAsBoolean(),"cancelled generation cannot publish ready or reconnect");r[0].close();
  QueueExecutor opening=new QueueExecutor();Link stale=new Link();VolumeHoldRunner[] pending=new VolumeHoldRunner[1];
  pending[0]=new VolumeHoldRunner(()->{pending[0].update(false);return stale;},s->{},opening,()->opening.now);pending[0].update(true);opening.drain();check(stale.closes==1&&stale.active==null,"cancellation during connect closes without starting");pending[0].close();
  CountDownLatch entered=new CountDownLatch(1),closed=new CountDownLatch(1),off=new CountDownLatch(1);AtomicInteger actualOpens=new AtomicInteger();
  VolumeHoldRunner threaded=new VolumeHoldRunner(()->{actualOpens.incrementAndGet();return new VolumeHoldRunner.Connection(){public void run(BooleanSupplier active,Runnable ready)throws IOException {ready.run();entered.countDown();try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IOException();}public void close(){closed.countDown();}};},s->{if(s.equals("OFF"))off.countDown();});
  threaded.update(true);check(entered.await(2,TimeUnit.SECONDS),"connection running");threaded.update(false);check(off.await(2,TimeUnit.SECONDS)&&actualOpens.get()==1,"close unblocks worker and cancellation never retries");threaded.close();
  System.out.println("PASS: actual volume storage, atomic failures, default-off, bounded reconnect/backoff, stable-session reset, cancellation during connect/run, stale readiness and shutdown");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    root=Path(directory)
    files={name:Path('app/src/io/tvos/remote/'+name).read_text() for name in ['VolumeHoldStore.java','VolumeHoldRunner.java']}
    for name,content in files.items():(root/name).write_text(content)
    (root/'VolumeRuntimeHarness.java').write_text(harness)
    subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,*map(str,root.glob('*.java'))],check=True)
    subprocess.run(['java','-cp',directory,'VolumeRuntimeHarness'],check=True,timeout=10)
