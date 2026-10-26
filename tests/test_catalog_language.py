"""Exercise production catalog queries, cache reset and metadata language without live TMDB."""
from pathlib import Path
import os
import subprocess
import tempfile
from tmdb_metadata_fixture import files

source = Path('app/src/io/tvos/Recs.java').read_text()
def method(signature, text=source):
    start = text.index(signature); brace = text.index('{', start); depth = 1; end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}'); end += 1
    return text[start:end]

header = r'''package io.tvos;
import java.util.*;import java.io.*;import org.json.*;import io.tvos.Recs.Pick;import static io.tvos.TmdbMetadata.countryName;
public class CatalogHarness {
 static class Config {static String TMDB_KEY="test";}static final String REGION="RU";
 static class AppLog {static void w(String s){}static void unexpected(String stage,RuntimeException e){}}
 List<Pick> picks=new ArrayList<>(),newPicks=new ArrayList<>(),detectivePicks=new ArrayList<>();String listLanguage="";long listAt,failedAt,keyStamp;int period,notices,refreshes;String error="";
 static class Enrichment {void resetAttempts(){}}final Enrichment movieEnrichment=new Enrichment();
 final CatalogBuilder catalogBuilder=new CatalogBuilder(this::get,CatalogHarness::day);
 List<String> requests=new ArrayList<>();Runnable onGet;boolean failDetectives,emptyFresh;double detectiveRating=8.5;int detectiveVotes=2000;
 void notifyListeners(){notices++;}void refreshIfStale(){refreshes++;}
 static String day(int offset){return offset<0?"2026-04-27":"2026-10-24";}
 JSONObject get(String path)throws IOException, org.json.JSONException {
  requests.add(path);if(onGet!=null)onGet.run();
  if(failDetectives && path.contains("with_genres"))throw new IOException("detectives unavailable");
  if(emptyFresh&&(path.contains("primary_release_date.gte")||path.contains("first_air_date.gte")))return new JSONObject().put("results",new JSONArray());
  boolean movie=path.contains("/movie?");boolean en=path.contains("language=en-US");
  JSONObject item=new JSONObject().put("id",movie?1:2).put("title",en?"English movie":"Русский фильм").put("name",en?"English series":"Русский сериал")
   .put("backdrop_path","/image.jpg").put("poster_path",en?"/english.jpg":"/russian.jpg").put("vote_average",8.5).put("vote_count",2000)
   .put("release_date","2026-09-01").put("first_air_date","2026-09-01").put("origin_country",new JSONArray().put("US")).put("genre_ids",new JSONArray().put(9648));
  if(path.contains("with_genres"))item.put("vote_average",detectiveRating).put("vote_count",detectiveVotes);
  return new JSONObject().put("results",new JSONArray().put(item));
 }
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
tests = r'''
 public static void main(String[] args)throws Exception {
  Locale.setDefault(new Locale("ru","RU"));Ui.language("en");CatalogHarness h=new CatalogHarness();
  check(h.fetchList(1,"en"),"English fetch succeeds");
  for(String path:h.requests)check(path.contains("language=en-US"),"all catalog requests use English: "+path);
  check(h.requests.stream().filter(p->!p.contains("with_genres")).allMatch(p->p.contains("region=RU")),"Russian ranking region retained");
  check(h.picks.get(0).title.equals("English movie") && h.newPicks.get(0).poster.equals("/english.jpg"),"localized titles/posters used");
  check(countryName("US").equals("USA") && countryName("GB").equals("UK") && countryName("FR").equals("France"),"app English overrides Russian Android locale");
  JSONObject detail=new JSONObject().put("release_date","2026-09-01").put("production_countries",new JSONArray().put(new JSONObject().put("iso_3166_1","US")));
  check(TmdbMetadata.movieOrigin(detail,"").equals("2026 · USA"),"English movie metadata");
  Pick old=h.picks.get(0),fresh=new Pick();fresh.id=old.id;fresh.kind=old.kind;
  check(!samePicks(Arrays.asList(old),Arrays.asList(fresh)),"same ID with new localized object replaces old card");
  check(samePicks(Arrays.asList(old),Arrays.asList(old)),"unchanged card object stays stable");
  long englishStamp=h.sourceStamp();Ui.language("ru");check(h.sourceStamp()!=englishStamp,"language changes source identity/backoff");
  h.resetLanguage();check(h.picks.isEmpty()&&h.newPicks.isEmpty()&&h.detectivePicks.isEmpty()&&h.listLanguage.isEmpty()&&h.refreshes==1,"old localized lists cleared and refetch requested");
  h.requests.clear();check(h.fetchList(1,"ru"),"Russian fetch succeeds");
  check(h.picks.get(0).title.equals("Русский фильм")&&countryName("US").equals("США")&&countryName("FR").equals("Франция"),"Russian strings retained");
  h.onGet=()->Ui.language("en");h.requests.clear();check(!h.fetchList(1,"ru"),"stale in-flight catalog rejected");
  check(h.requests.size()==1&&h.listLanguage.equals("ru"),"no further old-language requests or false language stamp");
  h.onGet=null;h.fetchList(1,"en");List<Pick> detectiveCache=h.detectivePicks;h.failDetectives=true;h.fetchList(1,"en");check(h.detectivePicks.size()==detectiveCache.size(),"same-language detective cache survives temporary endpoint failure");
  h.failDetectives=false;h.emptyFresh=true;h.fetchList(1,"en");check(h.picks.size()==2&&h.newPicks.size()==2,"repeated discover pages deduplicated; empty fresh falls back to popular");
  h.detectiveRating=7.5;h.fetchList(1,"en");check(h.detectivePicks.isEmpty(),"detective threshold is strictly greater than 7.5");
  h.detectiveRating=8;h.detectiveVotes=199;h.fetchList(1,"en");check(h.detectivePicks.isEmpty(),"detectives below 200 votes rejected");
  h.detectiveVotes=200;h.fetchList(1,"en");check(h.detectivePicks.size()==1,"200-vote threshold accepted and duplicate detective IDs removed");
  System.out.println("PASS: catalog language, RU ranking, country locale, cache reset, stale response rejection, localized card replacement");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-language-test-') as directory:
    root = Path(directory)
    body = ''.join(method(s) for s in ['    private boolean fetchList(', '    private static void add(', '    private synchronized void resetLanguage(', '    private long sourceStamp(', '    static String key('])
    view = Path('app/src/io/tvos/HomeView.java').read_text()
    body += method('    private static boolean samePicks(', view).replace('Recs.Pick', 'Pick')
    (root/'CatalogHarness.java').write_text(header+body+tests)
    dependencies=files()
    for name in ['CatalogBuilder','TmdbSource']:dependencies['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
    dependencies['io/tvos/AppLog.java']='package io.tvos;final class AppLog {static void w(String s){}static void unexpected(String s,RuntimeException e){}}'
    for name,content in dependencies.items():
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java,'com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run([java,'-cp',directory,'io.tvos.CatalogHarness'],check=True)
