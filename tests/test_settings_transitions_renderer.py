"""Execute production menu/title transitions and the Activity's screen-drawing adapter."""
from pathlib import Path
import subprocess
import tempfile
from settings_transitions_fixture import write
from tmdb_metadata_fixture import block
activity=Path('app/src/io/tvos/SettingsActivity.java').read_text()
harness='''import android.graphics.*;import android.os.SystemClock;import io.tvos.settings.*;import java.util.*;
public class TransitionRenderHarness {
 String title="Settings";boolean reduced;final Paint pageTitle=new Paint();final SettingsTextRenderer textRenderer=new SettingsTextRenderer();
 String screenTitle(){return title;}boolean localFlag(String key){return reduced;}
 int screen,frames;float k=1;int w=1920,h=1080;boolean fail;List<Integer> pages=new ArrayList<>();
 final SettingsTransitions transitions=new SettingsTransitions();
 final java.util.function.IntUnaryOperator transitionParent=id->id==1?0:id==2?1:-1;
 final SettingsTransitions.ScreenPainter transitionPainter=this::drawTransitionScreen;
 void drawScreenContent(Canvas c){pages.add(screen);if(fail)throw new IllegalStateException();}
 void postInvalidateDelayed(long delay){check(delay==16,"frame delay");frames++;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void near(float a,float b){check(Math.abs(a-b)<.001f,a+" != "+b);}
''' + block(activity,'        private void drawMenuTransition(')+block(activity,'        private void drawTransitionScreen(')+block(activity,'        private void drawPageTitle(')+'''
 public static void main(String[] args){
 for(float scale:new float[]{2f/3,1,2})for(boolean forward:new boolean[]{true,false})for(float t:new float[]{0,.25f,.5f,.75f,1}){
  TransitionRenderHarness v=new TransitionRenderHarness();v.k=scale;v.w=Math.round(1920*scale);v.h=Math.round(1080*scale);
  int old=forward?0:1,target=forward?1:0;v.screen=old;v.transitions.sync(old,0,false,false,v.transitionParent);
  v.screen=target;check(v.transitions.sync(target,1000,false,false,v.transitionParent),"navigation resets focus");
  check(!v.transitions.sync(target,1100,false,false,v.transitionParent),"same page does not restart timeline");
  SystemClock.now=1000+Math.round(280*t);Canvas c=new Canvas();v.drawMenuTransition(c);
  check(v.screen==target&&c.depth==0,"navigation state and Canvas retained");
  if(t==1){check(v.pages.equals(Arrays.asList(target))&&v.frames==0&&v.transitions.focusReady(1280),"completion draws once and stops frames");continue;}
  check(v.pages.equals(Arrays.asList(old,target))&&v.frames==1,"old then new page, one scheduled frame");
  float eased=t*t*(3-2*t),direction=forward?-1:1;
  near(c.translations.get(0),direction*105*scale*eased);near(c.translations.get(1),-direction*90*scale*(1-eased));
  check(c.alphas.equals(Arrays.asList(Math.round(255*(1-eased)),Math.round(255*eased))),"layer crossfade");
  for(float[] clip:c.clips){near(clip[0],960*scale);near(clip[1],140*scale);near(clip[2],v.w);near(clip[3],v.h);}
 }
 for(boolean reduced:new boolean[]{false,true})for(boolean modal:new boolean[]{false,true}){
  TransitionRenderHarness v=new TransitionRenderHarness();v.transitions.sync(0,0,false,false,v.transitionParent);v.screen=1;v.transitions.sync(1,100,reduced,modal,v.transitionParent);SystemClock.now=100;v.drawMenuTransition(new Canvas());
  check(v.pages.size()==(reduced||modal?1:2),"Reduce Motion and modal suppress page animation");
  check(v.transitions.focusReady(100)==reduced,"modal retains original focus delay");
 }
 TransitionRenderHarness v=new TransitionRenderHarness();v.transitions.sync(0,0,false,false,v.transitionParent);v.screen=3;v.transitions.sync(3,100,false,false,v.transitionParent);SystemClock.now=100;v.drawMenuTransition(new Canvas());check(v.pages.equals(Arrays.asList(3)),"unrelated pages do not slide");
 v.screen=2;v.fail=true;try{v.drawTransitionScreen(new Canvas(),1);throw new AssertionError();}catch(IllegalStateException expected){check(v.screen==2,"adapter restores logical screen on draw failure");}
 SettingsTransitions titles=new SettingsTransitions();SettingsTextRenderer text=new SettingsTextRenderer();Paint p=new Paint();p.setAlpha(200);Canvas c=new Canvas();
 check(!titles.drawTitle(c,"Settings",0,false,960,111,p,text)&&text.labels.equals(Arrays.asList("Settings")),"initial title not animated");text.clear();
 check(titles.drawTitle(c,"Общие",1000,false,960,111,p,text)&&text.labels.equals(Arrays.asList("Settings","Общие"))&&text.alphas.equals(Arrays.asList(200,0)),"title starts with previous label");text.clear();
 check(titles.drawTitle(c,"Общие",1140,false,960,111,p,text)&&text.alphas.equals(Arrays.asList(100,100))&&p.getAlpha()==200,"midpoint and caller alpha preserved");near(text.x,960);near(text.y,111);near(text.tracking,.02f);text.clear();
 check(titles.drawTitle(c,"About",1150,false,960,111,p,text)&&text.labels.equals(Arrays.asList("Общие","About")),"rapid title change uses latest label");text.clear();
 check(!titles.drawTitle(c,"About",1430,false,960,111,p,text)&&text.labels.equals(Arrays.asList("About")),"title completion stops frames");text.clear();
 check(!titles.drawTitle(c,"Настройки",1500,true,640,74,p,text)&&text.labels.equals(Arrays.asList("Настройки"))&&p.getAlpha()==200,"reduced-motion title changes immediately");
 TransitionRenderHarness tv=new TransitionRenderHarness();tv.drawPageTitle(c,0);check(tv.frames==0,"initial title requests no frames");tv.title="General";tv.drawPageTitle(c,100);check(tv.frames==1,"title wrapper requests active animation frame");tv.drawPageTitle(c,380);check(tv.frames==1,"title wrapper stops frames at completion");tv.reduced=true;tv.title="About";tv.drawPageTitle(c,400);check(tv.frames==1,"reduced-motion title requests no frames");
 System.out.println("PASS: production transition geometry/easing/alpha at 720p/1080p/4K, forward/back, frame completion, title locale/rapid changes, modal/reduced-motion and Activity adapter");
 }}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);write(root)
 path=root/'android/os/SystemClock.java';path.parent.mkdir(parents=True);path.write_text('package android.os;public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}')
 (root/'TransitionRenderHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'TransitionRenderHarness'],check=True)
