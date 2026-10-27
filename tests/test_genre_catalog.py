"""Execute real genre transport, locale sort, rating catalog, bounded cache and UiRequest cancellation."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import files
sources=files()
sources['org/json/JSONObject.java']=sources['org/json/JSONObject.java'].replace(' public boolean optBoolean(String k)', ' public boolean optBoolean(String k,boolean fallback){return isNull(k)?fallback:optBoolean(k);}public boolean optBoolean(String k)')
sources['android/os/Handler.java']='''package android.os;import java.util.concurrent.*;public class Handler {public final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();public void post(Runnable r){queue.add(r);}public void deliver()throws Exception{Runnable r=queue.poll(3,TimeUnit.SECONDS);if(r==null)throw new AssertionError("callback timeout");r.run();}}'''
sources['io/tvos/AppLog.java']='package io.tvos;class AppLog {static void w(String s){}static void unexpected(String s,Throwable t){throw new AssertionError(s,t);}}'
for name in ['GenreCatalog','TmdbSource','UiRequest']:
 sources['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
sources['io/tvos/GenreHarness.java']='''package io.tvos;
import java.util.*;import java.util.concurrent.*;import org.json.*;import android.os.Handler;
public class GenreHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Transport implements TmdbSource {
  List<String> calls=new ArrayList<>();boolean fail;
  public JSONObject get(String path)throws java.io.IOException{
   calls.add(path);if(fail)throw new java.io.IOException("offline");
   if(path.startsWith("/genre/")){
    boolean ru=path.contains("ru-RU");String[] names=ru?new String[]{"драма","детектив","вестерн","боевик","военный","комедия","музыка","приключения","семейный",""}:new String[]{"Drama","Crime","Comedy","Action","Adventure","Animation","Documentary","Family","Fantasy",""};
    JSONArray a=new JSONArray();for(int i=0;i<names.length;i++)a.put(new JSONObject().put("id",i+1).put("name",names[i]));a.put(new JSONObject().put("id",1).put("name",names[0]));return new JSONObject().put("genres",a);
   }
   check(path.contains("sort_by=vote_average.desc")&&path.contains("vote_count.gte=200")&&path.contains("include_adult=false")&&path.contains("include_video=false"),"server filters and ranking");
   int genre=Integer.parseInt(path.split("with_genres=")[1].split("&")[0]);int page=Integer.parseInt(path.split("page=")[1]);JSONArray a=new JSONArray();
   for(int i=0;i<20;i++){int id=(page-1)*20+i+1;a.put(new JSONObject().put("id",id).put("title","Movie "+id).put("backdrop_path","/bg.jpg").put("poster_path","/p.jpg").put("vote_average",6+id/10.0).put("vote_count",id==2?1:200).put("adult",id==3).put("genre_ids",new JSONArray().put(id==4?999:genre)));}
   // Duplicate across pages must not duplicate a poster.
   a.put(new JSONObject().put("id",1).put("title","Duplicate").put("backdrop_path","/bg.jpg").put("vote_average",6.1).put("vote_count",500).put("genre_ids",new JSONArray().put(genre)));
   return new JSONObject().put("results",a).put("total_pages",2);
  }
 }
 public static void main(String[] args)throws Exception{
  ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());Handler main=new Handler();Transport source=new Transport();GenreCatalog c=new GenreCatalog(source,io,main);
  try{
   Ui.language("ru");List<List<GenreCatalog.Genre>> lists=new ArrayList<>();c.genres(AppLanguage.current(),lists::add);main.deliver();List<GenreCatalog.Genre> ru=lists.get(0);
   check(ru.size()==9,"all valid official genres retained, including those beyond seven visible rows");check(ru.get(0).name.equals("Боевик")&&ru.get(1).name.equals("Вестерн")&&ru.get(2).name.equals("Военный")&&ru.get(3).name.equals("Детектив")&&ru.get(4).name.equals("Драма"),"Russian locale alphabet and title case from TMDB names");
   int calls=source.calls.size();c.genres(AppLanguage.current(),lists::add);main.deliver();check(source.calls.size()==calls,"genre list cache");
   List<List<Recs.Pick>> catalogs=new ArrayList<>();c.movies(ru.get(0),AppLanguage.current(),catalogs::add);main.deliver();List<Recs.Pick> picks=catalogs.get(0);check(picks.size()==25,"five pages of five posters, bounded at 25");double rating=Double.POSITIVE_INFINITY;Set<Integer> ids=new HashSet<>();for(Recs.Pick p:picks){check(p.rating<=rating,"descending rating after merging pages");rating=p.rating;check(ids.add(p.id)&&p.id!=2&&p.id!=3&&p.id!=4,"duplicates, low-vote, adult and wrong-genre movies excluded");check(p.kind.equals("movie"),"movies only");}check(picks.get(0).id==40,"highest rated title first");
   calls=source.calls.size();c.movies(ru.get(0),AppLanguage.current(),catalogs::add);main.deliver();check(source.calls.size()==calls&&catalogs.get(0)==catalogs.get(1),"catalog cache reuses metadata objects");
   for(int id=20;id<26;id++){c.movies(new GenreCatalog.Genre(id,"genre"),AppLanguage.current(),catalogs::add);main.deliver();}calls=source.calls.size();c.movies(ru.get(0),AppLanguage.current(),catalogs::add);main.deliver();check(source.calls.size()==calls+2,"five-entry LRU evicts old genre");
   source.fail=true;c.movies(new GenreCatalog.Genre(99,"bad"),AppLanguage.current(),catalogs::add);main.deliver();check(catalogs.get(catalogs.size()-1)==null,"failure distinguishable from a successful selection");source.fail=false;
   // A callback already posted for a previous language must not publish its titles.
   c.genres(AppLanguage.current(),lists::add);io.submit(()->{}).get();int delivered=lists.size();Ui.language("en");main.deliver();check(lists.size()==delivered,"late previous-language callback rejected");c.genres(AppLanguage.current(),lists::add);main.deliver();check(lists.get(lists.size()-1).get(0).name.equals("Action"),"English genre cache and order independent of Russian");
   CountDownLatch gate=new CountDownLatch(1),started=new CountDownLatch(1);io.execute(()->{started.countDown();try{gate.await();}catch(InterruptedException e){throw new AssertionError(e);}});started.await();calls=source.calls.size();UiRequest cancelled=c.movies(new GenreCatalog.Genre(77,"queued"),AppLanguage.current(),catalogs::add);cancelled.cancel();gate.countDown();io.submit(()->{}).get();check(source.calls.size()==calls&&main.queue.isEmpty(),"cancelled queued request performs no network or delivery");
   System.out.println("PASS: actual TMDB genre requests, RU/EN complete alphabetical lists, rating/vote/genre filters, top-25 paging, bounded LRU, cache reuse, errors, stale language and cancellation");
  }finally{io.shutdownNow();}
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-genres-') as tmp:
 root=Path(tmp)
 for name,value in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.GenreHarness'],check=True)
