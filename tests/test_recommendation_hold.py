"""Production recommendation key adapter and hold timing, including cancellation and exact target."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/HomeView.java').read_text()
methods='\n'.join(block(source,s) for s in ['    void cancelRecommendationHold(', '    private boolean recommendationAvailable(', '    private boolean recommendationKey('])
files={
 'io/tvos/home/RecommendationHold.java':Path('app/src/io/tvos/home/RecommendationHold.java').read_text(),
 'android/content/Intent.java':'''package android.content;public class Intent {public String link;public Intent(String s){link=s;}public Intent(Intent i){link=i.link;}}''',
 'android/view/KeyEvent.java':'''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DPAD_UP=19;int action,code,repeat;boolean cancelled;public KeyEvent(int a,int c,int r,boolean x){action=a;code=c;repeat=r;cancelled=x;}public int getAction(){return action;}public int getKeyCode(){return code;}public int getRepeatCount(){return repeat;}public boolean isCanceled(){return cancelled;}}''',
 'io/tvos/HoldHarness.java':'''package io.tvos;
import java.util.*;import android.view.KeyEvent;import android.content.Intent;import io.tvos.home.RecommendationHold;
class Prefs {static boolean collection;static boolean homeCollection(){return collection;}}
class DockBackdrop {static class Artwork {String owner="app",title="Shown film";}}
class HomeApps {static final int APP=1;static class Item {int kind=APP;String pkg="app";}}
class AppStrings {static String get(int id){return "Cannot open ";}}
class R {static class string {static final int home_open_error_prefix=1;}}
class AppLaunch {static Intent opened;static int calls;static boolean start(Object a,Intent i,String s){opened=i;calls++;return true;}}
public class HoldHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 long now;boolean running=true,window=true;static final int Z_SHELF=2;int zone=Z_SHELF,moves,clicks;
 Object activity=new Object();boolean hasWindowFocus(){return window;}void move(int dx,int dy){check(dx==0&&dy==-1,"short Up");moves++;zone=4;}void showToast(String s){}
 class State {boolean open;}final State grid=new State(),drawer=new State();
 class Editor {boolean edit;boolean editing(){return edit;}}final Editor dockEditor=new Editor();
 class Continue {boolean shown;boolean visible(){return shown;}}final Continue dockContinue=new Continue();
 class Dock {int index;List<HomeApps.Item> items=new ArrayList<>(Arrays.asList(new HomeApps.Item()));}final Dock dock=new Dock();
 class Click {void open(){clicks++;}}final Click click=new Click();
 final Map<Runnable,Long> timers=new LinkedHashMap<>();
 final RecommendationHold recommendationHold=new RecommendationHold(new RecommendationHold.Scheduler(){
  public void postDelayed(Runnable r,long delay){check(delay==600,"600ms threshold");timers.put(r,now+delay);}
  public void remove(Runnable r){timers.remove(r);}
 },()->now);
 DockBackdrop.Artwork recommendationArtwork=new DockBackdrop.Artwork();
 Intent recommendationIntent=new Intent("intent://shown-film/42");
'''+methods+'''
 void advance(long ms){now+=ms;for(Runnable r:new ArrayList<>(timers.keySet())){Long due=timers.get(r);if(due!=null&&due<=now){timers.remove(r);r.run();}}}
 boolean key(int action,int repeat,boolean cancel){return recommendationKey(new KeyEvent(action,19,repeat,cancel));}
 static HoldHarness fresh(){AppLaunch.calls=0;AppLaunch.opened=null;Prefs.collection=false;return new HoldHarness();}
 public static void main(String[] args){
  HoldHarness h=fresh();check(h.recommendationAvailable(),"published target eligible");
  check(h.key(0,0,false)&&h.moves==0,"Down stays on Dock while classifying press");h.advance(599);check(AppLaunch.calls==0,"no early launch");
  h.key(1,0,false);check(h.moves==1&&AppLaunch.calls==0&&!h.recommendationHold.active(),"short release navigates once");h.advance(1000);check(AppLaunch.calls==0,"short press removes delayed launch");
  h=fresh();h.key(0,0,false);h.advance(600);check(AppLaunch.calls==1&&AppLaunch.opened.link.equals("intent://shown-film/42")&&h.moves==0,"long Up opens exact visible film");
  h.key(0,5,false);h.advance(2000);h.key(1,0,false);check(AppLaunch.calls==1&&h.moves==0,"repeat and release after long press cannot relaunch or navigate");
  h=fresh();h.key(0,0,false);h.key(1,0,true);h.advance(1000);check(h.moves==0&&AppLaunch.calls==0,"cancelled release performs no action");
  h=fresh();check(!h.key(1,0,false)&&AppLaunch.calls==0,"orphan release cannot launch");
  h=fresh();h.key(0,0,false);check(!h.recommendationKey(new KeyEvent(0,21,0,false)),"other direction remains unconsumed");h.advance(1000);check(AppLaunch.calls==0&&!h.recommendationHold.active(),"other key cancels hold");
  h=fresh();h.key(0,0,false);h.cancelRecommendationHold();h.advance(1000);check(AppLaunch.calls==0,"pause/window reset cancels hold");
  h=fresh();h.key(0,0,false);h.recommendationArtwork=new DockBackdrop.Artwork();h.advance(600);check(AppLaunch.calls==0,"replacement recommendation cancels old target");
  h=fresh();h.key(0,0,false);h.dock.items.get(0).pkg="other";h.advance(600);check(AppLaunch.calls==0,"changed Dock app cancels old target");
  h=fresh();h.key(0,0,false);h.window=false;h.advance(600);check(AppLaunch.calls==0,"lost window cannot launch");
  h=fresh();h.key(0,0,false);h.now=650;h.key(1,0,false);check(AppLaunch.calls==1&&h.moves==0,"delayed timer still classifies a held release correctly");
  h=fresh();h.key(0,0,false);Runnable old=h.timers.keySet().iterator().next();h.cancelRecommendationHold();h.key(0,0,false);old.run();check(AppLaunch.calls==0,"stale timer cannot fire a new press");h.advance(600);check(AppLaunch.calls==1,"current timer still fires");
  h=fresh();h.key(0,0,false);h.recommendationIntent.link="different";h.advance(600);check(AppLaunch.opened.link.equals("intent://shown-film/42"),"launch uses immutable press snapshot");
  h=fresh();h.recommendationIntent=null;check(!h.recommendationAvailable()&&!h.key(0,0,false),"no link keeps ordinary navigation");
  h=fresh();Prefs.collection=true;check(!h.recommendationAvailable(),"collection view excluded");
  h=fresh();h.dockContinue.shown=true;check(!h.recommendationAvailable(),"Continue Watching keeps existing Up navigation");
  h=fresh();h.grid.open=true;check(!h.recommendationAvailable(),"app grid excluded");h.grid.open=false;h.drawer.open=true;check(!h.recommendationAvailable(),"drawer excluded");
  h=fresh();h.dockEditor.edit=true;check(!h.recommendationAvailable(),"Dock editor excluded");
  h=fresh();h.recommendationArtwork.owner="different-publisher";check(!h.recommendationAvailable(),"foreign publisher cannot open from focused app");
  System.out.println("PASS: real Up adapter, short/long/repeats, exact target, stale timer, lifecycle cancellation and eligibility");
 }
}'''
}
activity=Path('app/src/io/tvos/HomeActivity.java').read_text()
assert 'home.cancelRecommendationHold()' in block(activity,'    @Override public void onWindowFocusChanged(')
assert 'home.cancelRecommendationHold()' in block(activity,'    @Override public boolean dispatchKeyEvent(')
for signature in ['    void pause(', '    void reset(']:
 assert 'cancelRecommendationHold()' in block(source,signature)
assert 'artwork.link.trim().isEmpty()' in block(source,'    private void showDockBackdrop(')
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.HoldHarness'],check=True)
