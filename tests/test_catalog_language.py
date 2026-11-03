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
 static class Config {static String TMDB_KEY="test";}static class Prefs {static String region="RU";static String recommendationRegion(){return region;}}
 static class AppLog {static void w(String s){}static void unexpected(String stage,RuntimeException e){}}
 List<Pick> picks=new ArrayList<>(),newPicks=new ArrayList<>();String listLanguage="";long listAt,failedAt,keyStamp;int period,notices,refreshes;String error="";
 static class Enrichment {void resetAttempts(){}}final Enrichment movieEnrichment=new Enrichment();
 final CatalogBuilder catalogBuilder=new CatalogBuilder(this::get,CatalogHarness::day);
 List<String> requests=new ArrayList<>();Runnable onGet;boolean emptyFresh,emptyRegional;
 void notifyListeners(){notices++;}void refreshIfStale(){refreshes++;}
 static String day(int offset){return offset<0?"2026-04-27":"2026-10-24";}
 JSONObject get(String path)throws IOException, org.json.JSONException {
  requests.add(path);if(onGet!=null)onGet.run();
  if(emptyFresh&&(path.contains("primary_release_date.gte")||path.contains("first_air_date.gte")))return new JSONObject().put("results",new JSONArray());
  if(emptyRegional&&(path.contains("&region=")||path.contains("&watch_region=")))return new JSONObject().put("results",new JSONArray());
  boolean movie=path.contains("/movie?");boolean en=path.contains("language=en-US");
  JSONObject item=new JSONObject().put("id",movie?1:2).put("title",en?"English movie":"Русский фильм").put("name",en?"English series":"Русский сериал")
   .put("backdrop_path","/image.jpg").put("poster_path",en?"/english.jpg":"/russian.jpg").put("vote_average",8.5).put("vote_count",2000)
   .put("release_date","2026-09-01").put("first_air_date","2026-09-01").put("origin_country",new JSONArray().put("US")).put("genre_ids",new JSONArray().put(9648));
  return new JSONObject().put("results",new JSONArray().put(item));
 }
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
tests = r'''
 public static void main(String[] args)throws Exception {
  Locale.setDefault(new Locale("ru","RU"));Ui.language("en");CatalogHarness h=new CatalogHarness();
  check(h.fetchList(1,"en"),"English fetch succeeds");
  for(String path:h.requests)check(path.contains("language=en-US"),"all catalog requests use English: "+path);
  check(h.requests.stream().allMatch(p->p.contains("region=RU")),"selected country in every movie/TV request");
  check(h.picks.get(0).title.equals("English movie") && h.newPicks.get(0).poster.equals("/english.jpg"),"localized titles/posters used");
  check(countryName("US").equals("USA") && countryName("GB").equals("UK") && countryName("FR").equals("France"),"app English overrides Russian Android locale");
  JSONObject detail=new JSONObject().put("release_date","2026-09-01").put("production_countries",new JSONArray().put(new JSONObject().put("iso_3166_1","US")));
  check(TmdbMetadata.movieOrigin(detail,"").equals("2026 · USA"),"English movie metadata");
  Pick old=h.picks.get(0),fresh=new Pick();fresh.id=old.id;fresh.kind=old.kind;
  check(!samePicks(Arrays.asList(old),Arrays.asList(fresh)),"same ID with new localized object replaces old card");
  check(samePicks(Arrays.asList(old),Arrays.asList(old)),"unchanged card object stays stable");
  long englishStamp=h.sourceStamp();Ui.language("ru");check(h.sourceStamp()!=englishStamp,"language changes source identity/backoff");
  h.resetLanguage();check(h.picks.isEmpty()&&h.newPicks.isEmpty()&&h.listLanguage.isEmpty()&&h.refreshes==1,"old localized lists cleared and refetch requested");
  h.requests.clear();check(h.fetchList(1,"ru"),"Russian fetch succeeds");
  check(h.picks.get(0).title.equals("Русский фильм")&&countryName("US").equals("США")&&countryName("FR").equals("Франция"),"Russian strings retained");
  h.onGet=()->Ui.language("en");h.requests.clear();check(!h.fetchList(1,"ru"),"stale in-flight catalog rejected");
  check(h.requests.size()==1&&h.listLanguage.equals("ru"),"no further old-language requests or false language stamp");
  h.onGet=null;h.emptyFresh=true;h.requests.clear();h.fetchList(1,"en");check(h.picks.size()==2&&h.newPicks.size()==2,"repeated discover pages deduplicated; empty fresh falls back to popular");
  check(h.requests.size()==12&&h.requests.stream().noneMatch(p->p.contains("with_genres")),"no retired category network requests");
  Ui.language("en");Prefs.region="FR";h.onGet=null;h.emptyFresh=false;h.requests.clear();long france=h.sourceStamp();check(h.fetchList(1,"en"),"French catalog succeeds independently of UI language");check(h.requests.stream().filter(p->p.contains("/movie?")).allMatch(p->p.contains("&region=FR")),"movie release country is France");check(h.requests.stream().filter(p->p.contains("/tv?")).allMatch(p->p.contains("&watch_region=FR")&&p.contains("with_watch_monetization_types=")),"TV availability is filtered for France");Prefs.region="RU";check(france!=h.sourceStamp(),"country changes cache and backoff identity");h.onGet=()->Prefs.region="FR";h.requests.clear();check(!h.fetchList(1,"en")&&h.requests.size()==1,"old-country response stops further requests and publication");h.onGet=null;Prefs.region="MD";h.emptyRegional=true;h.requests.clear();check(h.fetchList(1,"en")&&h.picks.size()==2&&h.newPicks.size()==2,"countries without TMDB regional records use a bounded global fallback");System.out.println("PASS: catalog language, RU ranking, country locale, cache reset, stale response rejection, localized card replacement");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-language-test-') as directory:
    root = Path(directory)
    (root/'HomePosterWindow.java').write_text(Path('app/src/io/tvos/home/HomePosterWindow.java').read_text())
    body = ''.join(method(s) for s in ['    private static boolean sameSource(', '    private boolean fetchList(', '    private synchronized void resetLanguage(', '    private long sourceStamp(', '    static String key('])
    body += 'static boolean samePicks(java.util.List<Pick> a,java.util.List<Pick> b){return io.tvos.home.HomePosterWindow.select(a,0,Math.max(1,a.size()),b)==b;}'
    (root/'CatalogHarness.java').write_text(header+body+tests)
    dependencies=files()
    for name in ['CatalogBuilder','TmdbSource','catalog/RecommendationRegion']:dependencies['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
    dependencies['io/tvos/AppLog.java']='package io.tvos;final class AppLog {static void w(String s){}static void unexpected(String s,RuntimeException e){}}'
    for name,content in dependencies.items():
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java,'com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run([java,'-cp',directory,'io.tvos.CatalogHarness'],check=True)
