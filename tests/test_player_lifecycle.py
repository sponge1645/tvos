# Run from project root with Python 3 and JDK 17+.
# Production methods exercised with deterministic Android/network stubs.
import os, subprocess, tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix="tvos-resource-test-") as directory:
    destination=Path(directory)
    from pathlib import Path
    s=Path('app/src/io/tvos/VideoDream.java').read_text()
    def method(sig):
     a=s.index(sig);b=s.index('{',a);d=1;i=b+1
     while d:
      if s[i]=='{':d+=1
      elif s[i]=='}':d-=1
      i+=1
     return s[a:i]
    header='''import java.util.*;import java.util.function.*;
    public class PlayerHarness {
     static class AppLog {static void w(String s){} }
     static class Uri {static String parse(String s){return s;} }
     static class View {static final int VISIBLE=0,GONE=8;int getVisibility(){return 0;}View animate(){return this;}void cancel(){}View alpha(float a){return this;}View setDuration(int n){return this;}void start(){}void setAlpha(float a){}void setText(String s){}void setVisibility(int n){} }
     static class Surface extends View {Object getHolder(){return this;} }
     static class Handler {List<Runnable> tasks=new ArrayList<>();void postDelayed(Runnable r,long ms){tasks.add(r);}void removeCallbacks(Runnable r){tasks.removeIf(v->v==r);} }
     static class MediaPlayer {
      static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING=2;
      interface Error {boolean fire(MediaPlayer p,int a,int b);}
      Consumer<MediaPlayer> prepared,completed;Error error;boolean released,started;int scaling;
      void setDisplay(Object o){}void setAudioStreamType(int i){}void setVolume(int a,int b){}void setScreenOnWhilePlaying(boolean b){}
      void setOnPreparedListener(Consumer<MediaPlayer> c){prepared=c;}void setOnCompletionListener(Consumer<MediaPlayer> c){completed=c;}void setOnErrorListener(Error c){error=c;}
      void setVideoScalingMode(int m){if(released)throw new AssertionError("access to released player");scaling++;}
      void start(){if(released)throw new AssertionError("start after release");started=true;}int getDuration(){return 5000;}
      void setDataSource(Object o,String s){}void prepareAsync(){}void reset(){}void release(){released=true;}
     }
     Handler handler=new Handler();MediaPlayer player;Runnable prepareTimeout,fadeOut;final Runnable retryPlayback=this::playNext;
     boolean running=true,surfaceReady=true,preparing,appleMode=true;Object fallback;
     int generation,index,failures;List<String> urls=new ArrayList<>(Arrays.asList("one","two","three"));Map<String,String> locations=new HashMap<>();
     Surface surface=new Surface();View videoFade=new View(),aerialLocation=new View(),aerialWeather=new View();
     Object getContext(){return this;}boolean showAerialLocation(){return true;}String aerialPlace(String s){return "";}void updateAerialWeather(){}void showFallback(){releasePlayer();fallback=new Object();}
    '''
    tests='''
     static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
     public static void main(String[] args){
      PlayerHarness h=new PlayerHarness();h.playNext();MediaPlayer old=h.player;
      check(h.handler.tasks.size()==1 && h.preparing,"prepare timeout scheduled");
      old.prepared.accept(old);check(old.started && old.scaling==1 && !h.preparing,"prepared player starts");
      check(h.handler.tasks.size()==1 && h.prepareTimeout==null && h.fadeOut!=null,"timeout replaced by fade");
      h.releasePlayer();check(h.handler.tasks.isEmpty() && old.released && h.player==null,"release clears pending tasks");
      old.prepared.accept(old);old.completed.accept(old);old.error.fire(old,1,2);
      check(old.scaling==1 && h.index==0 && h.handler.tasks.isEmpty(),"stale callbacks cannot touch released player or advance playlist");
      h.playNext();MediaPlayer failing=h.player;failing.error.fire(failing,1,2);
      check(h.player==null && h.index==1 && h.handler.tasks.size()==1,"failure leaves only retry");
      failing.error.fire(failing,1,2);check(h.index==1 && h.handler.tasks.size()==1,"repeated stale error ignored");
      h.releasePlayer();check(h.handler.tasks.isEmpty(),"release clears playback retry");
      h.running=false;h.playNext();check(h.player==null,"stopped player does not restart");
      System.out.println("PASS: preparation, timeout/fade cleanup, stale media callbacks, retry cleanup, stopped playback");
     }
    }
    '''
    (destination/'PlayerHarness.java').write_text(header+method('    private void releasePlayer()')+method('    private void playNext()')+method('    private void onFailure(')+tests)
    (destination/'AudioManager.java').write_text('package android.media;public class AudioManager {public static final int STREAM_MUSIC=3;}')

    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', str(destination), str(destination / 'PlayerHarness.java'), str(destination / 'AudioManager.java')], check=True)
    subprocess.run([java, '-cp', str(destination), 'PlayerHarness'], check=True)
