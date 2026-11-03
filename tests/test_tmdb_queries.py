"""Execute interactive query policy using injected transport, mapping, locale and page randomness."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import JSON,ARRAY
sources={
'org/json/JSONObject.java':JSON,
'org/json/JSONArray.java':ARRAY,
'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {private static final long serialVersionUID=1L;public JSONException(String s){super(s);}}',
'external/QueryTest.java':'''package external;
import io.tvos.catalog.TmdbQueries;import java.util.*;import java.util.concurrent.atomic.*;import java.io.*;import org.json.*;
public class QueryTest {
 static class Card {final String key,value;Card(String k,String v){key=k;value=v;}}
 static final TmdbQueries.Mapper<Card> MAPPER=new TmdbQueries.Mapper<Card>(){
  public Card search(JSONObject item){if(item.optBoolean("broken"))throw new IllegalStateException();return item.optBoolean("skip")?null:new Card(item.optString("key"),item.optString("value"));}
  public void addMovie(List<Card> out,JSONObject item){if(!item.optBoolean("skip"))out.add(new Card("movie:"+item.optInt("id"),item.optString("value")));}
  public String key(Card card){return card.key;}
 };
 static class Source implements TmdbQueries.Source {
  final List<String> paths=new ArrayList<>();final List<JSONObject> replies=new ArrayList<>();int failAt=-1;Runnable hook;
  public JSONObject get(String path)throws IOException {int at=paths.size();paths.add(path);if(hook!=null)hook.run();if(at==failAt)throw new IOException("offline");if(at>=replies.size())throw new AssertionError("unexpected request "+path);return replies.get(at);}
 }
 static class Pages extends Random {private static final long serialVersionUID=1L;int page;Pages(int p){page=p;}public int nextInt(int n){check(n==15,"page range remains 15");return page;}}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static JSONObject card(String key,String value){return new JSONObject().put("key",key).put("value",value);}
 static JSONObject response(Object... rows){JSONArray items=new JSONArray();for(Object row:rows)items.put(row);return new JSONObject().put("results",items);}
 static TmdbQueries<Card> engine(Source source){return new TmdbQueries<>(source,()->"en-US",MAPPER,()->new Pages(0));}
 public static void main(String[] args)throws Exception {
  Source s=new Source();List<Card> out=new ArrayList<>();String[] language={"en-US"};s.replies.add(response(card("movie:1","old"),card("tv:1","series"),"bad row",card("skip","skip").put("skip",true)).put("total_pages",99));s.replies.add(response(card("movie:1","new"),card("movie:2","next")));
  // Language is read for each request; change it after the first transport completes.
  s.hook=()->language[0]="ru-RU";TmdbQueries<Card> query=new TmdbQueries<>(s,()->language[0],MAPPER);
  query.search("  A & B  ",out,()->true);check(s.paths.size()==2&&s.paths.get(0).contains("language=en-US")&&s.paths.get(1).contains("language=ru-RU"),"two-page bound and live language");check(s.paths.get(0).endsWith("query=A+%26+B&page=1")&&s.paths.get(1).endsWith("&page=2"),"trimmed UTF-8 URL and page order");check(out.size()==3&&out.get(0).key.equals("movie:1")&&out.get(0).value.equals("new")&&out.get(1).key.equals("tv:1")&&out.get(2).key.equals("movie:2"),"type identity, replacement without reorder, skipped rows");
  s=new Source();s.replies.add(response());s.replies.add(response());s.replies.add(response(card("found","suffix")));out=new ArrayList<>();engine(s).search("Тест",out,()->true);check(s.paths.size()==3&&s.paths.get(0).contains("query=%D0%A2%D0%B5%D1%81%D1%82&page=1")&&s.paths.get(1).contains("%D1%8B&page=1")&&s.paths.get(2).contains("%D0%B8&page=1")&&out.size()==1,"suffix order and stopping after match");
  for(String text:new String[]{"abc","1234","test?","😀😀","  "}){s=new Source();s.replies.add(response());engine(s).search(text,new ArrayList<>(),()->true);check(s.paths.size()==1,"short/nonletter/empty query gets no suffix variants");}
  s=new Source();for(int i=0;i<5;i++)s.replies.add(new JSONObject());engine(s).search("words",new ArrayList<>(),()->true);check(s.paths.size()==5&&s.paths.get(4).contains("query=words%D0%BE%D0%B2"),"missing results ends candidate pages but retains suffix fallback");
  s=new Source();s.replies.add(response(card("movie:1","kept")).put("total_pages",2));s.failAt=1;out=new ArrayList<>();boolean failed=false;try{engine(s).search("test",out,()->true);}catch(IOException expected){failed=true;}check(failed&&out.size()==1&&out.get(0).value.equals("kept"),"partial decoded search survives IO failure");
  s=new Source();s.replies.add(response(card("first","kept"),card("broken","").put("broken",true)));out=new ArrayList<>();failed=false;try{engine(s).search("test",out,()->true);}catch(IllegalStateException expected){failed=true;}check(failed&&out.size()==1,"partial search survives mapper failure");
  s=new Source();out=new ArrayList<>();engine(s).search("test",out,()->false);check(s.paths.isEmpty()&&out.isEmpty(),"cancel-before-search performs no transport");
  AtomicBoolean active=new AtomicBoolean(true);s=new Source();s.replies.add(response(card("one","ignored")));s.hook=()->active.set(false);out=new ArrayList<>();engine(s).search("test",out,active::get);check(s.paths.size()==1&&out.isEmpty(),"cancellation after response suppresses decoding and further pages");
  s=new Source();failed=false;try{engine(s).search(null,new ArrayList<>(),()->true);}catch(NullPointerException expected){failed=true;}check(failed&&s.paths.isEmpty(),"invalid search remains a search failure, never discover");
  s=new Source();for(int i=0;i<5;i++)s.replies.add(response(new JSONObject().put("id",7).put("value","one")));out=new ArrayList<>();engine(s).acclaimed(out,()->true);check(s.paths.size()==5&&out.size()==1&&out.get(0).key.equals("movie:7"),"five attempts, ID dedup, repeated random pages permitted");check(s.paths.get(0).equals("/discover/movie?language=en-US&sort_by=vote_average.desc&vote_count.gte=1000&vote_average.gte=7&include_adult=false&page=1"),"discover endpoint/filter values unchanged");
  s=new Source();Object[] many=new Object[40];for(int i=0;i<many.length;i++)many[i]=new JSONObject().put("id",i);s.replies.add(response(many));out=new ArrayList<>();new TmdbQueries<>(s,()->"en-US",MAPPER,()->new Pages(14)).acclaimed(out,()->true);check(out.size()==40&&s.paths.size()==1&&s.paths.get(0).endsWith("page=15"),"36 threshold checked between complete pages, upper page bound retained");
  s=new Source();s.replies.add(response(new JSONObject().put("id",1).put("skip",true),"bad row"));s.replies.add(response(new JSONObject().put("id",1),new JSONObject().put("id",2)));s.failAt=2;out=new ArrayList<>();failed=false;try{engine(s).acclaimed(out,()->true);}catch(IOException expected){failed=true;}check(failed&&out.size()==1&&out.get(0).key.equals("movie:2"),"ID marked before mapping, valid partial discover survives error");
  active.set(true);s=new Source();s.replies.add(response(new JSONObject().put("id",1)));s.hook=()->active.set(false);out=new ArrayList<>();engine(s).acclaimed(out,active::get);check(s.paths.size()==1&&out.isEmpty(),"discover cancels before mapping/next page");
  s=new Source();s.replies.add(response(many));out=new ArrayList<>();new TmdbQueries<>(s,()->"en-US",MAPPER,()->new Pages(0),()->"FR").acclaimed(out,()->true);check(s.paths.get(0).contains("&region=FR"),"roulette uses selected movie release country");
  System.out.println("PASS: actual generic query engine, URLs/live locale, suffixes/pages, ordered replacement, partial IO/mapper failures, cancellation, roulette bounds/ID policy and complete-page threshold");
 }
}'''}
sources['io/tvos/catalog/TmdbQueries.java']=Path('app/src/io/tvos/catalog/TmdbQueries.java').read_text()
sources['io/tvos/catalog/RecommendationRegion.java']=Path('app/src/io/tvos/catalog/RecommendationRegion.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-tmdb-queries-') as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'external.QueryTest'],check=True)
