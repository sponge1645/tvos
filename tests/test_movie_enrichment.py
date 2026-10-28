"""Run production movie enrichment with deterministic worker/UI queues and responses."""
from pathlib import Path
import subprocess, tempfile
from tmdb_metadata_fixture import files
source=Path('app/src/io/tvos/Recs.java').read_text()
def method(signature):
 start=source.index(signature); brace=source.index('{',start); depth=1; end=brace+1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
header='''package io.tvos;
import java.util.*;import java.io.*;import org.json.*;import io.tvos.Recs.Pick;
public class EnrichmentHarness {
 static class Queue implements java.util.concurrent.Executor {List<Runnable> tasks=new ArrayList<>();public void execute(Runnable r){tasks.add(r);}void post(Runnable r){tasks.add(r);}void run(){while(!tasks.isEmpty())tasks.remove(0).run();}}
 static class AppLog {static void w(String m){}static void unexpected(String m,RuntimeException e){}}
 Queue durationIo=new Queue(),main=new Queue();
 final MovieEnrichment enrichment=new MovieEnrichment(durationIo,main::post,this::get,()->this.notices++);
 Set<String> durationRequested=field("durationRequested");Map<String,JSONObject> durationResults=field("durationResults");
 @SuppressWarnings("unchecked") <T>T field(String name){try{java.lang.reflect.Field f=MovieEnrichment.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(enrichment);}catch(Exception e){throw new AssertionError(e);}}
 void requestDuration(Pick p){enrichment.request(p);}
 int requests,notices;boolean fallbackError;JSONObject response;
 JSONObject get(String path)throws IOException,JSONException{requests++;if(fallbackError&&requests%2==0)throw new IOException("offline");return response;}
 void save(){}void notifyListeners(){notices++;}
 static String key(Pick p){return p.kind+":"+p.id;}
 static String movieOrigin(JSONObject d,String fallback){return TmdbMetadata.movieOrigin(d,fallback);}
 static Pick pick(){Pick p=new Pick();p.id=42;p.kind="movie";p.movieOrigin="2020";return p;}
 static JSONObject details(int minutes,boolean country){JSONObject d=new JSONObject().put("runtime",minutes).put("release_date","2020");if(country)d.put("production_countries",new JSONArray().put(new JSONObject().put("iso_3166_1","US")));return d;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
tests='''
 public static void main(String[] args){
  Ui.language("en");EnrichmentHarness h=new EnrichmentHarness();h.response=details(125,true);Pick a=pick(),b=pick();
  h.requestDuration(a);h.requestDuration(b);h.requestDuration(b);check(h.durationIo.tasks.size()==1,"one request for duplicate ID");
  h.durationIo.run();check(a.duration.isEmpty(),"worker does not mutate visible cards");h.main.run();
  check(a.duration.equals("125 min")&&b.duration.equals("125 min")&&b.movieOrigin.equals("2020 · USA"),"both instances enriched");
  Pick search=pick();h.requestDuration(search);check(search.duration.equals("125 min")&&h.durationIo.tasks.isEmpty(),"new search instance reuses result despite daily throttle");
  h=new EnrichmentHarness();h.response=details(98,false);h.fallbackError=true;a=pick();a.movieOrigin="2020 · UK";h.requestDuration(a);h.durationIo.run();h.main.run();
  check(a.duration.equals("98 min")&&a.movieOrigin.equals("2020 · UK"),"fallback failure retains usable runtime and known country");
  check(!h.durationRequested.contains("en:movie:42"),"pending cleaned after fallback failure");
  h=new EnrichmentHarness();h.response=details(0,false);a=pick();h.requestDuration(a);h.durationIo.run();h.main.run();h.requestDuration(pick());check(h.durationIo.tasks.isEmpty(),"genuinely missing metadata has daily backoff");
  h=new EnrichmentHarness();h.response=details(125,true);a=pick();h.requestDuration(a);h.durationIo.run();Ui.language("ru");h.main.run();check(a.duration.isEmpty()&&h.notices==0,"stale language result rejected");
  h.requestDuration(a);h.durationIo.run();h.main.run();check(a.duration.equals("125 мин")&&a.movieOrigin.equals("2020 · США"),"current language can fetch independently");
  for(int i=0;i<140;i++)h.durationResults.put("key"+i,details(1,true));check(h.durationResults.size()==128,"result cache bounded");
  System.out.println("PASS: shared movie enrichment, cache reuse, partial fallback, missing data, language race and bounded cache");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-enrichment-') as tmp:
 root=Path(tmp);sources=files();sources['io/tvos/EnrichmentHarness.java']=header+tests
 for name in ['MovieEnrichment','TmdbSource']:sources['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
 sources['io/tvos/AppLog.java']='package io.tvos;final class AppLog {static void w(String s){}static void unexpected(String s,RuntimeException e){}}'
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.EnrichmentHarness'],check=True)
