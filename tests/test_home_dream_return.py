"""Real broadcast/lifecycle tracker, ease-out clock and HomeView layer transforms."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
methods='\n'.join(block(home,s) for s in ['    void revealFromDream(', '    private void updateMotion(', '    private static void show(', '    private static float smoothstep('])
files={
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Intent.java':'''package android.content;public class Intent {public static final String ACTION_CLOSE_SYSTEM_DIALOGS="close",ACTION_DREAMING_STARTED="start",ACTION_DREAMING_STOPPED="stop";final String action;String reason;public Intent(String action){this.action=action;}public Intent putExtra(String k,String v){reason=v;return this;}public String getAction(){return action;}public String getStringExtra(String k){return reason;}}''',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter {public void addAction(String a){}}',
'android/content/Context.java':'''package android.content;public class Context {public static final int RECEIVER_EXPORTED=2,MODE_PRIVATE=0;public BroadcastReceiver receiver;public int flags=-1;public boolean reduced;public void registerReceiver(BroadcastReceiver r,IntentFilter f){receiver=r;flags=0;}public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){receiver=r;this.flags=flags;}public void unregisterReceiver(BroadcastReceiver r){if(receiver!=r)throw new AssertionError();receiver=null;}public Context getSharedPreferences(String n,int m){return this;}public boolean getBoolean(String k,boolean d){return reduced;}public void send(String action){receiver.onReceive(this,new Intent(action));}public void dreamClose(){receiver.onReceive(this,new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason","dream"));}}''',
'android/os/Build.java':'package android.os;public class Build {public static class VERSION {public static int SDK_INT=28;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}',
'android/animation/ValueAnimator.java':'package android.animation;public class ValueAnimator {public static boolean enabled=true;public static boolean areAnimatorsEnabled(){return enabled;}}',
'android/view/View.java':'''package android.view;public class View {public static final int VISIBLE=0,INVISIBLE=4;public float alpha=1,y,sx=1,sy=1;public int visibility;public void setAlpha(float n){alpha=n;}public void setTranslationY(float n){y=n;}public void setVisibility(int v){visibility=v;}public void setScaleX(float n){sx=n;}public void setScaleY(float n){sy=n;}}''',
'io/tvos/HomeDreamReturn.java':Path('app/src/io/tvos/HomeDreamReturn.java').read_text(),
'io/tvos/HomeReveal.java':Path('app/src/io/tvos/HomeReveal.java').read_text(),
'io/tvos/DreamReturnHarness.java':'''package io.tvos;
import android.content.*;import android.view.View;import android.os.*;
public class DreamReturnHarness {
 static final int VISIBLE=0,INVISIBLE=4;static final float SHIFT=560;
 final Context context=new Context();final HomeReveal reveal=new HomeReveal();
 static class Spring {float value;}final Spring drawer=new Spring(),sheet=new Spring();
 final View sky=new View(),heroLayer=new View(),status=new View(),platter=new View(),tiles=new View(),drawerLayer=new View(),sheetLayer=new View();
 final View[] revealContent={sky,heroLayer,status};float h=1080,k=1;boolean running=true,drawerOpen,sheetOpen,searchMode;int wakes;
 static class Rect {float top=788;}final Rect platterRect=new Rect();static class Editor {boolean editing(){return false;}}final Editor dockEditor=new Editor();
 static class Ui {static float clamp(float n){return Math.max(0,Math.min(1,n));}}
 Context getContext(){return context;}void wake(){wakes++;}
'''+methods+'''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.0001,why+" "+a+" != "+b);}
 static void broadcasts(int sdk){
  Build.VERSION.SDK_INT=sdk;SystemClock.now=100;DreamReturnHarness v=new DreamReturnHarness();HomeDreamReturn d=new HomeDreamReturn(v.context,v::revealFromDream);
  check(v.context.flags==(sdk>=33?2:0),"receiver registration matches platform");d.started();d.resumed();d.focused(true);check(v.wakes==0,"cold start never reveals");
  d.paused();d.stopped();d.started();d.resumed();d.focused(true);check(v.wakes==0,"ordinary activity round trip never reveals");
  v.context.dreamClose();d.focused(false);d.paused();d.stopped();v.context.send(Intent.ACTION_DREAMING_STARTED);v.context.send(Intent.ACTION_DREAMING_STARTED);
  v.context.send(Intent.ACTION_DREAMING_STOPPED);v.context.send(Intent.ACTION_DREAMING_STOPPED);check(v.wakes==0,"waits for underlying home resume/focus");d.started();d.resumed();check(v.wakes==0,"resume alone is insufficient");d.focused(true);check(v.wakes==1&&v.reveal.active(),"cross-process dream returns to home once");d.focused(true);v.context.send(Intent.ACTION_DREAMING_STOPPED);check(v.wakes==1,"duplicate focus/stop cannot replay");
  // Android 9-style dream window: activity remains resumed, only window focus changes.
  v.context.send(Intent.ACTION_DREAMING_STARTED);d.focused(false);v.context.send(Intent.ACTION_DREAMING_STOPPED);d.focused(true);check(v.wakes==2,"dream without activity pause");
  // A dream on top of another app must never become a later home entrance.
  d.paused();d.stopped();v.context.dreamClose();v.context.send(Intent.ACTION_DREAMING_STARTED);v.context.send(Intent.ACTION_DREAMING_STOPPED);d.started();d.resumed();d.focused(true);check(v.wakes==2,"dream over foreign app excluded");
  v.context.send(Intent.ACTION_DREAMING_STARTED);d.paused();d.stopped();v.context.send(Intent.ACTION_DREAMING_STOPPED);SystemClock.now+=3001;d.started();d.resumed();d.focused(true);check(v.wakes==2,"stale return expires");
  d.close();check(v.context.receiver==null,"destroy unregisters receiver");
 }
 public static void main(String[] args){
  for(int sdk:new int[]{28,30,33,34})broadcasts(sdk);
  DreamReturnHarness v=new DreamReturnHarness();SystemClock.now=1000;v.revealFromDream();near(v.sky.sx,.97f,"background starts in depth");near(v.heroLayer.sx,.97f,"content shares background scale");near(v.tiles.y,312,"Dock starts completely below screen");near(v.platter.y,v.tiles.y,"Dock platter/icons move together");
  float[] rest={1,.421875f,.125f,.015625f,0};long[] at={1000,1162,1325,1487,1650};float last=2;
  for(int i=0;i<at.length;i++){v.reveal.tick(at[i]);v.updateMotion();check(v.reveal.remaining()<=last,"monotonic ease-out");last=v.reveal.remaining();if(i==2)near(last,rest[i],"half time has only one eighth movement left");}
  check(!v.reveal.active()&&!v.reveal.tick(2000),"animation ends without extra ticking");near(v.sky.sx,1,"final scale exactly normal");near(v.tiles.y,0,"final Dock position exactly normal");
  v.context.reduced=true;v.revealFromDream();check(!v.reveal.active(),"Reduce Motion skips effect");near(v.sky.sx,1,"Reduce Motion preserves normal content");near(v.tiles.y,0,"Reduce Motion preserves normal Dock");
  v.context.reduced=false;android.animation.ValueAnimator.enabled=false;v.revealFromDream();check(!v.reveal.active(),"system animation disable respected");android.animation.ValueAnimator.enabled=true;
  v.revealFromDream();v.reveal.cancel();v.updateMotion();near(v.sky.sx,1,"pause cancellation resets scale");near(v.tiles.y,0,"pause cancellation resets translation");
  for(int guard=0;guard<4;guard++){v.running=guard!=0;v.drawerOpen=guard==1;v.sheetOpen=guard==2;v.searchMode=guard==3;v.revealFromDream();check(!v.reveal.active(),"hidden/overlay home entrance excluded");}
  v.running=true;v.drawerOpen=v.sheetOpen=v.searchMode=false;v.drawer.value=1;v.sheet.value=0;v.updateMotion();near(v.tiles.y,-560,"existing drawer transform retained");v.sheet.value=1;v.updateMotion();near(v.tiles.alpha,0,"existing sheet opacity retained");
  System.out.println("PASS: actual dream receiver/lifecycle, SDK 28/30/33/34, duplicate/stale/foreign-app guards, real HomeView transforms, cubic ease-out, Reduce Motion and cleanup");
 }
}'''}
# Lifecycle and existing frame-loop wiring are also checked at their actual call sites.
activity=Path('app/src/io/tvos/HomeActivity.java').read_text()
for signature,call in [('    @Override protected void onResume(', 'dreamReturn.resumed()'),('    @Override protected void onPause(', 'dreamReturn.paused()'),('    @Override protected void onStop(', 'dreamReturn.stopped()'),('    @Override protected void onDestroy(', 'dreamReturn.close()')]:
 assert call in block(activity,signature)
assert 'reveal.cancel();' in block(home,'    void pause(')
assert 'reveal.tick(up)' in block(home,'    @Override public void doFrame(')
with tempfile.TemporaryDirectory(prefix='tvos-dream-return-') as tmp:
 root=Path(tmp)
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.DreamReturnHarness'],check=True)
