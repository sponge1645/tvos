"""Execute the production forecast parser with deterministic time and JSON access adapters."""
from pathlib import Path
import subprocess, tempfile
sources={
'org/json/JSONObject.java':'''package org.json;import java.util.*;public class JSONObject {
 public static final Map<String,JSONObject> docs=new HashMap<>();final Map<String,Object> values=new HashMap<>();
 public JSONObject(){}public JSONObject(String s){JSONObject o=docs.get(s);if(o==null)throw new IllegalArgumentException("JSON");values.putAll(o.values);}
 public JSONObject put(String k,Object v){values.put(k,v);return this;}public JSONObject remove(String k){values.remove(k);return this;}
 Object get(String k){if(!values.containsKey(k))throw new IllegalArgumentException(k);return values.get(k);}
 public JSONObject getJSONObject(String k){return (JSONObject)get(k);}public JSONObject optJSONObject(String k){return (JSONObject)values.get(k);}
 public JSONArray getJSONArray(String k){return (JSONArray)get(k);}public JSONArray optJSONArray(String k){return (JSONArray)values.get(k);}
 public double getDouble(String k){return ((Number)get(k)).doubleValue();}public int getInt(String k){return ((Number)get(k)).intValue();}
 public String optString(String k,String d){Object v=values.get(k);return v==null?d:v.toString();}public int optInt(String k,int d){Object v=values.get(k);return v instanceof Number?((Number)v).intValue():d;}
}''',
'org/json/JSONArray.java':'''package org.json;import java.util.*;public class JSONArray {
 final List<Object> values=new ArrayList<>();public JSONArray(Object... v){values.addAll(Arrays.asList(v));}public int length(){return values.size();}
 public String getString(int i){return (String)values.get(i);}public double getDouble(int i){return ((Number)values.get(i)).doubleValue();}public int getInt(int i){return ((Number)values.get(i)).intValue();}
 public int optInt(int i,int d){return i>=0&&i<values.size()&&values.get(i) instanceof Number?((Number)values.get(i)).intValue():d;}
}''',
'external/ParserTest.java':'''package external;
import io.tvos.weather.*;import org.json.*;import java.util.*;import java.time.*;import java.util.concurrent.*;
public class ParserTest {
 static int reads;static long now;static final WeatherParser parser=new WeatherParser(()->{reads++;return now;});
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static long at(String date,String zone){return LocalDateTime.parse(date).atZone(ZoneId.of(zone)).toInstant().toEpochMilli();}
 static JSONObject daily(String rise,String set){return new JSONObject().put("temperature_2m_max",new JSONArray(20.5)).put("temperature_2m_min",new JSONArray(-3.5)).put("sunrise",new JSONArray(rise)).put("sunset",new JSONArray(set));}
 static JSONObject body(String zone){JSONObject o=new JSONObject().put("current",new JSONObject().put("temperature_2m",12.5).put("weather_code",3)).put("daily",daily("2026-11-02T06:00","2026-11-02T18:00"));if(zone!=null)o.put("timezone",zone);return o;}
 static JSONObject hourly(Object... times){Object[] temps=new Object[times.length],codes=new Object[times.length];for(int i=0;i<times.length;i++){temps[i]=i+.25;codes[i]=i+10;}return new JSONObject().put("time",new JSONArray(times)).put("temperature_2m",new JSONArray(temps)).put("weather_code",new JSONArray(codes));}
 static WeatherForecast parse(JSONObject o)throws Exception{JSONObject.docs.put("body",o);return parser.parse("body");}
 static void fails(JSONObject o,String why)throws Exception{boolean failed=false;try{parse(o);}catch(Exception expected){failed=true;}check(failed,why);}
 public static void main(String[] args)throws Exception{
  TimeZone saved=TimeZone.getDefault();Locale language=Locale.getDefault();try{
   Locale.setDefault(Locale.forLanguageTag("ar"));TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"));
   WeatherForecast defaults=new WeatherForecast();check(Double.isNaN(defaults.temp)&&Double.isNaN(defaults.min)&&Double.isNaN(defaults.max)&&defaults.code==-1&&defaults.isDay&&defaults.sunrise==0&&defaults.hours.isEmpty(),"original model defaults");defaults.hours.add(1L);check(defaults.hours.size()==1,"snapshot lists remain mutable");
   reads=0;WeatherForecast w=parse(body("Europe/Berlin"));check(w.temp==12.5&&w.max==20.5&&w.min==-3.5&&w.code==3&&w.isDay,"Celsius/current/daily fields and default daylight");check(w.sunrise==at("2026-11-02T06:00","Europe/Berlin")&&w.sunset==at("2026-11-02T18:00","Europe/Berlin"),"response timezone wins over device timezone");check(reads==0&&w.hours.isEmpty(),"no hourly section never reads clock");
   w=parse(body(null));check(w.sunrise==at("2026-11-02T06:00","Asia/Kathmandu"),"missing timezone uses device timezone");w=parse(body("Unknown/Zone"));check(w.sunrise==at("2026-11-02T06:00","UTC"),"unknown timezone retains TimeZone GMT fallback");
   JSONObject o=body("UTC");o.getJSONObject("current").put("is_day",0);now=at("2026-11-02T08:00","UTC");
   o.put("hourly",hourly("2026-11-02T07:00","2026-11-02T08:00","2026-11-02T09:00","2026-11-02T10:00","2026-11-02T11:00","2026-11-02T12:00","2026-11-02T13:00","2026-11-02T14:00","malformed-after-six"));
   o.getJSONObject("hourly").put("is_day",new JSONArray(0,0,0));reads=0;w=parse(o);check(reads==1&&!w.isDay&&w.hours.size()==6,"clock sampled once, six strictly future hours only");check(w.hours.get(0)==at("2026-11-02T09:00","UTC")&&w.hours.get(5)==at("2026-11-02T14:00","UTC")&&w.hourTemp.get(0)==2.25&&w.hourCode.get(5)==17,"hourly indexes align with source after past/equal filtering");check(!w.hourDay.get(0)&&w.hourDay.get(1),"short day array retains default daylight");
   o=body("UTC").put("hourly",hourly("2026-11-02T10:00","2026-11-02T09:00"));w=parse(o);check(w.hours.get(0)>w.hours.get(1)&&w.hourDay.get(0),"provider order preserved, absent day array defaults true");
   o=body("UTC").put("hourly",hourly("2026-11-02T07:00"));o.getJSONObject("hourly").put("temperature_2m",new JSONArray()).put("weather_code",new JSONArray());w=parse(o);check(w.hours.isEmpty(),"past values filtered before temperature/code indexing");
   reads=0;o=body("UTC").put("hourly",hourly());w=parse(o);check(reads==1&&w.hours.isEmpty(),"empty hourly section still samples clock at old location");
   reads=0;o=body("UTC").put("hourly",hourly());o.getJSONObject("hourly").remove("weather_code");fails(o,"missing required hourly array rejected");check(reads==0,"required hourly arrays are read before clock");
   reads=0;o=body("UTC");o.getJSONObject("daily").put("sunrise",new JSONArray("invalid"));fails(o,"malformed daily timestamp rejected");check(reads==0,"daily errors do not sample hourly clock");
   o=body("UTC").put("daily",daily("2026-11-02T25:00","2026-11-03T18:00suffix"));w=parse(o);check(w.sunrise==at("2026-11-03T01:00","UTC")&&w.sunset==at("2026-11-03T18:00","UTC"),"legacy lenient hours and trailing text retained");
   o=body("Europe/Berlin").put("daily",daily("2026-03-29T02:30","2026-10-25T02:30"));w=parse(o);check(w.sunrise==at("2026-03-29T03:30","Europe/Berlin")&&w.sunset==Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(),"DST gap and overlap match legacy lenient calendar");
   now=0;fails(body("UTC").put("hourly",hourly("bad-time")),"malformed future time rejected");fails(body("UTC").put("hourly",hourly("2026-11-02T09:00").put("temperature_2m",new JSONArray())),"incomplete future temperature array rejected");
   ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<Long>> jobs=new ArrayList<>();JSONObject.docs.put("utc",body("UTC"));JSONObject.docs.put("berlin",body("Europe/Berlin"));for(int i=0;i<50;i++){final boolean utc=i%2==0;jobs.add(pool.submit(()->parser.parse(utc?"utc":"berlin").sunrise));}for(int i=0;i<jobs.size();i++)check(jobs.get(i).get()==at("2026-11-02T06:00",i%2==0?"UTC":"Europe/Berlin"),"date formatter is response-local");}finally{pool.shutdownNow();}
   o=body("Europe/Berlin");JSONObject dd=o.getJSONObject("daily");
   dd.put("time",new JSONArray("2026-11-02","2026-11-03","2026-11-04","2026-11-05","2026-11-06","2026-11-07"));
   dd.put("temperature_2m_min",new JSONArray(-2.0,0.0,1.0,2.0,3.0,4.0)).put("temperature_2m_max",new JSONArray(5.0,6.0,7.0,8.0,9.0,10.0)).put("weather_code",new JSONArray(0,3,61,71,95,2)).put("precipitation_probability_max",new JSONArray(0,20,60));
   o.getJSONObject("current").put("relative_humidity_2m",84).put("wind_speed_10m",13.5);w=parse(o);
   check(w.days.size()==5&&w.days.get(2).code==61&&w.days.get(2).rain==60&&w.days.get(4).rain==-1,"five-day cap and optional rain");
   check(w.days.get(0).time==at("2026-11-02T00:00","Europe/Berlin")&&w.days.get(4).min==3&&w.days.get(4).max==9,"daily dates use forecast timezone and preserve Celsius");
   check(w.humidity==84&&w.wind==13.5&&w.timezone.equals("Europe/Berlin"),"current humidity/wind and timezone retained");
   System.out.println("PASS: real weather parser, model defaults/mutability, timezone/DST/lenient dates, future-hour cap/order/indexes, clock timing, malformed data and concurrent isolation");
  }finally{TimeZone.setDefault(saved);Locale.setDefault(language);}
 }
}'''}
for name in ['WeatherForecast','WeatherParser']:
 sources['io/tvos/weather/'+name+'.java']=Path('app/src/io/tvos/weather',name+'.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-weather-parser-') as tmp:
 root=Path(tmp)
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'external.ParserTest'],check=True)
