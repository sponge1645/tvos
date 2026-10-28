# Run from project root with Python 3 and JDK 17+.
# Production methods exercised with deterministic Android/network stubs.
import os, subprocess, tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix="tvos-resource-test-") as directory:
    destination=Path(directory)
    from pathlib import Path
    s=Path('app/src/io/tvos/HomeData.java').read_text()
    def method(sig):
     a=s.index(sig);b=s.index('{',a);d=1;i=b+1
     while d:
      if s[i]=='{':d+=1
      elif s[i]=='}':d-=1
      i+=1
     return s[a:i]
    header='''import java.util.*;
    public class WeatherHarness {
     static class Prefs {static String lat="1",lon="2";static String latitude(){return lat;}static String longitude(){return lon;} }
     static class AppLog {static void w(String s){} }
     static class Worker {List<Runnable> q=new ArrayList<>();void execute(Runnable r){q.add(r);}void drain(){while(!q.isEmpty())q.remove(0).run();} }
     static class Cache {Map<String,Object> values=new HashMap<>();Cache edit(){return this;}Cache putString(String k,String v){values.put(k,v);return this;}Cache putLong(String k,long v){values.put(k,v);return this;}void apply(){} }
     static class Weather {String body;Weather(String s){body=s;} }
     static final long PRICES_EVERY=300000,WEATHER_EVERY=1800000;
     static final String WEATHER_QUERY="&forecast";
     final Worker io=new Worker();final Cache cache=new Cache();int settingsEpoch,notices,parsedPrices,calls;
     boolean fetchingPrices,fetchingWeather,quotes;
     long pricesAt,weatherAt,pricesFailedAt,weatherFailedAt;Weather weather=new Weather("initial");
     Runnable onGet;String requested;
     int coinCount(){return quotes?1:0;} String pricesUrl(){return "prices";}
     String get(String url){calls++;requested=url;if(onGet!=null)onGet.run();return url;}
     void parsePrices(String body){parsedPrices++;} Weather parseWeather(String body){return new Weather(body);}
     void notifyListeners(){notices++;}
    '''
    tests='''
     static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
     public static void main(String[] args){
      WeatherHarness h=new WeatherHarness();h.refreshIfStale();h.refreshIfStale();check(h.io.q.size()==1,"deduplicate weather refresh");
      h.onGet=()->{Prefs.lat="3";Prefs.lon="4";h.settingsChanged();};h.io.drain();
      check(h.weather.body.equals("initial") && h.weatherAt==0 && h.cache.values.isEmpty() && h.notices==0,"old city cannot commit after settings change");
      check(!h.fetchingWeather,"stale request clears fetching flag");h.onGet=null;h.refreshIfStale();h.io.drain();
      check("3,4".equals(h.cache.values.get("weatherPlace")) && h.weather.body.contains("latitude=3"),"new city's weather stored with matching identity");
      h.settingsChanged();int calls=h.calls;h.refreshIfStale();h.settingsChanged();h.io.drain();check(h.calls==calls,"obsolete queued weather skips network");
      h.quotes=true;h.onGet=()->h.settingsChanged();h.refreshIfStale();h.io.drain();check(h.parsedPrices==0 && h.pricesAt==0,"stale prices cannot overwrite refreshed settings");
      h.onGet=null;h.refreshIfStale();h.io.drain();check(h.parsedPrices==1,"current price update succeeds");
      System.out.println("PASS: weather deduplication, stale queued/in-flight rejection, coordinate identity, stale prices, recovery");
     }
    }
    '''
    (destination/'WeatherHarness.java').write_text(header+method('    private static String weatherPlace()')+method('    synchronized void settingsChanged()')+method('    synchronized void refreshIfStale()')+method('    private static boolean failedRecently(')+tests)

    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', str(destination), str(destination / 'WeatherHarness.java')], check=True)
    subprocess.run([java, '-cp', str(destination), 'WeatherHarness'], check=True)
