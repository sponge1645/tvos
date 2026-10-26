"""Execute the production preview controller and validate dream window fade resources."""
from pathlib import Path
import subprocess,tempfile,xml.etree.ElementTree as ET
sources={
'android/content/Context.java':'package android.content;public class Context {}',
'android/graphics/Color.java':'package android.graphics;public class Color {public static final int BLACK=0xFF000000;}',
'android/view/View.java':'''package android.view;public class View {public static final int VISIBLE=0,GONE=8;public float alpha;public int visibility=VISIBLE;public View(android.content.Context c){}public void setBackgroundColor(int c){}public void setAlpha(float a){alpha=a;}public void setVisibility(int v){visibility=v;}}''',
'android/view/KeyEvent.java':'''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_BACK=4;final int action,code;public KeyEvent(int a,int c){action=a;code=c;}public int getAction(){return action;}public int getKeyCode(){return code;}}''',
'android/widget/FrameLayout.java':'''package android.widget;import java.util.*;import android.view.View;public class FrameLayout extends View {public final List<View> children=new ArrayList<>();public FrameLayout(android.content.Context c){super(c);}public static class LayoutParams {public LayoutParams(int w,int h){}}public void addView(View v,LayoutParams p){children.add(v);}public void addView(View v,int i,LayoutParams p){children.add(i,v);}public void removeView(View v){children.remove(v);}}''',
'io/tvos/ScreensaverFade.java':'''package io.tvos;import android.view.View;final class ScreensaverFade {static ScreensaverFade last;final View view;Runnable end;float target;long duration;ScreensaverFade(View v){last=this;view=v;}void cancel(){end=null;}void start(float t,long d,Runnable r){target=t;duration=d;end=r;}void complete(){view.setAlpha(target);Runnable r=end;end=null;if(r!=null)r.run();}}''',
'io/tvos/VideoDream.java':'''package io.tvos;final class VideoDream extends android.view.View {static final long TRANSITION_MS=600;static VideoDream last;boolean started,stopped;Runnable exit;VideoDream(android.content.Context c){super(c);last=this;}void start(){started=true;}void stop(){stopped=true;exit=null;}void fadeExit(Runnable r){exit=r;}void completeExit(){Runnable r=exit;exit=null;if(r!=null)r.run();}}''',
'io/tvos/PreviewHarness.java':'''package io.tvos;import android.view.*;import android.content.Context;import android.widget.FrameLayout;
public class PreviewHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static boolean key(SettingsPreview p,int action,int code){return p.onKey(new KeyEvent(action,code));}
 public static void main(String[] args){
  Context context=new Context();View view=new View(context);int[] resumed={0};SettingsPreview p=new SettingsPreview(context,view,()->resumed[0]++);FrameLayout host=p.host();View cover=host.children.get(1);ScreensaverFade fade=ScreensaverFade.last;
  check(!p.active()&&!key(p,0,23),"closed preview does not intercept settings input");p.show();
  check(!p.hasDream()&&view.visibility==View.VISIBLE&&cover.visibility==View.VISIBLE&&cover.alpha==0&&fade.target==1,"settings stay visible while black cover fades in");
  check(key(p,1,4)&&key(p,1,24),"transition consumes input");p.show();fade.complete();
  VideoDream dream=VideoDream.last;check(dream.started&&p.hasDream()&&view.visibility==View.GONE&&host.children.size()==3&&host.children.get(2)==cover,"preview starts only at black; cover remains topmost");
  check(!key(p,1,24),"volume keys pass to system");key(p,0,23);check(dream.exit==null,"key-down does not close dream");key(p,1,23);check(dream.exit!=null,"key-up starts dream fade");dream.completeExit();
  check(dream.stopped&&!p.hasDream()&&cover.alpha==1&&view.visibility==View.VISIBLE&&resumed[0]==1,"settings restored beneath opaque cover");
  check(fade.target==0&&fade.duration==600&&p.active(),"settings reveal uses matching fade");fade.complete();check(!p.active()&&cover.visibility==View.GONE,"input restored after reveal");
  p.show();p.close();check(fade.end==null&&!p.active()&&cover.visibility==View.GONE&&cover.alpha==0,"pause cancels opening with no delayed preview");fade.complete();check(!p.hasDream(),"cancelled opening cannot create dream");
  p.show();fade.complete();dream=VideoDream.last;p.close();check(dream.stopped&&host.children.size()==2&&!p.active(),"pause stops visible dream and removes its view");
  p.show();fade.complete();key(p,1,4);VideoDream.last.completeExit();p.close();check(fade.end==null&&cover.visibility==View.GONE&&!p.active(),"pause cancels reveal");
  int before=resumed[0];p.close();check(resumed[0]==before,"repeated close is no-op");
  for(int code:new int[]{19,20,21,22,23,66,4}){p.show();fade.complete();check(key(p,1,code),"exit key consumed");VideoDream.last.completeExit();fade.complete();check(!p.active(),"all navigation keys complete preview exit");}
  System.out.println("PASS: actual preview controller, black-only swaps, matching fades, repeated open, key routing and pause in all stages");
 }
}'''
}
sources['io/tvos/SettingsPreview.java']=Path('app/src/io/tvos/SettingsPreview.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-preview-') as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.PreviewHarness'],check=True)
ns='{http://schemas.android.com/apk/res/android}'
for name,first,last in [('enter','0','1'),('exit','1','0')]:
 root=ET.parse('app/res/anim/screensaver_window_'+name+'.xml').getroot()
 assert root.get(ns+'fromAlpha')==first and root.get(ns+'toAlpha')==last
 assert root.get(ns+'duration')=='600' and root.get(ns+'interpolator')=='@android:anim/accelerate_decelerate_interpolator'
print('PASS: dream window fades use matching duration and easing')
