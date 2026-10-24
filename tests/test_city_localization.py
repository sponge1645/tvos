"""Exercise localized city persistence, real resolver selection and stale async response guards."""
from pathlib import Path
import subprocess,tempfile
prefs=Path('app/src/io/tvos/Prefs.java').read_text();data=Path('app/src/io/tvos/HomeData.java').read_text()
def method(source,sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:
  d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
header='''package io.tvos;import java.util.*;import java.net.URLEncoder;import java.io.*;import org.json.*;import android.content.SharedPreferences;
public class CityLocalizationHarness {
 static class Config {static String CITY="Berlin",LATITUDE="52.5244",LONGITUDE="13.4105";}
 static class AppLog {static void w(String s){}}
 static class Ui {static void applyLanguage(){}}
 static class Recs {static void languageChanged(){}}
 static class Store implements SharedPreferences.Editor {
  Map<String,Object> values=new HashMap<>();String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  long getLong(String k,long d){return ((Number)values.getOrDefault(k,d)).longValue();}
  Store edit(){return this;}public Store putString(String k,String v){values.put(k,v);return this;}
  public Store putLong(String k,long v){values.put(k,v);return this;}public Store remove(String k){values.remove(k);return this;}public void apply(){}
 }
 static class Queue {List<Runnable> tasks=new ArrayList<>();void execute(Runnable r){tasks.add(r);}void post(Runnable r){tasks.add(r);}void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}}
 static class Prefs {
  static Store store=new Store();static Object app=new Object();static int version;
  static Store sp(){return store;}static String language(){return store.getString("language","en");}
  static void put(String k,String v){store.putString(k,v);version++;}
'''
prefbody=''.join(method(prefs,s) for s in ['    static void setLanguage(', '    static String cityRaw(', '    private static String cityPlace(', '    static long cityId(', '    static boolean hasCityName(', '    static String city()', '    static String latitude()', '    static String longitude()', '    static void setCity(String name, String latitude, String longitude)', '    static void setCity(String name, String latitude, String longitude, long id, String nameLanguage)', '    static boolean cacheCityName('])
mid='''}
 static class HomeData {
  static HomeData current=new HomeData();static HomeData get(Object context){return current;}
  final Set<String> cityNameRequests=new HashSet<>();Queue io=new Queue(),main=new Queue();int notices;
  static List<String> urls=new ArrayList<>();static Runnable onGet;static boolean fail;
  void notifyListeners(){notices++;}
  static String get(String url)throws Exception {urls.add(url);if(onGet!=null){Runnable r=onGet;onGet=null;r.run();}if(fail)throw new IOException("offline");if(url.contains("/search?") && url.contains("language=en") && url.contains("%D0%91%D0%B5%D1%80%D0%BB%D0%B8%D0%BD"))return "empty";return (url.contains("/get?")?"id-":"search-")+(url.contains("language=ru")?"ru":"en");}
'''
databody=''.join(method(data,s) for s in ['    private static String weatherPlace()', '    synchronized void localizeCity(', '    private static String[] findCityName(', '    static List<String[]> findCity(String name)', '    private static List<String[]> findCity(String name, String lang)'])
tests='''}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void fixture(String lang,String name){JSONObject city=new JSONObject().put("id",2950159L).put("name",name).put("latitude",52.52437).put("longitude",13.41053);JSONObject.all.put("id-"+lang,city);JSONObject.all.put("search-"+lang,new JSONObject().put("results",new JSONArray().put(city)));}
 static void reset(){Prefs.store=new Store();Prefs.version=0;HomeData.current=new HomeData();HomeData.urls.clear();HomeData.fail=false;HomeData.onGet=null;}
 public static void main(String[] args)throws Exception{
  fixture("en","Berlin");fixture("ru","Берлин");JSONObject.all.put("empty",new JSONObject().put("results",new JSONArray()));
  reset();HomeData h=HomeData.current;
  Prefs.setLanguage("ru");h.localizeCity("ru");check(h.io.tasks.size()==1,"duplicate lookup deduplicated");
  h.io.drain();h.main.drain();check(Prefs.city().equals("Берлин") && Prefs.cityId()==2950159L,"legacy city localized and migrated to stable ID");
  check(Prefs.latitude().equals(Config.LATITUDE) && Prefs.longitude().equals(Config.LONGITUDE),"label translation never moves weather location");
  check(h.notices==1,"visible language update notifies weather widget");
  Prefs.setLanguage("en");h.io.drain();h.main.drain();check(Prefs.city().equals("Berlin") && HomeData.urls.get(HomeData.urls.size()-1).contains("/get?id=2950159"),"second language resolved by same ID");
  int calls=HomeData.urls.size();HomeData.fail=true;Prefs.setLanguage("ru");Prefs.setLanguage("en");
  check(Prefs.city().equals("Berlin") && h.io.tasks.isEmpty() && HomeData.urls.size()==calls,"both cached names switch immediately offline");
  int before=Prefs.version;check(!Prefs.cacheCityName("Wrong","en","1","2",2950159L) && Prefs.version==before,"stale coordinates cannot overwrite cache");
  check(!Prefs.cacheCityName("Wrong","en",Config.LATITUDE,Config.LONGITUDE,99),"different ID at same coordinates rejected");
  Prefs.setCity("Paris","48.8566","2.3522",2988507L,"en");
  check(Prefs.city().equals("Paris") && !Prefs.hasCityName("ru"),"new city clears previous city's localized labels");
  reset();h=HomeData.current;Prefs.setLanguage("ru");HomeData.onGet=()->{Prefs.store.putString("latitude","48.8566").putString("longitude","2.3522").putString("city","Paris").putLong("city_id",2988507).apply();};
  h.io.drain();h.main.drain();check(Prefs.city().equals("Paris") && h.notices==0 && !Prefs.hasCityName("ru"),"in-flight old-city response suppressed");
  reset();h=HomeData.current;Prefs.setLanguage("ru");Prefs.store.putString("latitude","1");h.io.drain();h.main.drain();check(HomeData.urls.isEmpty(),"obsolete queued lookup skips network");
  reset();h=HomeData.current;HomeData.fail=true;Prefs.setLanguage("ru");h.io.drain();h.main.drain();check(Prefs.city().equals("Berlin") && h.cityNameRequests.isEmpty(),"network failure retains original label and permits retry");
  HomeData.fail=false;h.localizeCity("ru");h.io.drain();h.main.drain();check(Prefs.city().equals("Берлин"),"retry recovers");
  reset();h=HomeData.current;Prefs.store.putString("city","Берлин").putString("language","en");
  h.localizeCity(Prefs.language());h.io.drain();h.main.drain();
  check(Prefs.city().equals("Berlin") && HomeData.urls.size()==3,"Cyrillic legacy name migrates via Russian search and English ID lookup on resume");
  check(HomeData.urls.get(0).contains("language=en") && HomeData.urls.get(1).contains("language=ru") && HomeData.urls.get(2).contains("/get?"),"fallback query order matches real geocoder failure");
  check(HomeData.findCityName("Berlin","1","2",0,"ru")==null,"same-named distant result rejected");
  System.out.println("PASS: RU/EN cache, legacy migration, stable ID, unchanged coordinates, offline switching, deduplication, stale responses, retry");
 }
}'''
json='''package org.json;import java.util.*;public class JSONObject {public static Map<String,JSONObject> all=new HashMap<>();Map<String,Object> values=new HashMap<>();public JSONObject(){}public JSONObject(String key){values.putAll(all.get(key).values);}public JSONObject put(String k,Object v){values.put(k,v);return this;}public boolean has(String k){return values.containsKey(k);}public String optString(String k,String d){Object v=values.get(k);return v==null?d:v.toString();}public String getString(String k){return (String)values.get(k);}public double getDouble(String k){return ((Number)values.get(k)).doubleValue();}public long optLong(String k,long d){Object v=values.get(k);return v==null?d:((Number)v).longValue();}public JSONArray optJSONArray(String k){return (JSONArray)values.get(k);}}'''
array='''package org.json;import java.util.*;public class JSONArray {List<JSONObject> values=new ArrayList<>();public JSONArray put(JSONObject o){values.add(o);return this;}public int length(){return values.size();}public JSONObject getJSONObject(int n){return values.get(n);}}'''
shared='''package android.content;public interface SharedPreferences {interface Editor {Editor putString(String k,String v);Editor putLong(String k,long v);Editor remove(String k);void apply();}}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);sources={'CityLocalizationHarness.java':header+prefbody+mid+databody+tests,'JSONObject.java':json,'JSONArray.java':array,'SharedPreferences.java':shared}
 for name,source in sources.items():(root/name).write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*[str(root/n) for n in sources]],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.CityLocalizationHarness'],check=True)
