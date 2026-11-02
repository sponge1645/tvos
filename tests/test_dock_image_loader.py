"""Execute actual Dock image transport/cache/decode and request-lifetime behavior with real temp files."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/net/Uri.java':'package android.net;public class Uri {public final String text;private Uri(String s){text=s;}public static Uri parse(String s){return new Uri(s);}}',
'android/content/Context.java':'package android.content;import java.io.*;public class Context {public File directory;public int queries;public InputStream body;public Runnable open;public File getCacheDir(){return directory;}public Context getContentResolver(){return this;}public InputStream openInputStream(android.net.Uri uri){queries++;if(open!=null)open.run();return body;}}',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap {}',
'android/graphics/BitmapFactory.java':'''package android.graphics;public class BitmapFactory {public static int width=3840,height=2160,decoded,sample;public static class Options {public boolean inJustDecodeBounds;public int outWidth,outHeight,inSampleSize;}public static Bitmap decodeFile(String path,Options o){o.outWidth=width;o.outHeight=height;if(o.inJustDecodeBounds)return null;decoded++;sample=o.inSampleSize;return new Bitmap();}}''',
'io/tvos/artwork/PosterCache.java':'package io.tvos.artwork;public class PosterCache {public static String cacheName(String s){return Integer.toHexString(s.hashCode());}}',
'external/ImageTest.java':'''package external;
import io.tvos.artwork.*;import android.content.*;import android.graphics.*;import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
public class ImageTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Requests implements DockImageLoader.Requests {int generation=7,opened,closed,token;boolean reject;HttpURLConnection current;public boolean active(int token){return generation==token;}public void opened(int token,HttpURLConnection c){opened++;this.token=token;current=c;if(reject)throw new CancellationException();}public void closed(HttpURLConnection c){closed++;if(current==c)current=null;}}
 static class Body extends InputStream {long left;boolean closed;Runnable before;Body(long n){left=n;}public int read(){if(before!=null)before.run();return left-->0?1:-1;}public int read(byte[] b,int off,int len){if(before!=null)before.run();if(left==0)return -1;int n=(int)Math.min(left,len);left-=n;Arrays.fill(b,off,off+n,(byte)1);return n;}public void close(){closed=true;}}
 static class Connection extends HttpURLConnection {Body body;int disconnected,streams;boolean fail;Connection(URL u,Body b){super(u);body=b;}public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected++;}public InputStream getInputStream()throws IOException {streams++;if(fail)throw new IOException("offline");return body;}}
 static final class Network extends URLStreamHandler {Connection last;Body body;boolean fail;protected URLConnection openConnection(URL u){last=new Connection(u,body);last.fail=fail;return last;}}
 static File file(Context c,String url){return new File(c.directory,"dock-backdrops/"+PosterCache.cacheName(url));}
 static void noPart(Context c){File[] files=new File(c.directory,"dock-backdrops").listFiles();if(files!=null)for(File f:files)check(!f.getName().endsWith(".part"),"partial download cleaned");}
 static void failure(DockImageLoader loader,String url,String text)throws Exception {boolean failed=false;try{loader.read(url,7,true,2500);}catch(IOException expected){failed=true;check(expected.getMessage().contains(text),"expected failure "+text+", got "+expected);}check(failed,"failure propagated");}
 public static void main(String[] args)throws Exception {
  Network net=new Network();URL.setURLStreamHandlerFactory(s->s.equals("https")?net:null);Context c=new Context();c.directory=new File(args[0],"main");Requests request=new Requests();DockImageLoader loader=new DockImageLoader(c,request);
  check(!c.directory.exists(),"construction performs no file access");String url="content://owner/wide";Body body=new Body(5);c.body=body;check(loader.read(url,7,true,8000)!=null&&BitmapFactory.sample==2&&c.queries==1&&body.closed,"provider download closes stream and samples landscape");
  check(file(c,url).isFile()&&file(c,url).length()==5,"complete cache published");int queries=c.queries;loader.read(url,7,true,8000);check(c.queries==queries,"cache hit avoids provider");noPart(c);
  BitmapFactory.width=1200;BitmapFactory.height=600;c.body=new Body(1);loader.read("android.resource://owner/logo",7,false,8000);check(BitmapFactory.sample==2,"logo sampling uses 600 by 300 bounds");
  BitmapFactory.width=500;BitmapFactory.height=800;c.body=new Body(1);int decoded=BitmapFactory.decoded;String portrait="content://owner/portrait";check(loader.read(portrait,7,true,8000)==null&&BitmapFactory.decoded==decoded&&file(c,portrait).isFile(),"portrait rejection precedes bitmap decode and keeps cache");
  BitmapFactory.width=1200;BitmapFactory.height=1000;c.body=new Body(1);check(loader.read("content://owner/boundary",7,true,8000)!=null,"landscape 1.2 ratio boundary retained");
  BitmapFactory.width=0;c.body=new Body(1);String corrupt="content://owner/corrupt";check(loader.read(corrupt,7,true,8000)==null&&!file(c,corrupt).exists(),"invalid bounds discard corrupt cache");BitmapFactory.width=1920;BitmapFactory.height=1080;c.body=new Body(1);check(loader.read(corrupt,7,true,8000)!=null,"invalid cache can recover on next request");
  request.generation=8;decoded=BitmapFactory.decoded;check(loader.read(corrupt,7,true,8000)==null&&BitmapFactory.decoded==decoded,"stale cached request skips final decode");request.generation=7;
  c.body=null;failure(loader,"content://owner/missing","No image stream");noPart(c);
  c.body=new Body(16L*1024*1024+1);failure(loader,"content://owner/large","Image too large");check(!file(c,"content://owner/large").exists()&&((Body)c.body).closed,"oversize never published and stream closes");noPart(c);
  Body stale=new Body(100);stale.before=()->request.generation=8;c.body=stale;failure(loader,"content://owner/stale","Stale focus");check(stale.closed&&!file(c,"content://owner/stale").exists(),"mid-stream cancellation cleans uncommitted file");noPart(c);request.generation=7;
  c.body=new Body(1);check(loader.read("content://owner/stale",7,true,8000)!=null,"cancelled image can retry");
  net.body=new Body(2);String https="https://example.test/wide";loader.read(https,7,true,2500);check(net.last.getConnectTimeout()==2500&&net.last.getReadTimeout()==2500&&request.opened==1&&request.closed==1&&request.token==7&&request.current==null&&net.last.disconnected==1&&net.body.closed,"HTTPS timeout/token/connection and stream lifecycle");
  net.fail=true;failure(loader,"https://example.test/fail","offline");check(net.last.disconnected==1&&request.closed==2&&request.current==null,"failed HTTPS connection released");net.fail=false;noPart(c);
  request.reject=true;boolean cancelled=false;try{loader.read("https://example.test/rejected",7,true,2500);}catch(CancellationException expected){cancelled=true;}check(cancelled&&net.last.streams==0&&net.last.disconnected==1&&request.current==null,"open-time stale cancellation closes connection before body");request.reject=false;noPart(c);
  Context bounded=new Context();bounded.directory=new File(args[0],"bounded");DockImageLoader cache=new DockImageLoader(bounded,request);for(int i=0;i<9;i++){String key="content://owner/cache"+i;bounded.body=new Body(1);cache.read(key,7,true,8000);file(bounded,key).setLastModified(1000+i);}check(new File(bounded.directory,"dock-backdrops").listFiles().length==6&&!file(bounded,"content://owner/cache0").exists()&&file(bounded,"content://owner/cache8").exists(),"six-file cache evicts oldest downloads");
  check(DockImageLoader.publishedImage("https://image.tmdb.org/t/p/original/a").equals("https://image.tmdb.org/t/p/w1280/a")&&DockImageLoader.publishedImage("https://other.test/t/p/original/a").contains("original"),"published URL rewrite is host-specific");
  System.out.println("PASS: real Dock image loader, provider/resource/HTTPS streams, cache hits/eviction, sampling/aspect, corrupt recovery, size limit, partial cleanup and request/connection cancellation");
 }
}'''}
sources['io/tvos/artwork/DockImageLoader.java']=Path('app/src/io/tvos/artwork/DockImageLoader.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-dock-images-') as tmp:
 root=Path(tmp)
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'external.ImageTest',tmp],check=True)
