# Run from project root with Python 3 and JDK 17+.
# Production methods exercised with deterministic Android/network stubs.
from settings_strings_fixture import write as write_strings
import os, subprocess, tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix="tvos-resource-test-") as directory:
    destination=Path(directory)
    from pathlib import Path
    s=Path('app/src/io/tvos/SettingsActivity.java').read_text()
    def method(sig):
     a=s.index(sig);b=s.index('{',a);d=1;i=b+1
     while d:
      if s[i]=='{':d+=1
      elif s[i]=='}':d-=1
      i+=1
     return s[a:i]
    header='''package io.tvos;
    import java.util.*;import java.util.concurrent.*;import android.os.Handler;
    public class CityHarness {
     static final int GENERAL=1,HOME_SETTINGS=2,REGION=23,PICK_CITY=0;
     static class Prefs {static boolean automatic;static boolean automaticRegion(){return automatic;}}
     static class Ui {static boolean ru;static String tr(String p,String e){return e;} }
     static class Choreographer {static Choreographer getInstance(){return new Choreographer();}void removeFrameCallback(Object o){} }
     static class Spoken {void focusMoved(){} }
     static class Dialog {boolean paused;void pause(){paused=true;}}final Dialog updateDialog=new Dialog();
     static class Shadows {boolean cleared;void clear(){cleared=true;}}
     Shadows rowShadows=new Shadows();Object descriptionLayout=new Object();String descriptionText="cached";
 Object getContext(){return this;}
 static final ThreadPoolExecutor CITY_IO=executor();
 static ThreadPoolExecutor executor(){try{java.lang.reflect.Field f=SettingsCitySearch.class.getDeclaredField("IO");f.setAccessible(true);return (ThreadPoolExecutor)f.get(null);}catch(Exception e){throw new AssertionError(e);}}
     Handler handler=new Handler();Spoken spoken=new Spoken();int screen=GENERAL,pickers;boolean regionWasOpen;boolean running=true;
 final SettingsCitySearch citySearch=new SettingsCitySearch(handler,()->running,()->screen,this::showCityResults,this::invalidate);
     List<String[]> cities=new ArrayList<>();Runnable poll=()->{};
     void invalidate(){}static class Background {void clear(){}}final Background background=new Background();void openPicker(int id){pickers++;}
     static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
     void flush()throws Exception{CITY_IO.submit(()->{}).get(3,TimeUnit.SECONDS);}
    '''
    tests='''
     public static void main(String[] args)throws Exception {
      CityHarness h=new CityHarness();
      try {
       h.citySearch.search("old");h.flush();h.pause();h.handler.drain();check(h.pickers==0,"paused settings suppress city callback");
       check(h.updateDialog.paused,"pause delegates update listener lifecycle");
       check(h.rowShadows.cleared && h.descriptionLayout==null && h.descriptionText==null,"pause drops shadow and description caches");
       h.running=true;h.citySearch.search("different-screen");h.flush();h.screen=99;h.handler.drain();check(h.pickers==0 && h.citySearch.status().isEmpty(),"navigation suppresses picker");
       h.screen=GENERAL;CountDownLatch gate=new CountDownLatch(1),entered=new CountDownLatch(1);
       CITY_IO.execute(()->{entered.countDown();try{gate.await(3,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});entered.await(3,TimeUnit.SECONDS);
       h.citySearch.search("first");h.citySearch.search("latest");check(CITY_IO.getQueue().size()==1,"old queued city removed");gate.countDown();h.flush();h.handler.drain();
       check(h.pickers==1 && h.cities.get(0)[0].equals("latest"),"only latest city delivered");
       h.citySearch.search("empty");h.flush();h.handler.drain();check(!h.citySearch.searching()&&!h.citySearch.status().isEmpty(),"empty result exposes status");
       h.handler.expire();check(h.citySearch.status().isEmpty(),"status expires after four seconds");
       h.citySearch.search("error");h.flush();h.handler.drain();check(h.citySearch.status().contains("offline"),"failure status exposed");
       h.pause();check(h.handler.delayed.isEmpty()&&h.citySearch.status().isEmpty(),"pause cancels status timer");
       h.running=true;h.citySearch.search(" ");h.flush();h.handler.drain();check(h.pickers==1,"blank query is no-op");
       h.screen=REGION;h.citySearch.search("region-city");h.flush();h.handler.drain();check(h.pickers==2&&h.cities.get(0)[0].equals("region-city"),"new Region submenu displays real search results");
       h.citySearch.search("stale-manual");h.flush();Prefs.automatic=true;h.handler.drain();check(h.pickers==2,"enabling automatic region rejects a pending manual result");Prefs.automatic=false;
       System.out.println("PASS: paused city callback, navigation guard, cancelled city queue, latest city delivery");
      } finally {CITY_IO.shutdownNow();}
     }
    }
    '''
    (destination/'CityHarness.java').write_text(header+method('        void pause()')+method('        private void showCityResults(')+tests)

    adapters={
     'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {public List<Runnable> tasks=Collections.synchronizedList(new ArrayList<>()),delayed=new ArrayList<>();public void post(Runnable r){tasks.add(r);}public void postDelayed(Runnable r,int ms){if(ms!=4000)throw new AssertionError("status timeout");delayed.add(r);}public void removeCallbacks(Runnable r){tasks.removeIf(v->v==r);delayed.removeIf(v->v==r);}public void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}public void expire(){for(Runnable r:new ArrayList<>(delayed))r.run();delayed.clear();}}''',
     'io/tvos/AppLog.java':'package io.tvos;final class AppLog {static void w(String s){}}',
     'io/tvos/HomeData.java':'''package io.tvos;import java.util.*;final class HomeData {static List<String[]> findCity(String s)throws Exception {if(s.equals("error"))throw new Exception("offline");if(s.equals("empty"))return Collections.emptyList();return Collections.singletonList(new String[]{s});}}''',
     'io/tvos/SettingsCitySearch.java':Path('app/src/io/tvos/SettingsCitySearch.java').read_text(),
    }
    for name,content in adapters.items():
     path=destination/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    write_strings(destination)
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', str(destination), 'app/src/io/tvos/UiRequest.java', *map(str,destination.rglob('*.java'))], check=True)
    subprocess.run([java, '-cp', str(destination), 'io.tvos.CityHarness'], check=True)
