"""Exercise the actual video exit transition and lifecycle cleanup with deterministic animation stubs."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/VideoDream.java').read_text()
def method(signature):
    a=source.index(signature);b=source.index('{',a);depth=1;i=b+1
    while depth:
        depth+=(source[i]=='{')-(source[i]=='}');i+=1
    return source[a:i]
header='''import java.util.*;
public class ExitHarness {
 static class Color {static int BLACK=0;}
 static class LayoutParams {LayoutParams(int w,int h){}}
 static class Handler {List<Runnable> tasks=new ArrayList<>();void postDelayed(Runnable r,long t){tasks.add(r);}void removeCallbacks(Runnable r){tasks.remove(r);}void removeCallbacksAndMessages(Object o){tasks.clear();}}
 static class Animation {
  Runnable end;int cancels;long duration;float target;Object easing;
  Animation alpha(float a){target=a;return this;}Animation setInterpolator(Object i){easing=i;return this;}Animation setDuration(long n){duration=n;return this;}
  Animation withEndAction(Runnable r){end=r;return this;}void start(){}void cancel(){cancels++;end=null;}
 }
 static class ScreensaverFade {
  View view;ScreensaverFade(View v){view=v;}void cancel(){view.animate().cancel();}void reset(float value){cancel();view.setAlpha(value);}
  void start(float a,long d,Runnable r){view.animate().alpha(a).setDuration(d).withEndAction(r).start();}
 }
 static class View {
  static int VISIBLE=0,GONE=8;Animation animation=new Animation();float alpha;
  View(){}View(Object c){}Animation animate(){return animation;}void setAlpha(float a){alpha=a;}
  void setBackgroundColor(int c){}void setVisibility(int v){}
 }
 static class MediaPlayer {boolean released;void reset(){}void release(){released=true;}}
 static class Fallback extends View {boolean stopped;void stop(){stopped=true;}}
 Handler handler=new Handler(); View videoFade=new View(),aerialLocation=new View(),aerialWeather=new View();final View scene=new View();static final long TRANSITION_MS=600;
 ScreensaverFade sceneFade=new ScreensaverFade(scene);
 Fallback fallback;MediaPlayer player=new MediaPlayer();boolean running=true,exiting,loadingCatalog,preparing,entered;
 Runnable opaqueListener;int generation,catalogGeneration;Runnable prepareTimeout,fadeOut,retryPlayback=()->{};
 List<View> children=new ArrayList<>();Object getContext(){return this;}
 void addView(View v,LayoutParams p){children.add(v);}void removeView(View v){children.remove(v);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
'''
photo_source=Path('app/src/io/tvos/DreamView.java').read_text()
a=photo_source.index('    private void notifyContentReady()');b=photo_source.index('{',a);d=1;i=b+1
while d:
    d+=(photo_source[i]=='{')-(photo_source[i]=='}');i+=1
photo_method=photo_source[a:i]
header+='static class PhotoReady {boolean running=true;Runnable contentReady;List<Runnable> posted=new ArrayList<>();void post(Runnable r){posted.add(r);}' + photo_method + '}\n'
tests='''
 public static void main(String[] args){
  ExitHarness h=new ExitHarness();MediaPlayer original=h.player;int[] calls={0};
  h.scene.setAlpha(0f);h.fadeEntrance();
  check(h.entered && h.scene.animation.target==1 && h.scene.animation.duration==600,"ready content fades over preceding screen with shared duration");
  h.scene.setAlpha(.4f);h.fadeExit(()->{calls[0]++;h.stop();});
  check(h.exiting && h.generation==1 && h.catalogGeneration==1,"stale asynchronous work invalidated");
  check(!original.released && h.scene.alpha==.4f,"interrupted entry preserves cover opacity and live decoder");
  check(h.scene.animation.target==0 && h.scene.animation.duration==600 && h.handler.tasks.size()==1,"matching exit fade and deadline");
  h.fadeEntrance();check(h.scene.animation.target==0,"late content ready cannot reverse exit");
  h.fadeExit(()->{throw new AssertionError("duplicate exit");});
  h.scene.animation.end.run();
  check(calls[0]==1 && original.released && h.handler.tasks.isEmpty() && h.scene.alpha==0,"normal completion releases after fade");
  ExitHarness deadline=new ExitHarness();deadline.fadeExit(()->{calls[0]++;deadline.stop();});
  deadline.handler.tasks.get(0).run();
  check(calls[0]==2 && deadline.player==null,"deadline handles missing animation callback");
  ExitHarness forced=new ExitHarness();forced.fadeExit(()->{throw new AssertionError("callback after forced stop");});
  forced.stop();check(forced.handler.tasks.isEmpty() && forced.scene.animation.end==null,"forced stop cancels transition");
  ExitHarness photos=new ExitHarness();photos.fallback=new Fallback();Fallback f=photos.fallback;
  photos.fadeExit(()->photos.stop());check(!f.stopped,"photo remains visible during fade");
  photos.scene.animation.end.run();check(f.stopped,"photo stops after fade");
  PhotoReady ready=new PhotoReady();int[] readyCalls={0};ready.contentReady=()->readyCalls[0]++;
  ready.notifyContentReady();ready.notifyContentReady();
  check(ready.posted.size()==1 && readyCalls[0]==0,"photo readiness waits for drawing and notifies once");
  ready.posted.get(0).run();check(readyCalls[0]==1,"ready photo triggers parent entrance");
  PhotoReady stopped=new PhotoReady();stopped.contentReady=()->{throw new AssertionError("late photo entry");};
  stopped.notifyContentReady();stopped.running=false;stopped.posted.get(0).run();
  System.out.println("PASS: symmetric entry/exit, interrupted entry, late ready, live decoder, completion, deadline, forced stop, photos");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ExitHarness.java';p.write_text(header+method('    private void fadeEntrance(')+method('    void fadeExit(')+method('    void stop()')+method('    private void releasePlayer()')+tests)
 stub=Path(tmp)/'AccelerateDecelerateInterpolator.java';stub.write_text('package android.view.animation;public class AccelerateDecelerateInterpolator {}')
 clock=Path(tmp)/'SystemClock.java';clock.write_text('package android.os;public class SystemClock {public static long uptimeMillis(){return 1000;}}')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p),str(stub),str(clock)],check=True)
 subprocess.run(['java','-cp',tmp,'ExitHarness'],check=True)
