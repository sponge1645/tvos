# Run from project root with Python 3 and JDK 17+.
# Exercises production query methods and UiRequest with deterministic network/UI stubs.
from pathlib import Path
import os,subprocess,tempfile
s=Path('app/src/io/tvos/Recs.java').read_text()
def method(signature):
 start=s.index(signature);brace=s.index('{',start);depth=1;i=brace+1
 while depth:
  if s[i]=='{':depth+=1
  elif s[i]=='}':depth-=1
  i+=1
 return s[start:i]
header='''package io.tvos;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.net.URLEncoder;
public class AsyncHarness {
 static class Pick {int id; String kind,title,originalTitle,poster,backdrop,movieOrigin,tvOrigin;double rating;}
 static class JSONArray {int length(){return 0;} JSONObject optJSONObject(int i){return null;} }
 static class JSONObject {
  JSONArray optJSONArray(String s){return new JSONArray();}
  String optString(String s){return "";} String optString(String s,String d){return d;}
  int optInt(String s){return 0;} int optInt(String s,int d){return d;}
  double optDouble(String s,double d){return d;}
 }
 static class Main {
  final List<Runnable> queue=Collections.synchronizedList(new ArrayList<>());
  void post(Runnable r){queue.add(r);} void drain(){while(!queue.isEmpty())queue.remove(0).run();}
 }
 final ThreadPoolExecutor requestIo=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
 final Main main=new Main();final AtomicInteger calls=new AtomicInteger(); volatile Runnable onGet;
 JSONObject get(String s){calls.incrementAndGet();Runnable hook=onGet;if(hook!=null)hook.run();return new JSONObject();}
 String lang(){return "en-US";} void add(List<Pick> list,JSONObject item,String kind){}
 void flush()throws Exception{requestIo.submit(()->{}).get(3,TimeUnit.SECONDS);}
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 static final class Gate {
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  Gate(ThreadPoolExecutor executor)throws Exception {
   executor.execute(()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   if(!entered.await(3,TimeUnit.SECONDS))throw new AssertionError("worker did not start");
  }
 }
'''
tests='''
 public static void main(String[] args)throws Exception{
  AsyncHarness h=new AsyncHarness();AtomicInteger delivered=new AtomicInteger();
  try{
   Gate gate=new Gate(h.requestIo);
   for(int i=0;i<100;i++){UiRequest old=h.search("obsolete",r->delivered.incrementAndGet());old.cancel();}
   check(h.requestIo.getQueue().isEmpty(),"cancelled queries removed from queue");
   UiRequest live=h.search("current",r->delivered.incrementAndGet());gate.release.countDown();h.flush();
   check(h.calls.get()>0,"latest query runs");h.main.drain();check(delivered.get()==1,"only latest query delivered");
   int baseline=h.calls.get();delivered.set(0);
   UiRequest late=h.search("late",r->delivered.incrementAndGet());h.flush();late.cancel();h.main.drain();
   check(delivered.get()==0,"cancel after completion suppresses queued callback");
   gate=new Gate(h.requestIo);AtomicReference<UiRequest> running=new AtomicReference<>();
   UiRequest active=h.search("cancel-during-get",r->delivered.incrementAndGet());running.set(active);
   h.onGet=()->running.get().cancel();baseline=h.calls.get();gate.release.countDown();h.flush();h.main.drain();
   check(h.calls.get()==baseline+1 && delivered.get()==0,"in-flight cancellation skips remaining requests and delivery");
   h.onGet=null;gate=new Gate(h.requestIo);
   UiRequest roulette=h.loadAcclaimed(r->delivered.incrementAndGet());running.set(roulette);
   h.onGet=()->running.get().cancel();baseline=h.calls.get();gate.release.countDown();h.flush();h.main.drain();
   check(h.calls.get()==baseline+1 && delivered.get()==0,"roulette cancellation skips more pages and callback");
   h.onGet=null;
   UiRequest beforeStart=new UiRequest(h.requestIo);beforeStart.cancel();baseline=h.calls.get();
   beforeStart.start(()->h.calls.incrementAndGet());h.flush();check(h.calls.get()==baseline,"cancel before start");
   System.out.println("PASS: cancelled queue removal, latest query, delivery guard, in-flight search/roulette cancellation, cancel-before-start");
  }finally{h.requestIo.shutdownNow();}
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-background-test-') as directory:
    root=Path(directory)
    (root/'AsyncHarness.java').write_text(header+method('    UiRequest loadAcclaimed(')+method('    UiRequest search(')+tests)
    (root/'Log.java').write_text('package android.util; public class Log {public static int w(String t,String s,Throwable e){return 0;}}')
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java,'com.sun.tools.javac.Main','-d',directory,'app/src/io/tvos/UiRequest.java',str(root/'AsyncHarness.java'),str(root/'Log.java')],check=True)
    subprocess.run([java,'-cp',directory,'io.tvos.AsyncHarness'],check=True)
