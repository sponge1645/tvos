from settings_strings_fixture import write as write_strings
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
  boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}public Store putBoolean(String k,boolean v){values.put(k,v);return this;}Store edit(){return this;}public Store putString(String k,String v){values.put(k,v);return this;}
  public Store putLong(String k,long v){values.put(k,v);return this;}public Store remove(String k){values.remove(k);return this;}public void apply(){}
 }
 static class Queue {List<Runnable> tasks=new ArrayList<>();void execute(Runnable r){tasks.add(r);}void post(Runnable r){tasks.add(r);}void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}}
 static class Prefs {
  static Store store=new Store();static Object app=new Object();static int version;
  static Store sp(){return store;}static String language(){return store.getString("language","en");}
  static void put(String k,String v){store.putString(k,v);version++;}
'''
prefbody=''.join(method(prefs,s) for s in ['    static void setLanguage(', '    static String cityRaw(', '    private static String cityPlace(', '    static long cityId(', '    static boolean hasCityName(', '    static String city()', '    static String latitude()', '    static String longitude()', '    static void setCity(String name, String latitude, String longitude)', '    static void setCity(String name, String latitude, String longitude, long id, String nameLanguage)', '    static boolean cacheCityName(', '    static boolean automaticRegion(', '    static long regionGeneration(', '    static String manualCity(', '    static void setAutomaticRegion(', '    static boolean applyAutomaticCity('])
mid='''}
 static class HomeData {
  static final io.tvos.weather.CityGeocoder CITIES=new io.tvos.weather.CityGeocoder(HomeData::get);
  static HomeData current=new HomeData();static HomeData get(Object context){return current;}
  final Set<String> cityNameRequests=new HashSet<>();Queue io=new Queue(),main=new Queue();int notices;
  static List<String> urls=new ArrayList<>();static Runnable onGet;static boolean fail;
  void settingsChanged(){}void notifyListeners(){notices++;}
  static String get(String url)throws Exception {urls.add(url);if(onGet!=null){Runnable r=onGet;onGet=null;r.run();}if(fail)throw new IOException("offline");if(url.contains("/search?") && url.contains("language=en") && url.contains("%D0%91%D0%B5%D1%80%D0%BB%D0%B8%D0%BD"))return "empty";return (url.contains("/get?")?"id-":"search-")+(url.contains("language=ru")?"ru":"en");}
'''
databody=''.join(method(data,s) for s in ['    private static String weatherPlace()', '    synchronized void localizeCity(', '    static List<String[]> findCity(String name)'])
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
  check(HomeData.CITIES.localize("Berlin","1","2",0,"ru",AppLanguage.searchOrder("ru"))==null,"same-named distant result rejected");
  reset();h=HomeData.current;Prefs.store.putString("city","Berlin").putString("latitude","52.5244").putString("longitude","13.4105").putString("city_name_en","Berlin").putString("city_names_place","52.5244,13.4105").putLong("city_id",2950159L).apply();
  check(!Prefs.automaticRegion(),"existing installs remain manual");Prefs.setAutomaticRegion(true);long generation=Prefs.regionGeneration();check(Prefs.automaticRegion()&&Prefs.manualCity().equals("Berlin"),"enabling auto snapshots manual city");
  check(Prefs.applyAutomaticCity("Paris","48.85","2.35","en",generation)&&Prefs.cityRaw().equals("Paris"),"auto coordinates feed real weather preferences");check(Prefs.manualCity().equals("Berlin"),"automatic lookup never overwrites manual selection");
  Prefs.setAutomaticRegion(false);check(Prefs.city().equals("Berlin")&&Prefs.latitude().equals("52.5244")&&Prefs.cityId()==2950159L,"disabling auto restores city, coordinates, ID and localized cache");check(!Prefs.applyAutomaticCity("Late","1","2","en",generation),"late reply after disabling rejected");Prefs.setAutomaticRegion(true);check(!Prefs.applyAutomaticCity("Late","1","2","en",generation),"old request rejected after off/on cycle");Prefs.setAutomaticRegion(false);
  // Use the production geocoder through an independent text source, without HomeData policy.
  List<String> requests=new ArrayList<>();String[] response={"custom"};io.tvos.weather.CityGeocoder geocoder=new io.tvos.weather.CityGeocoder(url->{requests.add(url);return response[0];});
  JSONObject first=new JSONObject().put("name","Paris").put("admin1","Paris").put("country","France").put("latitude",48.85661).put("longitude",2.35221).put("id",7L);
  JSONObject duplicate=new JSONObject().put("name","Paris").put("admin1","Paris").put("country","France").put("latitude",1).put("longitude",2).put("id",8L);
  JSONObject second=new JSONObject().put("name","Paris").put("admin1","Texas").put("country","USA").put("latitude",33.6609).put("longitude",-95.5555);
  JSONObject.all.put("custom",new JSONObject().put("results",new JSONArray().put(first).put(duplicate).put(new JSONObject().put("name","Missing coordinates")).put(new JSONObject().put("latitude",0).put("longitude",0)).put(second)));
  Locale previous=Locale.getDefault();Locale.setDefault(Locale.GERMANY);List<String[]> cities=geocoder.search("  Бельцы & Café  ","ru");Locale.setDefault(previous);
  check(requests.get(0).equals("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=ru&name=%D0%91%D0%B5%D0%BB%D1%8C%D1%86%D1%8B+%26+Caf%C3%A9"),"trimmed UTF-8 query and URL policy unchanged");
  check(cities.size()==2&&Arrays.equals(cities.get(0),new String[]{"Paris, France","Paris","48.8566","2.3522","7","ru"})&&cities.get(1)[0].equals("Paris, Texas, USA")&&cities.get(1)[4].equals("0"),"source order, duplicate labels, missing data and coordinate format retained");
  JSONObject.all.put("custom",new JSONObject().put("name","Bound").put("latitude",1.25).put("longitude",2.25));
  check(geocoder.localize("Bound","1","2",9,"ru",new String[]{"ru","en"})[1].equals("9")&&requests.get(requests.size()-1).equals("https://geocoding-api.open-meteo.com/v1/get?id=9&language=ru"),"stable ID permits coordinate boundary and preserves lookup URL");
  JSONObject.all.put("custom",new JSONObject().put("name","Far").put("latitude",1.251).put("longitude",2));check(geocoder.localize("Far","1","2",9,"ru",new String[]{"ru"})==null,"stable ID outside coordinate tolerance rejected");
  JSONObject.all.put("custom",new JSONObject().put("name","").put("latitude",1).put("longitude",2));check(geocoder.localize("Empty","1","2",9,"ru",new String[]{"ru"})==null,"empty localized name rejected");
  JSONObject near=new JSONObject().put("name","Near").put("latitude",1.01).put("longitude",2.01);JSONObject.all.put("custom",new JSONObject().put("results",new JSONArray().put(near)));requests.clear();
  check(geocoder.localize("Near","1","2",0,"ru",new String[]{"ru","en"})[0].equals("Near")&&requests.size()==1,"ID-less result uses its language and stops at first matching language");
  check(geocoder.localize("Near","1","2",0,"en",new String[]{"ru"})==null,"ID-less foreign-language result is not cached under requested language");
  io.tvos.weather.CityGeocoder failing=new io.tvos.weather.CityGeocoder(url->{throw new IOException("offline");});boolean propagated=false;try{failing.search("City","en");}catch(IOException expected){propagated=true;}check(propagated,"transport errors remain caller-owned");
  System.out.println("PASS: RU/EN cache, legacy migration, stable ID, unchanged coordinates, offline switching, deduplication, stale responses, retry");
 }
}'''
json='''package org.json;import java.util.*;public class JSONObject {public static Map<String,JSONObject> all=new HashMap<>();Map<String,Object> values=new HashMap<>();public JSONObject(){}public JSONObject(String key){values.putAll(all.get(key).values);}public JSONObject put(String k,Object v){values.put(k,v);return this;}public boolean has(String k){return values.containsKey(k);}public String optString(String k,String d){Object v=values.get(k);return v==null?d:v.toString();}public String getString(String k){return (String)values.get(k);}public double getDouble(String k){return ((Number)values.get(k)).doubleValue();}public long optLong(String k,long d){Object v=values.get(k);return v==null?d:((Number)v).longValue();}public JSONArray optJSONArray(String k){return (JSONArray)values.get(k);}}'''
array='''package org.json;import java.util.*;public class JSONArray {List<JSONObject> values=new ArrayList<>();public JSONArray put(JSONObject o){values.add(o);return this;}public int length(){return values.size();}public JSONObject getJSONObject(int n){return values.get(n);}}'''
shared='''package android.content;public interface SharedPreferences {interface Editor {Editor putBoolean(String k,boolean v);Editor putString(String k,String v);Editor putLong(String k,long v);Editor remove(String k);void apply();}}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);sources={'CityGeocoder.java':Path('app/src/io/tvos/weather/CityGeocoder.java').read_text(),'CityLocalizationHarness.java':header+prefbody+mid+databody+tests,'JSONObject.java':json,'JSONArray.java':array,'SharedPreferences.java':shared}


 write_strings(root)
 for name,source in sources.items():(root/name).write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.CityLocalizationHarness'],check=True)
