"""Execute the real DreamService and frame fade, including Android 13's Activity window path."""
from pathlib import Path
import ast, subprocess, tempfile
module=ast.parse(Path('tests/test_screensaver_fade.py').read_text())
stubs=ast.literal_eval(next(n.value for n in module.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='stubs' for t in n.targets)))
stubs.pop('io/tvos/FadeHarness.java')
stubs['android/view/View.java']=stubs['android/view/View.java'].replace('public float alpha;', 'public void post(Runnable r){frames.add(r);}public float alpha;')
stubs.update({
'android/content/Context.java':'package android.content;public class Context {}',
'android/content/ContextWrapper.java':'package android.content;public class ContextWrapper extends Context {private final Context base;public ContextWrapper(Context c){base=c;}public Context getBaseContext(){return base;}}',
'android/app/Activity.java':'''package android.app;public class Activity extends android.content.ContextWrapper {
 public boolean translucent=true,deny;public int conversions,enter=-1,exit=-1;public Activity(){super(new android.content.Context());}
 public boolean setTranslucent(boolean b){if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("API guard");conversions++;if(deny)throw new SecurityException();return translucent;}
 public void overridePendingTransition(int a,int b){enter=a;exit=b;}
}''',
'android/view/WindowManager.java':'package android.view;public class WindowManager {public static class LayoutParams {public float alpha=1;public int windowAnimations;}}',
'android/view/Window.java':'''package android.view;public class Window {
 public android.content.Context context;public View decor=new View();public WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();public int updates;
 public Window(android.content.Context c){context=c;}public android.content.Context getContext(){return context;}public View getDecorView(){return decor;}
 public WindowManager.LayoutParams getAttributes(){return attrs;}public void setAttributes(WindowManager.LayoutParams a){attrs=a;updates++;}
}''',
'android/util/Log.java':'package android.util;public class Log {public static int i(String tag,String message){return 0;}}',
'android/animation/ValueAnimator.java':'package android.animation;public class ValueAnimator {public static boolean areAnimatorsEnabled(){return true;}}',
'android/service/dreams/DreamService.java':'''package android.service.dreams;public class DreamService extends android.content.Context {
 public android.view.Window window;public int finishes;public float firstContentAlpha=-1;public android.view.Window getWindow(){return window;}
 public void finish(){if(window!=null&&window.attrs.alpha!=0&&window.attrs.windowAnimations==0)throw new AssertionError("finish before full transparent frame");finishes++;}
 public void setContentView(android.view.View v){firstContentAlpha=window.attrs.alpha;}
 public void setInteractive(boolean b){}public void setFullscreen(boolean b){}public void setScreenBright(boolean b){}
 public void onAttachedToWindow(){}public void onDreamingStarted(){}public void onDreamingStopped(){}public void onDetachedFromWindow(){}public void onWakeUp(){}
}''',
'io/tvos/VideoDream.java':'''package io.tvos;public class VideoDream extends android.view.View {
 static final long TRANSITION_MS=600;static VideoDream last;public int starts,stops;public Runnable exit,opaque;
 VideoDream(Object c){last=this;}void setOnOpaqueListener(Runnable r){opaque=r;}void startAfterWindowFade(){starts++;}void stop(){stops++;}
 void fadeExit(Runnable r){exit=r;}
}''',
'io/tvos/AppLog.java':'package io.tvos;class AppLog {static int warnings,errors;static void w(String m){warnings++;}static void unexpected(String m,Throwable e){errors++;}}',
'io/tvos/Ui.java':'package io.tvos;class Ui {static void init(Object c){}}',
'io/tvos/Watchdog.java':'package io.tvos;class Watchdog {static void install(Object c){}}',
'io/tvos/HomeDreamReturn.java':'package io.tvos;class HomeDreamReturn {static void opaque(Object c){}}',
'io/tvos/R.java':'package io.tvos;class R {static class style {static final int ScreensaverWindowAnimations=42;}static class anim {static final int screensaver_window_enter=10,screensaver_window_exit=11;}}',
'io/tvos/WindowHarness.java':'''package io.tvos;
import android.app.Activity;import android.view.*;import android.content.*;import android.os.*;
public class WindowHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.001,why+" "+a);}
 static Screensaver create(int sdk,Activity activity){Build.VERSION.SDK_INT=sdk;SystemClock.now=0;Screensaver s=new Screensaver();s.window=new Window(activity==null?new Context():new ContextWrapper(activity));s.onAttachedToWindow();return s;}
 static void fades(int sdk){
  Activity a=sdk<30?null:new Activity();Screensaver s=create(sdk,a);VideoDream video=VideoDream.last;
  check(s.firstContentAlpha==0&&s.window.attrs.alpha==0&&s.window.attrs.windowAnimations==0,"first content is hidden, no native transition: "+sdk);
  if(a!=null)check(a.conversions==1,"modern dream activity made translucent");
  s.onDreamingStarted();check(video.starts==0,"entry waits for posted start");video.tick(0);check(video.starts==1,"existing black-to-video phase retained");
  s.window.decor.tick(300);near(s.window.attrs.alpha,.5f,"previous screen gradually dims to black");check(s.finishes==0,"no early finish");
  s.window.decor.tick(600);near(s.window.attrs.alpha,1,"black window fully opaque before video cover reveals");
  s.onWakeUp();check(s.window.attrs.alpha==1&&s.finishes==0,"video first fades to black");video.exit.run();video.exit.run();
  s.window.decor.tick(900);near(s.window.attrs.alpha,.5f,"black window gradually reveals previous screen");check(s.finishes==0,"decoder lifetime extends through exit");
  s.window.decor.tick(1200);check(s.window.attrs.alpha==0&&s.finishes==1&&s.window.decor.frames.isEmpty(),"single finish after final transparent frame");
  if(a!=null)check(a.enter==0&&a.exit==0,"no duplicate framework transition");
  s.onDreamingStopped();s.onDetachedFromWindow();check(s.window.decor.frames.isEmpty(),"lifecycle removes pending frames");
 }
 public static void main(String[] args){
  for(int sdk:new int[]{28,29,30,33,34})fades(sdk);
  Activity denied=new Activity();denied.translucent=false;Screensaver fallback=create(33,denied);check(fallback.firstContentAlpha==1&&fallback.window.attrs.windowAnimations==42&&AppLog.warnings==1,"firmware refusal keeps safe native fallback");
  fallback.onDreamingStarted();VideoDream.last.tick(0);check(denied.enter==10,"native fallback still enters");fallback.onWakeUp();VideoDream.last.exit.run();check(fallback.finishes==1&&denied.exit==11,"native fallback exits");
  denied=new Activity();denied.deny=true;fallback=create(33,denied);check(fallback.firstContentAlpha==1&&AppLog.errors==1,"firmware exception is logged without crash");
  Screensaver noActivity=create(33,null);check(noActivity.firstContentAlpha==1&&noActivity.window.attrs.windowAnimations==42,"unknown context uses native fallback");
  Screensaver early=create(33,new Activity());VideoDream video=VideoDream.last;early.onDreamingStarted();early.onWakeUp();video.tick(0);check(video.starts==0,"wake prevents queued start");video.exit.run();early.window.decor.tick(600);check(early.finishes==1,"early wake finishes safely");
  Screensaver stopped=create(33,new Activity());video=VideoDream.last;stopped.onDreamingStarted();stopped.onDetachedFromWindow();video.tick(0);check(video.starts==0&&stopped.window.decor.frames.isEmpty(),"detach cannot revive queued work");
  Screensaver during=create(33,new Activity());during.onDreamingStarted();VideoDream.last.tick(0);Runnable stale=during.window.decor.frames.get(0);during.onDreamingStopped();stale.run();during.window.decor.tick(600);check(during.window.attrs.alpha==0&&during.window.decor.frames.isEmpty(),"stopped frame cannot publish opacity");
  System.out.println("PASS: real system dream + real window fade on SDK28/29/30/33/34, hidden initial content, symmetric black phases, native fallback/refusal, interrupted/duplicate wake and lifecycle cleanup");
 }
}'''
})
for name in ['Screensaver','ScreensaverFade']:stubs['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-dream-window-') as tmp:
 root=Path(tmp)
 for name,text in stubs.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.WindowHarness'],check=True)
