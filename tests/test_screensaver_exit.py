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
  Runnable end;int cancels;long duration;
  Animation alpha(float a){return this;}Animation setDuration(long n){duration=n;return this;}
  Animation withEndAction(Runnable r){end=r;return this;}void start(){}void cancel(){cancels++;end=null;}
 }
 static class View {
  static int VISIBLE=0,GONE=8;Animation animation=new Animation();float alpha;
  View(){}View(Object c){}Animation animate(){return animation;}void setAlpha(float a){alpha=a;}
  void setBackgroundColor(int c){}void setVisibility(int v){}
 }
 static class MediaPlayer {boolean released;void reset(){}void release(){released=true;}}
 static class Fallback extends View {boolean stopped;void stop(){stopped=true;}}
 Handler handler=new Handler(); View videoFade=new View(),aerialLocation=new View(),aerialWeather=new View(),exitCover;
 Fallback fallback;MediaPlayer player=new MediaPlayer();boolean running=true,exiting,loadingCatalog,preparing;
 int generation,catalogGeneration;Runnable prepareTimeout,fadeOut,retryPlayback=()->{};
 List<View> children=new ArrayList<>();Object getContext(){return this;}
 void addView(View v,LayoutParams p){children.add(v);}void removeView(View v){children.remove(v);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
'''
tests='''
 public static void main(String[] args){
  ExitHarness h=new ExitHarness();MediaPlayer original=h.player;int[] calls={0};
  h.handler.tasks.add(()->{throw new AssertionError("old work ran");});
  h.fadeExit(()->{calls[0]++;h.stop();});
  check(h.exiting && h.generation==1 && h.catalogGeneration==1,"stale asynchronous work invalidated");
  check(!original.released && h.children.contains(h.exitCover),"video remains alive under exit overlay");
  check(h.exitCover.animation.duration==300 && h.handler.tasks.size()==1,"bounded fade and deadline");
  h.fadeExit(()->{throw new AssertionError("duplicate exit");});
  h.exitCover.animation.end.run();
  check(calls[0]==1 && original.released && h.handler.tasks.isEmpty() && h.children.isEmpty(),"normal completion releases after fade");
  ExitHarness deadline=new ExitHarness();deadline.fadeExit(()->{calls[0]++;deadline.stop();});
  deadline.handler.tasks.get(0).run();
  check(calls[0]==2 && deadline.player==null && deadline.children.isEmpty(),"deadline handles missing animation callback");
  ExitHarness forced=new ExitHarness();forced.fadeExit(()->{throw new AssertionError("callback after forced stop");});
  forced.stop();check(forced.handler.tasks.isEmpty() && forced.exitCover==null,"forced stop cancels transition");
  ExitHarness photos=new ExitHarness();photos.fallback=new Fallback();Fallback f=photos.fallback;
  photos.fadeExit(()->photos.stop());check(!f.stopped,"photo remains visible during fade");
  photos.exitCover.animation.end.run();check(f.stopped,"photo stops after fade");
  System.out.println("PASS: live decoder during fade, duplicate exit, completion, deadline, forced stop, photo fallback");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ExitHarness.java';p.write_text(header+method('    void fadeExit(')+method('    void stop()')+method('    private void releasePlayer()')+tests)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'ExitHarness'],check=True)
