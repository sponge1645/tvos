# Run from project root with Python 3 and JDK 17+.
# Production methods exercised with deterministic Android/network stubs.
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
    import java.util.*;import java.util.concurrent.*;
    public class CityHarness {
     static final int GENERAL=1,HOME_SETTINGS=2,PICK_CITY=0;
     static class Ui {static boolean ru;static String tr(String p,String e){return e;} }
     static class AppLog {static void w(String s){} }
     static class HomeData {static List<String[]> findCity(String s){return Collections.singletonList(new String[]{s});} }
     static class Handler {List<Runnable> tasks=Collections.synchronizedList(new ArrayList<>());void post(Runnable r){tasks.add(r);}void postDelayed(Runnable r,int ms){tasks.add(r);}void removeCallbacks(Runnable r){tasks.removeIf(v->v==r);}void drain(){while(!tasks.isEmpty())tasks.remove(0).run();} }
     static class Choreographer {static Choreographer getInstance(){return new Choreographer();}void removeFrameCallback(Object o){} }
     static class Spoken {void focusMoved(){} }
     static class SelfUpdate {static SelfUpdate get(Object o){return new SelfUpdate();}void removeListener(Object o){}}
     static class Shadows {boolean cleared;void clear(){cleared=true;}}
     Shadows rowShadows=new Shadows();Object descriptionLayout=new Object();String descriptionText="cached";
 Object updateListener;Object getContext(){return this;}
 static final ThreadPoolExecutor CITY_IO=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
     Handler handler=new Handler();Spoken spoken=new Spoken();UiRequest cityRequest;int citySerial,screen=GENERAL,pickers;boolean running=true;
     String cityStatus="";List<String[]> cities=new ArrayList<>();Runnable poll=()->{};final Runnable clearCityStatus=()->{cityStatus="";if(running)invalidate();};
     void invalidate(){}void releaseBackground(){}void openPicker(int id){pickers++;}
     static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
     void flush()throws Exception{CITY_IO.submit(()->{}).get(3,TimeUnit.SECONDS);}
    '''
    tests='''
     public static void main(String[] args)throws Exception {
      CityHarness h=new CityHarness();
      try {
       h.searchCity("old");h.flush();h.pause();h.handler.drain();check(h.pickers==0,"paused settings suppress city callback");
       check(h.rowShadows.cleared && h.descriptionLayout==null && h.descriptionText==null,"pause drops shadow and description caches");
       h.running=true;h.searchCity("different-screen");h.flush();h.screen=99;h.handler.drain();check(h.pickers==0 && h.cityStatus.isEmpty(),"navigation suppresses picker");
       h.screen=GENERAL;CountDownLatch gate=new CountDownLatch(1),entered=new CountDownLatch(1);
       CITY_IO.execute(()->{entered.countDown();try{gate.await(3,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});entered.await(3,TimeUnit.SECONDS);
       h.searchCity("first");h.searchCity("latest");check(CITY_IO.getQueue().size()==1,"old queued city removed");gate.countDown();h.flush();h.handler.drain();
       check(h.pickers==1 && h.cities.get(0)[0].equals("latest"),"only latest city delivered");
       System.out.println("PASS: paused city callback, navigation guard, cancelled city queue, latest city delivery");
      } finally {CITY_IO.shutdownNow();}
     }
    }
    '''
    (destination/'CityHarness.java').write_text(header+method('        void pause()')+method('        private void searchCity(')+tests)

    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', str(destination), 'app/src/io/tvos/UiRequest.java', str(destination / 'CityHarness.java')], check=True)
    subprocess.run([java, '-cp', str(destination), 'io.tvos.CityHarness'], check=True)
