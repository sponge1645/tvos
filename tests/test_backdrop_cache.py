"""Execute production backdrop files/LRU/decoding against deterministic graphics and HTTP adapters."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import files
sources=files()
sources.update({
'android/content/Context.java':'package android.content;public class Context {final java.io.File dir;public Context(java.io.File d){dir=d;}public Context getApplicationContext(){return this;}public java.io.File getCacheDir(){return dir;}}',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap {}',
'android/graphics/BitmapFactory.java':'''package android.graphics;public class BitmapFactory {public static boolean bad;public static int sample;public static class Options {public boolean inJustDecodeBounds;public int outWidth,outHeight,inSampleSize;}public static Bitmap decodeFile(String p,Options o){if(o.inJustDecodeBounds){o.outWidth=3840;o.outHeight=2160;return null;}sample=o.inSampleSize;return bad?null:new Bitmap();}}''',
'io/tvos/Backdrop.java':'''package io.tvos;final class Backdrop {static final int W=1920;static Runnable hook;final boolean light;Backdrop(boolean l){light=l;}static Backdrop make(android.graphics.Bitmap b,boolean light){if(hook!=null)hook.run();return new Backdrop(light);}}''',
'io/tvos/AppLog.java':'package io.tvos;final class AppLog {static void w(String s){}static void unexpected(String s,RuntimeException e){}}',
'io/tvos/BackdropHarness.java':'''package io.tvos;import java.util.*;import java.util.concurrent.*;import java.io.*;import java.nio.file.*;import java.net.*;import android.content.Context;import android.graphics.BitmapFactory;import io.tvos.Recs.Pick;
public class BackdropHarness {
 static class Queue implements Executor {final List<Runnable> tasks=new ArrayList<>();public void execute(Runnable r){tasks.add(r);}void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}}
 static class Connection extends HttpURLConnection {static boolean fail;static int closed;Connection(URL u){super(u);}public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closed++;}public InputStream getInputStream()throws IOException{if(fail)throw new IOException("offline");return new ByteArrayInputStream(new byte[]{1,2});}}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static Pick pick(int i){Pick p=new Pick();p.id=i;p.kind="movie";p.backdrop="/image"+i;p.title="Title";return p;}
 static void fixture(File root,Pick p)throws Exception {File f=new File(new File(root,"recs"),p.backdrop.substring(1));f.getParentFile().mkdirs();Files.write(f.toPath(),new byte[]{1});}
 @SuppressWarnings("unchecked") static Map<String,Backdrop> images(BackdropCache c)throws Exception {java.lang.reflect.Field f=BackdropCache.class.getDeclaredField("images");f.setAccessible(true);return (Map<String,Backdrop>)f.get(c);}
 public static void main(String[] args)throws Exception {
  URL.setURLStreamHandlerFactory(p->new URLStreamHandler(){protected URLConnection openConnection(URL u){return new Connection(u);}});
  File root=new File(args[0]);Queue worker=new Queue();List<Pick> initial=new ArrayList<>(Arrays.asList(pick(0),pick(1),pick(2)));int[] notices={0};BackdropCache cache=new BackdropCache(new Context(root),worker,()->notices[0]++,()->initial);Map<String,Backdrop> images=images(cache);
  for(int i=0;i<15;i++)fixture(root,pick(i));cache.setLight(true);Pick p=pick(0);cache.backdrop(p);cache.backdrop(p);check(worker.tasks.size()==1,"same path is deduplicated");worker.drain();check(cache.backdrop(p)!=null&&worker.tasks.isEmpty()&&notices[0]==1,"RAM hit avoids decode");check(BitmapFactory.sample==2,"original image downsampled to backdrop width");
  for(int i=1;i<15;i++){cache.backdrop(pick(i));worker.drain();}check(images.size()==10&&images.containsKey("/image0")&&images.containsKey("/image1")&&images.containsKey("/image2"),"light LRU retains first three new-release backgrounds");
  cache.setLight(false);check(images.isEmpty(),"theme change invalidates prepared backdrops");for(int i=0;i<7;i++){cache.backdrop(pick(i));worker.drain();}check(images.size()==4,"dark LRU has four entries");
  cache.retain(Collections.singleton("/image0"));check(images.size()==1&&images.containsKey("/image0"),"trim retains requested backgrounds");
  cache.setLight(true);Backdrop.hook=()->cache.setLight(false);int before=notices[0];cache.backdrop(pick(5));worker.drain();Backdrop.hook=null;check(images.isEmpty()&&notices[0]==before,"theme change during preparation rejects stale result");
  BitmapFactory.bad=true;cache.backdrop(pick(6));worker.drain();check(!cache.hasImage(pick(6)),"unreadable original is deleted");BitmapFactory.bad=false;cache.backdrop(pick(6));worker.drain();check(images.containsKey("/image6"),"failed pending path can be retried");
  Pick downloaded=pick(99);cache.download(downloaded);check(cache.hasImage(downloaded)&&cache.imageFile(downloaded).length()==2&&Connection.closed>0,"original network download completes and disconnects");
  Connection.fail=true;try{cache.download(pick(100));throw new AssertionError("failure expected");}catch(IOException expected){}check(!new File(new File(root,"recs"),"image100.part").exists(),"failed download removes partial file");Connection.fail=false;
  File dir=new File(root,"recs");File stale=new File(dir,"stale.part");Files.write(stale.toPath(),new byte[]{1});stale.setLastModified(1);for(int i=200;i<230;i++)fixture(root,pick(i));cache.download(pick(300));check(!stale.exists()&&dir.listFiles((d,n)->!n.endsWith(".part")).length==24,"disk pruning removes stale partials and keeps 24 originals");
  check(cache.backdrop(null)==null,"null pick is safe");System.out.println("PASS: backdrop deduplication, sampling, protected LRU, theme races, trim, decode retry, downloads and disk pruning");
 }
}'''
})
sources['io/tvos/BackdropCache.java']=Path('app/src/io/tvos/BackdropCache.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-backdrop-cache-') as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.BackdropHarness',str(root/'data')],check=True)
