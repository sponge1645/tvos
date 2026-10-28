"""Run the real fade driver against frame scheduling, animator and clock doubles."""
from pathlib import Path
import subprocess
import tempfile

stubs = {
'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=28;}}',
'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long now; public static long uptimeMillis(){return now;}}',
'android/view/animation/AccelerateDecelerateInterpolator.java': 'package android.view.animation; public class AccelerateDecelerateInterpolator {}',
'android/view/View.java': '''package android.view;
import java.util.*;
public class View {
 public float alpha; public List<Runnable> frames=new ArrayList<>();public Animation animation=new Animation();
 public void setAlpha(float v){alpha=v;}public float getAlpha(){return alpha;}
 public void removeCallbacks(Runnable r){frames.removeIf(v->v==r);}
 public void postOnAnimation(Runnable r){frames.add(r);}public Animation animate(){return animation;}
 public void tick(long now){android.os.SystemClock.now=now;List<Runnable> batch=new ArrayList<>(frames);frames.clear();for(Runnable r:batch)r.run();}
 public class Animation {
  public int starts;public Runnable end;public float target;public long duration;
  public Animation alpha(float v){target=v;return this;}public Animation setDuration(long d){duration=d;return this;}
  public Animation setInterpolator(Object o){return this;}public Animation withEndAction(Runnable r){end=r;return this;}
  public void start(){starts++;}public void cancel(){end=null;}
  public void complete(){alpha=target;Runnable r=end;end=null;if(r!=null)r.run();}
 }
}''',
'io/tvos/FadeHarness.java': '''package io.tvos;
import android.view.View;import android.os.*;
public class FadeHarness {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static boolean near(float a,float b){return Math.abs(a-b)<.001f;}
 static void legacy(int sdk){
  Build.VERSION.SDK_INT=sdk;SystemClock.now=0;View v=new View();v.alpha=1;
  ScreensaverFade f=new ScreensaverFade(v);int[] calls={0};f.start(0,600,()->calls[0]++);
  check(v.alpha==1 && v.animation.starts==0,"legacy starts at black without property animator");
  v.tick(300);check(near(v.alpha,.5f)&&calls[0]==0,"halfway frame renders halfway opacity");
  v.tick(600);check(v.alpha==0&&calls[0]==1&&v.frames.isEmpty(),"ends once with no idle callbacks");
  f.start(1,600,()->calls[0]++);v.tick(900);check(near(v.alpha,.5f),"symmetric reverse");
  Runnable stale=v.frames.get(0);f.cancel();stale.run();v.tick(1200);
  check(near(v.alpha,.5f)&&calls[0]==1&&v.frames.isEmpty(),"cancel blocks queued callback and completion");
  f.start(0,600,()->calls[0]++);v.tick(1500);check(near(v.alpha,.25f),"restart preserves partial alpha");
  v.tick(2400);check(v.alpha==0&&calls[0]==2,"late frame completes without extending duration");
  f.start(1,0,()->calls[0]++);check(v.alpha==1&&calls[0]==3&&v.frames.isEmpty(),"zero duration has no pending frames");
  SystemClock.now=3000;f.start(0,600,()->f.start(1,600,()->calls[0]++));v.tick(3600);v.tick(3900);
  check(near(v.alpha,.5f),"completion may start next leg");v.tick(4200);check(calls[0]==4,"chained completion once");
 }
 public static void main(String[] args){
  legacy(28);legacy(29);
  Build.VERSION.SDK_INT=30;SystemClock.now=0;View v=new View();v.alpha=1;ScreensaverFade f=new ScreensaverFade(v);
  int[] calls={0};f.start(0,600,()->calls[0]++);
  check(v.animation.starts==1&&v.frames.isEmpty()&&v.animation.duration==600,"modern animator path retained");
  Runnable stale=v.animation.end;f.cancel();stale.run();check(calls[0]==0,"modern stale completion cancelled");
  f.start(0,600,()->calls[0]++);v.animation.complete();check(calls[0]==1&&v.alpha==0,"modern completion");
  // Window alpha must fade even when the backing decor view has alpha 1.
  Build.VERSION.SDK_INT=28;SystemClock.now=0;View decor=new View();decor.alpha=1;float[] window={0};
  ScreensaverFade w=new ScreensaverFade(decor,a->window[0]=a,0);w.start(1,600,null);decor.tick(300);
  check(near(window[0],.5f)&&decor.alpha==1,"window target is independent of decor alpha");
  w.start(0,600,()->calls[0]++);decor.tick(600);check(near(window[0],.25f),"window reversal preserves actual opacity");
  decor.tick(900);check(window[0]==0&&calls[0]==2,"window reaches transparent before finish");
  System.out.println("PASS: Android 9/10 frame fades, symmetric timing, cancellation, interruption, delayed frames, chained phases, modern path, window alpha");
 }
}'''
}
with tempfile.TemporaryDirectory() as directory:
    root=Path(directory)
    stubs['io/tvos/ScreensaverFade.java']=Path('app/src/io/tvos/ScreensaverFade.java').read_text()
    for name,content in stubs.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run(['java','-cp',directory,'io.tvos.FadeHarness'],check=True)
