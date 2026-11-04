"""Execute the complete production PosterCache with deterministic worker, graphics and HTTP adapters."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/content/ContentResolver.java':'''package android.content;public class ContentResolver {public static byte[] body={1};public static boolean missing;public static Runnable afterRead;public java.io.InputStream openInputStream(android.net.Uri u){if(missing)return null;return new java.io.ByteArrayInputStream(body){@Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);Runnable r=afterRead;if(r!=null)r.run();return n;}};}}''',
'android/net/Uri.java':'package android.net;public class Uri {public static Uri parse(String s){return new Uri();}}',
'android/content/Context.java':'''package android.content;public class Context {final java.io.File dir;public Context(java.io.File d){dir=d;}public Context getApplicationContext(){return this;}public java.io.File getCacheDir(){return dir;}public ContentResolver getContentResolver(){return new ContentResolver();}}''',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap {public int getAllocationByteCount(){return 1024*1024;}}',
'android/graphics/BitmapFactory.java':'''package android.graphics;public class BitmapFactory {public static Runnable afterDecode;public static int width=500,height=750,sample;public static class Options {public int inSampleSize,outWidth,outHeight;public boolean inJustDecodeBounds;}public static Bitmap decodeFile(String p,Options o){if(o.inJustDecodeBounds){o.outWidth=width;o.outHeight=height;return null;}sample=o.inSampleSize;if(new java.io.File(p).getName().startsWith("oom"))throw new OutOfMemoryError("test decode");if(afterDecode!=null)afterDecode.run();return new java.io.File(p).getName().startsWith("bad")?null:new Bitmap();}}''',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}',
}
harness='''package io.tvos;import io.tvos.artwork.PosterCache;
import java.io.*;import java.util.*;import java.nio.file.*;import java.net.*;import java.util.concurrent.Executor;import android.content.Context;import android.graphics.*;import android.os.SystemClock;
public class PosterHarness {
 static class Pick {String poster;Pick(String p){poster=p;}}
 static class Worker implements Executor {ArrayDeque<Runnable> queue=new ArrayDeque<>();public void execute(Runnable r){queue.add(r);}void drain(){while(!queue.isEmpty())queue.remove().run();}}
 static class Fake extends HttpURLConnection {static byte[] body={1};static long declared=1;static int disconnects;Fake(URL u){super(u);}public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnects++;}public long getContentLengthLong(){return declared;}public InputStream getInputStream(){return new ByteArrayInputStream(body);}}
 final List<Runnable> retries=new ArrayList<>();final Context context;final Worker posterIo=new Worker();final PosterCache cache;int notices;final List<String> warnings=new ArrayList<>();
 final Map<String,Bitmap> posters;final Set<String> posterPending;final Map<String,Long> posterFailures;
 PosterHarness(File dir)throws Exception {context=new Context(dir);cache=new PosterCache(context,()->notices++,posterIo,warnings::add,(task,delay)->{check(delay==30000,"retry delay");retries.add(task);});posters=field("posters");posterPending=field("posterPending");posterFailures=field("posterFailures");}
 @SuppressWarnings("unchecked") <T>T field(String name)throws Exception {java.lang.reflect.Field f=PosterCache.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(cache);}
 long ram()throws Exception{return this.<Long>field("posterRamBytes");}
 Bitmap poster(Pick p){return cache.poster(p==null?null:p.poster);}
 void setPosterWindow(List<Pick> picks){Set<String> paths=new HashSet<>();for(Pick p:picks)if(p!=null&&p.poster!=null&&!p.poster.isEmpty())paths.add(p.poster);cache.setWindow(paths);}
'''
tests='''
 void fixture(String path)throws Exception{
  File dir=new File(context.getCacheDir(),"tmdb-posters");dir.mkdirs();
  Files.write(new File(dir,path.replace("/", "")).toPath(),new byte[]{1});
 }
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){protected URLConnection openConnection(URL url){return new Fake(url);}});
  check(PosterCache.imageUrl("https://cdn.example/poster.jpg?x=1").equals("https://cdn.example/poster.jpg?x=1"),"external poster URL is not prefixed with TMDB");
  check(PosterCache.imageUrl("/poster.jpg").equals("https://image.tmdb.org/t/p/w500/poster.jpg"),"TMDB paths unchanged");
  check(PosterCache.cacheName("https://cdn.example/p?x=1").matches("external-[0-9a-f]{64}"),"external filenames bounded and safe");
  check(!PosterCache.cacheName("https://cdn.example/p?x=1").equals(PosterCache.cacheName("https://cdn.example/p?x=2")),"full URL identity includes query");
  PosterHarness h=new PosterHarness(new File(args[0]));
  for(int i=0;i<100;i++){String p="/p"+i;h.fixture(p);h.poster(new Pick(p));}
  check(h.posterPending.size()==24,"pending queue cap");
  h.poster(new Pick("/p0"));check(h.posterIo.queue.size()==24,"deduplication");
  h.posterIo.drain();
  check(h.posters.size()==12 && h.ram()==12L*1024*1024,"RAM LRU cap");
  check(!h.posters.containsKey("/p0") && h.posters.containsKey("/p23"),"LRU order");
  check(h.posterPending.isEmpty() && h.notices==24,"completion cleanup");
  h.poster(new Pick("/p23"));check(h.posterIo.queue.isEmpty(),"RAM hit does not enqueue");
  h.poster(new Pick("content://provider/poster/42"));h.posterIo.drain();check(h.posters.containsKey("content://provider/poster/42"),"provider poster loaded through ContentResolver");
  h.poster(new Pick("android.resource://provider/drawable/poster"));h.posterIo.drain();check(h.posters.containsKey("android.resource://provider/drawable/poster"),"resource poster loaded through ContentResolver");
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
  check(h.posters.isEmpty() && h.ram()==0 && h.notices==noticesBefore,"obsolete queued task cannot refill cache");
  check(!h.posterFailures.containsKey("/p0") && h.posterPending.isEmpty(),"cancelled work has no failure backoff");
  h.setPosterWindow(Collections.singletonList(new Pick("/p0")));
  h.poster(new Pick("/p0"));h.posterIo.drain();
  check(h.posters.containsKey("/p0"),"returning page reloads immediately from disk");
  h.setPosterWindow(Collections.emptyList());
  check(h.posters.isEmpty() && h.ram()==0,"hidden home drops poster cache");
  h.setPosterWindow(Collections.singletonList(new Pick("/p2")));
  h.poster(new Pick("/p2"));
  BitmapFactory.afterDecode=()->h.setPosterWindow(Collections.emptyList());
  h.posterIo.drain();BitmapFactory.afterDecode=null;
  check(h.posters.isEmpty() && h.ram()==0 && !h.posterFailures.containsKey("/p2"),"in-flight decode cannot refill hidden home or poison retries");

  h.setPosterWindow(Collections.singletonList(new Pick("/network")));Fake.body=new byte[]{1,2,3};Fake.declared=3;
  h.poster(new Pick("/network"));h.posterIo.drain();check(h.posters.containsKey("/network")&&Fake.disconnects>0,"network download publishes decoded poster and disconnects");
  File cacheDir=new File(h.context.getCacheDir(),"tmdb-posters");check(new File(cacheDir,"network").length()==3&&!new File(cacheDir,"network.part").exists(),"complete download atomically promoted");
  for(String path:new String[]{"/oversize-header","/incomplete","/oversize-stream"}){
   Fake.body=path.equals("/oversize-stream")?new byte[8*1024*1024+1]:new byte[]{1};Fake.declared=path.equals("/oversize-header")?8*1024*1024+1:path.equals("/incomplete")?5:-1;
   h.setPosterWindow(Collections.singletonList(new Pick(path)));h.poster(new Pick(path));h.posterIo.drain();
   check(h.posterFailures.containsKey(path)&&!new File(cacheDir,path.substring(1)).exists()&&!new File(cacheDir,path.substring(1)+".part").exists(),"failed download is bounded and leaves no cache/temp: "+path);
  }
  String provider="content://provider/oversized";
  android.content.ContentResolver.body=new byte[8*1024*1024+1];h.setPosterWindow(Collections.singletonList(new Pick(provider)));h.poster(new Pick(provider));h.posterIo.drain();
  check(h.posterFailures.containsKey(provider)&&!new File(cacheDir,PosterCache.cacheName(provider)).exists(),"provider stream obeys same size limit as HTTPS");
  provider="content://provider/missing";android.content.ContentResolver.missing=true;h.setPosterWindow(Collections.singletonList(new Pick(provider)));h.poster(new Pick(provider));h.posterIo.drain();android.content.ContentResolver.missing=false;
  check(h.posterFailures.containsKey(provider)&&!h.warnings.isEmpty(),"null provider stream reports through injected warning sink");
  provider="content://provider/cancelled";android.content.ContentResolver.body=new byte[]{1,2,3};h.setPosterWindow(Collections.singletonList(new Pick(provider)));
  android.content.ContentResolver.afterRead=()->{h.setPosterWindow(Collections.emptyList());android.content.ContentResolver.afterRead=null;};h.poster(new Pick(provider));h.posterIo.drain();
  check(!h.posterFailures.containsKey(provider)&&h.posterPending.isEmpty()&&!new File(cacheDir,PosterCache.cacheName(provider)+".part").exists(),"provider cancellation cleans partial file without poisoning retries");
  h.setPosterWindow(Collections.singletonList(new Pick(provider)));h.poster(new Pick(provider));h.posterIo.drain();check(h.posters.containsKey(provider),"cancelled provider image retries immediately when visible again");
  android.content.ContentResolver.body=new byte[]{1};
  Fake.body=new byte[]{1};Fake.declared=1;
  File old=new File(cacheDir,"old-large");try(RandomAccessFile f=new RandomAccessFile(old,"rw")){f.setLength(201L*1024*1024);}old.setLastModified(1);
  h.setPosterWindow(Collections.singletonList(new Pick("/new-download")));h.poster(new Pick("/new-download"));h.posterIo.drain();check(!old.exists(),"new download prunes least recently used disk cache beyond 200 MiB");
  PosterHarness recovery=new PosterHarness(new File(args[0],"recovery"));
  recovery.setPosterWindow(Collections.singletonList(new Pick("/retry")));Fake.body=new byte[]{1};Fake.declared=5;
  recovery.poster(new Pick("/retry"));recovery.posterIo.drain();check(recovery.retries.size()==1,"one retry timer after visible failure");
  SystemClock.now+=30000;Fake.declared=1;recovery.retries.remove(0).run();recovery.posterIo.drain();
  check(recovery.posters.containsKey("/retry"),"visible poster recovers without another draw request");
  recovery.setPosterWindow(Collections.emptyList());for(Runnable retry:new ArrayList<>(recovery.retries))retry.run();
  check(recovery.posterIo.queue.isEmpty(),"hidden screens do not load on retry timer");
  File shared=new File(args[0],"shared");PosterHarness one=new PosterHarness(shared),two=new PosterHarness(shared);
  String same="content://provider/shared";android.content.ContentResolver.afterRead=()->{
   android.content.ContentResolver.afterRead=null;two.poster(new Pick(same));two.posterIo.drain();
  };
  one.poster(new Pick(same));one.posterIo.drain();check(one.posters.containsKey(same)&&two.posters.containsKey(same),"overlapping screens cannot corrupt each other's partial download");
  BitmapFactory.width=8000;BitmapFactory.height=12000;one.poster(new Pick("/large-dimensions"));one.posterIo.drain();
  check(BitmapFactory.sample>=16&&one.posters.containsKey("/large-dimensions"),"large external dimensions decode within per-image memory budget");
  BitmapFactory.width=500;BitmapFactory.height=750;
  PosterCache stopped=new PosterCache(one.context,()->{},r->{throw new java.util.concurrent.RejectedExecutionException();},s->{},(r,delay)->{});
  check(stopped.poster("/after-close")==null,"stopped image executor cannot crash drawing");
  System.out.println("PASS: pending bound, deduplication, RAM LRU, disk reload, retry deadline, bounded failure history, shared provider limits/null/cancellation");
 }
}
'''

sources['io/tvos/PosterHarness.java']=harness+tests
sources['io/tvos/artwork/PosterCache.java']=Path('app/src/io/tvos/artwork/PosterCache.java').read_text()
sources['io/tvos/artwork/ArtworkDiskCache.java']=Path('app/src/io/tvos/artwork/ArtworkDiskCache.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-poster-cache-') as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.PosterHarness',str(root/'data')],check=True)
