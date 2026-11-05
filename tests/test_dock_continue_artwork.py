"""Resume artwork owner: queued work/UI cancellation, current cache window and metadata fallback."""
from pathlib import Path
import subprocess,tempfile
files={
 'io/tvos/DockContinueArtwork.java':Path('app/src/io/tvos/DockContinueArtwork.java').read_text(),
 'android/content/Context.java':'package android.content;public class Context {}',
 'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap {}',
 'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {public final List<Runnable> queue=new ArrayList<>();public void post(Runnable r){queue.add(r);}public void drain(){while(!queue.isEmpty())queue.remove(0).run();}}''',
 'io/tvos/artwork/PosterCache.java':'''package io.tvos.artwork;import java.util.*;public class PosterCache {public static Set<String> window=new HashSet<>();public static int max;public static Runnable decoded;public PosterCache(android.content.Context c,Runnable changed,java.util.function.Consumer<String> warn){decoded=changed;}public void setWindow(Set<String> s){window=new HashSet<>(s);max=Math.max(max,s.size());}public android.graphics.Bitmap poster(String p){return new android.graphics.Bitmap();}}''',
 'io/tvos/Recs.java':'package io.tvos;class Recs {static class Pick {String originalTitle="",watchingKey,pkg,title,poster,backdrop,watchingYear,kind;}}',
 'io/tvos/AppLog.java':'package io.tvos;class AppLog {static boolean artworkEnabled(){return false;}static void artwork(String s){}static void artworkError(String op,Exception e){}static void w(String message){}}',
 'io/tvos/ProviderImages.java':'package io.tvos;class ProviderImages {static boolean allowed(android.content.Context c,String u,String p){return true;}}',
 'io/tvos/network/ConnectionObserver.java':Path('app/src/io/tvos/network/ConnectionObserver.java').read_text(),
 'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {}',
 'io/tvos/watching/ResumeArtworkIdentity.java':'package io.tvos.watching;public class ResumeArtworkIdentity {public static String year(String p,String t,String y){return y;}}',
 'io/tvos/watching/DockPrograms.java':'''package io.tvos.watching;import java.util.*;public class DockPrograms {
 public static Candidate published;public DockPrograms(android.content.Context c,java.util.function.BiPredicate<String,String> allow){}
 public static class Candidate {public List<String> urls=new ArrayList<>();public String year="";}
 public Candidate forResume(String pkg,String title,String poster){return published;}}''',
 'io/tvos/KinoPubArtworkMetadata.java':'package io.tvos;class KinoPubArtworkMetadata {static String resolved="";static Runnable after;static String year(String key,String t,String o,io.tvos.network.ConnectionObserver c)throws java.io.IOException,org.json.JSONException{if(after!=null){Runnable r=after;after=null;r.run();}return resolved;}}',
 'io/tvos/DockArtwork.java':'''package io.tvos;class DockArtwork {
 static int calls,logos;static Runnable afterLookup;static java.net.HttpURLConnection connection;static boolean fail;static String year,kind,original;String backdrop,logo="",logoStatus="selected";
 static DockArtwork lookup(String t,String p,String y,String k,String o,io.tvos.network.ConnectionObserver c)throws java.io.IOException,org.json.JSONException {
  calls++;year=y;kind=k;original=o;if(connection!=null)c.opened(connection);if(afterLookup!=null){Runnable r=afterLookup;afterLookup=null;r.run();}if(fail)throw new java.io.IOException();DockArtwork a=new DockArtwork();a.backdrop="https://image.tmdb.org/t/p/w1280/"+t+".jpg";return a;}
 static void loadLogo(DockArtwork a,io.tvos.network.ConnectionObserver c)throws java.io.IOException,org.json.JSONException {logos++;a.logo="https://logo/"+a.backdrop.hashCode()+".png";}}''',
 'io/tvos/ArtworkHarness.java':'''package io.tvos;import java.util.*;import android.os.Handler;import io.tvos.artwork.PosterCache;
public class ArtworkHarness {
 static class Worker implements java.util.concurrent.Executor {List<Runnable> queue=new ArrayList<>();public void execute(Runnable r){queue.add(r);}void one(){queue.remove(0).run();}void drain(){while(!queue.isEmpty())one();}}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static List<Recs.Pick> picks(String prefix){List<Recs.Pick> out=new ArrayList<>();for(int i=0;i<10;i++){Recs.Pick p=new Recs.Pick();p.watchingKey=prefix+i;p.title=prefix+i;p.originalTitle="Original";p.pkg="source";p.poster="https://portrait/"+i;p.backdrop="";p.watchingYear="2008";p.kind="movie";out.add(p);}return out;}
 public static void main(String[] args)throws Exception{
  Worker worker=new Worker();Handler main=new Handler();int[] changes={0};DockContinueArtwork a=new DockContinueArtwork(new android.content.Context(),main,worker,()->changes[0]++,Runnable::run);
  List<Recs.Pick> old=picks("old");a.prepare(old,0);a.prepare(old,0);check(worker.queue.size()==4,"bounded four-card metadata requests and no duplicate pending requests");
  worker.one();check(main.queue.size()==1,"poster delivery precedes logo work");a.clear();int before=changes[0];main.drain();worker.drain();main.drain();
  check(changes[0]==before&&a.posterPath("old0")==null&&PosterCache.window.isEmpty(),"hide rejects queued UI result and stale metadata/logo tasks");
  PosterCache.decoded.run();main.drain();check(changes[0]==before,"late cache completion cannot redraw hidden row");
  a.prepare(old,0);DockArtwork.afterLookup=a::clear;int logosBefore=DockArtwork.logos;worker.drain();main.drain();
  check(changes[0]==before&&DockArtwork.logos==logosBefore&&PosterCache.window.isEmpty(),"cancellation during metadata stops poster delivery and optional logo stage");
  List<Recs.Pick> fresh=picks("new");a.prepare(fresh,0);worker.one();a.prepare(fresh,3);main.drain();
  check(a.posterPath("new0").contains("/w780/"),"TMDB landscape remains normalized to w780");
  check(!PosterCache.window.contains(a.posterPath("new0")),"late offscreen image cannot replace current cache window");
  worker.drain();main.drain();check(PosterCache.window.size()==8&&PosterCache.max<=8,"four current posters plus four optional logos share bounded cache window");
  check(DockArtwork.year.equals("2008")&&DockArtwork.kind.equals("movie")&&DockArtwork.original.equals("Original"),"year and media kind reach artwork lookup");
  a.clear();List<Recs.Pick> missing=picks("missing");for(Recs.Pick p:missing)p.watchingYear="";
  KinoPubArtworkMetadata.resolved="2008";a.prepare(missing,0);worker.drain();main.drain();check(DockArtwork.year.equals("2008"),"publisher year reaches poster and logo metadata");
  a.clear();KinoPubArtworkMetadata.after=a::clear;int lookups=DockArtwork.calls;a.prepare(missing,0);worker.drain();main.drain();check(DockArtwork.calls==lookups,"cancelled publisher lookup cannot start TMDB request");KinoPubArtworkMetadata.resolved="";
  a.reset();List<Recs.Pick> replaced=picks("replace");a.prepare(replaced,0);worker.drain();a.reset();main.drain();
  check(a.posterPath("replace0")==null&&a.logoPath("replace0")==null,"snapshot replacement rejects both queued poster and logo results");
  io.tvos.watching.DockPrograms.Candidate published=new io.tvos.watching.DockPrograms.Candidate();published.year="2026";published.urls.add("https://provider/wide");io.tvos.watching.DockPrograms.published=published;
  a.prepare(fresh,0);worker.drain();main.drain();check(a.posterPath("new0").equals("https://provider/wide")&&DockArtwork.year.equals("2026"),"provider-wide image and enriched year preserved for logo lookup");
  a.clear();a.prepare(fresh,0);worker.one();worker.one();worker.one();worker.one();
  DockArtwork.afterLookup=a::clear;logosBefore=DockArtwork.logos;worker.drain();main.drain();
  check(DockArtwork.logos==logosBefore&&PosterCache.window.isEmpty(),"cancellation during published-artwork logo metadata skips logo download");
  a.clear();io.tvos.watching.DockPrograms.published=null;DockArtwork.fail=true;fresh.get(0).backdrop="https://fallback/wide";
  a.prepare(fresh,0);worker.drain();main.drain();check(a.posterPath("new0").equals("https://fallback/wide")&&a.posterPath("new1").equals(fresh.get(1).poster),"failed lookup retains wide history image then original poster fallback");
  a.clear();DockArtwork.fail=false;final int[] disconnects={0};
  DockArtwork.connection=new java.net.HttpURLConnection(null){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnects[0]++;}};
  a.prepare(fresh,0);DockArtwork.afterLookup=a::clear;worker.drain();main.drain();
  check(disconnects[0]>=1&&PosterCache.window.isEmpty(),"reset disconnects active metadata HTTP and rejects results");DockArtwork.connection=null;
  a.clear();java.lang.reflect.Method observe=DockContinueArtwork.class.getDeclaredMethod("observer",int.class);observe.setAccessible(true);
  java.lang.reflect.Field gen=DockContinueArtwork.class.getDeclaredField("generation"),active=DockContinueArtwork.class.getDeclaredField("activeConnection");gen.setAccessible(true);active.setAccessible(true);
  io.tvos.network.ConnectionObserver oldObserver=(io.tvos.network.ConnectionObserver)observe.invoke(a,gen.getInt(a));oldObserver.opened(new java.net.HttpURLConnection(null){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}});a.reset();
  io.tvos.network.ConnectionObserver currentObserver=(io.tvos.network.ConnectionObserver)observe.invoke(a,gen.getInt(a));
  java.net.HttpURLConnection current=new java.net.HttpURLConnection(null){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}};currentObserver.opened(current);oldObserver.closed(DockArtwork.connection);
  check(active.get(a)==current,"stale close cannot discard current connection");
  boolean rejected=false;try{oldObserver.opened(current);}catch(java.util.concurrent.CancellationException expected){rejected=true;}check(rejected,"late stale open cannot restart cancelled metadata");
  currentObserver.closed(current);check(active.get(a)==null,"matching close releases connection ownership");
  java.util.List<Runnable> closing=new ArrayList<>();int[] closedCount={0};DockContinueArtwork deferred=new DockContinueArtwork(new android.content.Context(),main,worker,()->{},closing::add);
  io.tvos.network.ConnectionObserver tracked=(io.tvos.network.ConnectionObserver)observe.invoke(deferred,gen.getInt(deferred));tracked.opened(new java.net.HttpURLConnection(null){public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closedCount[0]++;}});
  deferred.reset();check(closedCount[0]==0&&closing.size()==1,"UI reset only queues disconnect");closing.remove(0).run();check(closedCount[0]==1,"disconnect executes on cancellation worker");
  a.clear();check(PosterCache.window.isEmpty(),"clear releases cache window");
  System.out.println("PASS: real artwork owner, bounded window, queued cancellation, changed viewport, year/kind propagation and provider/history fallback");
 }
}'''}
with tempfile.TemporaryDirectory(prefix='tvos-resume-artwork-') as tmp:
 root=Path(tmp)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ArtworkHarness'],check=True)
