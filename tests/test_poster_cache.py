# Run from project root with Python 3 and JDK 17+.
# Compiles the current production poster method with deterministic Android stubs.
from pathlib import Path
import os, shutil, subprocess, tempfile
s=Path('app/src/io/tvos/Recs.java').read_text()
def method(signature):
 start=s.index(signature);brace=s.index('{',start);depth=1;i=brace+1
 while depth:
  if s[i]=='{':depth+=1
  elif s[i]=='}':depth-=1
  i+=1
 return s[start:i]
fields=s[s.index('    private final Map<String, Bitmap> posters'):s.index('    private final ExecutorService durationIo')]
fields=fields.replace('private ', '')
header='''import java.io.*;
import java.util.*;
import java.nio.file.*;

public class PosterHarness {
 static class Bitmap { int getAllocationByteCount(){return 1024*1024;} }
 static class BitmapFactory {
  static Runnable afterDecode;
  static class Options {int inSampleSize;}
  static Bitmap decodeFile(String p,Options o){if(new File(p).getName().startsWith("oom"))throw new OutOfMemoryError("test decode");if(afterDecode!=null)afterDecode.run();return new File(p).getName().startsWith("bad")?null:new Bitmap();}
 }
 static class SystemClock {static long now;static long elapsedRealtime(){return now;} }
 static class AppLog {static void w(String s){} }
 static class Pick {String poster;Pick(String p){poster=p;} }
 static class Context {File dir;Context(File d){dir=d;} File getCacheDir(){return dir;} }
 static class Worker {ArrayDeque<Runnable> queue=new ArrayDeque<>();void execute(Runnable r){queue.add(r);}void drain(){while(!queue.isEmpty())queue.remove().run();} }
 Context context;Worker posterIo=new Worker();int notices;
 PosterHarness(File d){context=new Context(d);}
 void notifyListeners(){notices++;}
'''
# The harness compiles the exact production method and fields, replacing Android I/O dependencies only.
header+='import_placeholder'
header=header.replace('import_placeholder','')
body=fields+'\n'+method('    void setPosterWindow(')+'\n'+method('    private boolean posterWanted(')+'\n'+method('    Bitmap poster(Pick p)')+'\n'+method('    private static void prunePosterCache(File dir)')
body=body.replace('HttpURLConnection','java.net.HttpURLConnection').replace('new URL(', 'new java.net.URL(')
tests='''
 void fixture(String path)throws Exception{
  File dir=new File(context.getCacheDir(),"tmdb-posters");dir.mkdirs();
  Files.write(new File(dir,path.replace("/", "")).toPath(),new byte[]{1});
 }
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  PosterHarness h=new PosterHarness(Files.createTempDirectory("poster-test").toFile());
  for(int i=0;i<100;i++){String p="/p"+i;h.fixture(p);h.poster(new Pick(p));}
  check(h.posterPending.size()==24,"pending queue cap");
  h.poster(new Pick("/p0"));check(h.posterIo.queue.size()==24,"deduplication");
  h.posterIo.drain();
  check(h.posters.size()==12 && h.posterRamBytes==12L*1024*1024,"RAM LRU cap");
  check(!h.posters.containsKey("/p0") && h.posters.containsKey("/p23"),"LRU order");
  check(h.posterPending.isEmpty() && h.notices==24,"completion cleanup");
  h.poster(new Pick("/p23"));check(h.posterIo.queue.isEmpty(),"RAM hit does not enqueue");
  h.poster(new Pick("/p0"));h.posterIo.drain();check(h.posters.containsKey("/p0"),"evicted poster reloads from disk");
  h.fixture("/bad1");h.poster(new Pick("/bad1"));h.posterIo.drain();
  check(h.posterFailures.containsKey("/bad1") && h.posterPending.isEmpty(),"failed decode records retry deadline");
  for(int i=0;i<100;i++)h.poster(new Pick("/bad1"));
  check(h.posterIo.queue.isEmpty(),"draw requests do not retry immediately");
  SystemClock.now=29999;h.poster(new Pick("/bad1"));check(h.posterIo.queue.isEmpty(),"backoff before deadline");
  SystemClock.now=30000;h.fixture("/bad1");h.poster(new Pick("/bad1"));check(h.posterIo.queue.size()==1,"retry at deadline");h.posterIo.drain();
  for(int i=0;i<140;i++){String p="/bad-history"+i;h.fixture(p);h.poster(new Pick(p));h.posterIo.drain();}
  check(h.posterFailures.size()==128,"failure history bound");
  h.fixture("/oom");h.poster(new Pick("/oom"));h.posterIo.drain();
  check(h.posterFailures.containsKey("/oom") && h.posterPending.isEmpty(),"OOM backoff and pending cleanup");
  h.setPosterWindow(Collections.singletonList(new Pick("/p23")));
  h.poster(new Pick("/p23"));h.posterIo.drain();
  check(h.posters.size()==1 && h.posters.containsKey("/p23"),"window drops unrelated RAM entries");
  h.poster(new Pick("/p0"));check(h.posterIo.queue.isEmpty(),"off-window draws do not enqueue");
  h.setPosterWindow(Collections.singletonList(new Pick("/p0")));
  h.poster(new Pick("/p0"));
  h.setPosterWindow(Collections.singletonList(new Pick("/p1")));
  int noticesBefore=h.notices;h.posterIo.drain();
  check(h.posters.isEmpty() && h.posterRamBytes==0 && h.notices==noticesBefore,"obsolete queued task cannot refill cache");
  check(!h.posterFailures.containsKey("/p0") && h.posterPending.isEmpty(),"cancelled work has no failure backoff");
  h.setPosterWindow(Collections.singletonList(new Pick("/p0")));
  h.poster(new Pick("/p0"));h.posterIo.drain();
  check(h.posters.containsKey("/p0"),"returning page reloads immediately from disk");
  h.setPosterWindow(Collections.emptyList());
  check(h.posters.isEmpty() && h.posterRamBytes==0,"hidden home drops poster cache");
  h.setPosterWindow(Collections.singletonList(new Pick("/p2")));
  h.poster(new Pick("/p2"));
  BitmapFactory.afterDecode=()->h.setPosterWindow(Collections.emptyList());
  h.posterIo.drain();BitmapFactory.afterDecode=null;
  check(h.posters.isEmpty() && h.posterRamBytes==0 && !h.posterFailures.containsKey("/p2"),"in-flight decode cannot refill hidden home or poison retries");
  System.out.println("PASS: pending bound, deduplication, RAM LRU, disk reload, retry deadline, bounded failure history");
 }
}
'''
with tempfile.TemporaryDirectory(prefix="tvos-poster-test-") as directory:
    root=Path(directory)
    (root/'PosterHarness.java').write_text(header+body+tests)
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory, str(root/'PosterHarness.java')], check=True)
    subprocess.run([java, '-cp', directory, 'PosterHarness'], check=True)
