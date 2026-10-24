"""Exercise production preview swaps only at black, including pause in either transition leg."""
from pathlib import Path
import subprocess,tempfile,xml.etree.ElementTree as ET
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:
  d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
header='''import java.util.*;
public class PreviewHarness {
 static class Animation {
  View view;Runnable end;float target;long duration;
  Animation(View v){view=v;}Animation alpha(float a){target=a;return this;}
  Animation setDuration(long d){duration=d;return this;}Animation setInterpolator(Object o){return this;}
  Animation withEndAction(Runnable r){end=r;return this;}void start(){}void cancel(){end=null;}
  void complete(){view.alpha=target;Runnable r=end;end=null;if(r!=null)r.run();}
 }
 static class View {
  static int VISIBLE=0,GONE=8;float alpha;int visibility=VISIBLE;Animation animation=new Animation(this);
  Animation animate(){return animation;}void setAlpha(float a){alpha=a;}void setVisibility(int v){visibility=v;}
 }
 static class SettingsView extends View {int resumes;void resume(){resumes++;}}
 static class Host {List<Object> children=new ArrayList<>();void addView(Object v,int n,Object lp){children.add(n,v);}void removeView(Object v){children.remove(v);}}
 static class VideoDream {
  static long TRANSITION_MS=600;boolean started,stopped;VideoDream(Object c){}void start(){started=true;}void stop(){stopped=true;}
 }
 SettingsView view=new SettingsView();View previewCover=new View();Host previewHost=new Host();
 VideoDream preview;boolean previewTransition;
 PreviewHarness(){previewHost.children.add(view);previewHost.children.add(previewCover);previewCover.visibility=View.GONE;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
'''
tests='''
 public static void main(String[] args){
  PreviewHarness h=new PreviewHarness();h.showPreview();
  check(h.preview==null && h.view.visibility==View.VISIBLE,"settings stay visible while fading to black");
  check(h.previewCover.visibility==View.VISIBLE && h.previewCover.alpha==0 && h.previewCover.animation.target==1,"black cover fades over settings");
  h.showPreview();h.previewCover.animation.complete();
  check(h.preview!=null && h.preview.started && h.view.visibility==View.GONE && !h.previewTransition,"preview starts only at black");
  check(h.previewHost.children.size()==3 && h.previewHost.children.get(2)==h.previewCover,"cover stays topmost");
  VideoDream old=h.preview;h.finishPreview();
  check(old.stopped && h.preview==null && h.previewCover.alpha==1 && h.view.visibility==View.VISIBLE,"settings restored beneath fully black cover");
  check(h.previewCover.animation.target==0 && h.previewCover.animation.duration==600 && h.previewTransition,"settings reveal uses matching fade");
  h.previewCover.animation.complete();check(h.previewCover.visibility==View.GONE && !h.previewTransition,"normal settings input restored after reveal");
  PreviewHarness interrupted=new PreviewHarness();interrupted.showPreview();interrupted.closePreview();
  check(interrupted.previewCover.animation.end==null && interrupted.preview==null && !interrupted.previewTransition,"pause cancels opening without delayed preview");
  PreviewHarness closing=new PreviewHarness();closing.showPreview();closing.previewCover.animation.complete();closing.finishPreview();closing.closePreview();
  check(closing.previewCover.animation.end==null && closing.previewCover.visibility==View.GONE,"pause cancels reveal without stale callbacks");
  System.out.println("PASS: settings-to-black before preview, black-to-settings reveal, stable host, repeated open, pause in both legs");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'PreviewHarness.java';p.write_text(header+method('    void showPreview()')+method('    private void finishPreview()')+method('    private void closePreview()')+tests)
 stubs={'AccelerateDecelerateInterpolator.java':'package android.view.animation;public class AccelerateDecelerateInterpolator {}',
 'FrameLayout.java':'package android.widget;public class FrameLayout {public static class LayoutParams {public LayoutParams(int w,int h){}}}'}
 for n,s in stubs.items():(Path(tmp)/n).write_text(s)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p),*[str(Path(tmp)/n) for n in stubs]],check=True)
 subprocess.run(['java','-cp',tmp,'PreviewHarness'],check=True)
ns='{http://schemas.android.com/apk/res/android}'
for name,first,last in [('enter','0','1'),('exit','1','0')]:
 root=ET.parse('app/res/anim/screensaver_window_'+name+'.xml').getroot()
 assert root.get(ns+'fromAlpha')==first and root.get(ns+'toAlpha')==last
 assert root.get(ns+'duration')=='600' and root.get(ns+'interpolator')=='@android:anim/accelerate_decelerate_interpolator'
print('PASS: dream window fades use matching duration and easing')
