"""Execute search history and title-page metadata mapping without network or an Android device."""
from pathlib import Path
import subprocess
import tempfile
from tmdb_metadata_fixture import JSON, ARRAY, block
sources = {
 'org/json/JSONObject.java': JSON,
 'org/json/JSONArray.java': ARRAY,
 'org/json/JSONException.java': 'package org.json;public class JSONException extends Exception {public JSONException(String s){super(s);}}',
 'io/tvos/Recs.java': 'package io.tvos;class Recs {' + block(Path('app/src/io/tvos/Recs.java').read_text(), '    static final class Pick {') + '}',
 'io/tvos/AppLanguage.java': 'package io.tvos;class AppLanguage {String apiTag="ru-RU";static AppLanguage current(){return new AppLanguage();}}',
 'io/tvos/TmdbMetadata.java': '''package io.tvos;class TmdbMetadata {static Recs.Pick search(org.json.JSONObject item){if(!item.optString("media_type").equals("movie")&&!item.optString("media_type").equals("tv"))return null;Recs.Pick p=new Recs.Pick();p.id=item.optInt("id");return p;}}''',
 'io/tvos/TmdbClient.java': '''package io.tvos;class TmdbClient {static org.json.JSONObject response;static String path;static org.json.JSONObject get(String p)throws java.io.IOException,org.json.JSONException{path=p;return response;}}''',
}
for name in ['SearchData', 'SearchHistory']:
 sources['io/tvos/' + name + '.java'] = Path('app/src/io/tvos/' + name + '.java').read_text()
sources['io/tvos/SearchHarness.java'] = '''package io.tvos;
import java.util.*;import org.json.*;
public class SearchHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  List<String> initial=Arrays.asList("Dune","DUNE"," ","Arrival","arrival");
  List<String> history=SearchHistory.remember(initial," dune ");
  check(history.equals(Arrays.asList("dune","Arrival")),"trim and deduplicate case variants");
  check(initial.size()==5,"input history unchanged");
  check(SearchHistory.remember(history," ").equals(history),"empty search not recorded");
  for(int i=0;i<12;i++)history=SearchHistory.remember(history,"Film "+i);
  check(history.size()==8&&history.get(0).equals("Film 11")&&history.get(7).equals("Film 4"),"eight most recent searches");
  JSONArray entries=new JSONArray();for(int i=0;i<15;i++)entries.put(new JSONObject().put("id",i).put("media_type",i==0?"person":"movie"));
  TmdbClient.response=new JSONObject().put("results",entries);
  List<Recs.Pick> trending=SearchData.trending();
  check(trending.size()==10&&trending.get(0).id==1,"trending is bounded and excludes people");
  check(TmdbClient.path.equals("/trending/all/week?language=ru-RU"),"localized popular titles");
  Recs.Pick pick=new Recs.Pick();pick.id=42;pick.kind="tv";
  TmdbClient.response=new JSONObject().put("overview","Synopsis").put("backdrop_path","/wide.jpg").put("genres",new JSONArray().put(new JSONObject().put("name","Drama")).put(new JSONObject().put("name","Comedy")));
  SearchData.Detail detail=SearchData.detail(pick);
  check(TmdbClient.path.equals("/tv/42?language=ru-RU"),"series identity and language retained");
  check(detail.genres.equals("Drama, Comedy")&&detail.overview.equals("Synopsis")&&detail.backdrop.equals("/wide.jpg"),"detail fields mapped");
  check(detail.minutes==0,"unknown runtime is not invented");
  pick.kind="movie";TmdbClient.response=new JSONObject().put("runtime",123);detail=SearchData.detail(pick);
  check(TmdbClient.path.startsWith("/movie/42?")&&detail.minutes==123&&detail.genres.isEmpty(),"movie runtime and missing optional fields");
  System.out.println("PASS: bounded search history, localized trending and film/series detail mapping");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name, content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.SearchHarness'],check=True)
