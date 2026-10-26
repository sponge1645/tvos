"""Run the complete Dock transaction and real tile animation methods with deterministic keys/time."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
handler='''package android.os;import java.util.*;public class Handler {
 public long now;Map<Runnable,Long> queue=new LinkedHashMap<>();
 public void postDelayed(Runnable r,long delay){queue.put(r,now+delay);}public void removeCallbacks(Runnable r){queue.remove(r);}
 public void advance(long ms){now+=ms;for(Runnable r:new ArrayList<>(queue.keySet()))if(queue.get(r)<=now){queue.remove(r);r.run();}}
}'''
keys='''package android.view;public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160,KEYCODE_BACK=4;
 int key,action,repeat;boolean canceled;public KeyEvent(int key,int action,int repeat,boolean canceled){this.key=key;this.action=action;this.repeat=repeat;this.canceled=canceled;}
 public int getKeyCode(){return key;}public int getAction(){return action;}public int getRepeatCount(){return repeat;}public boolean isCanceled(){return canceled;}
}'''
harness='''package io.tvos;
import java.util.*;import android.os.Handler;import android.view.KeyEvent;
class HomeApps {static class Item {String pkg,key;Item(String p,String k){pkg=p;key=k;}}}
class Prefs {static int writes;static List<String> stored;static void setShelf(List<String> p){writes++;stored=new ArrayList<>(p);}}
public class DockHarness implements HomeDockEditor.Host {
 static class Spring {float value,target;}static class LinearGradient {}static class RectF {float left;RectF(float x){left=x;}}
 static class Click {void move(){}}final Click click=new Click();
 final Handler handler=new Handler();final HomeDockEditor editor=new HomeDockEditor(handler,this);
 final HomeApps.Item a=new HomeApps.Item("a","a"),b=new HomeApps.Item("b","b"),settings=new HomeApps.Item(null,"tv-settings");
 List<HomeApps.Item> shelf=new ArrayList<>(Arrays.asList(a,b,settings));
 Spring[] tileFocus={new Spring(),new Spring(),new Spring()},dockSlide={new Spring(),new Spring(),new Spring()};
 LinearGradient[] tileShaders={new LinearGradient(),new LinearGradient(),new LinearGradient()};
 RectF[] tileRects={new RectF(0),new RectF(100),new RectF(200)};
 final Spring dockLift=new Spring();int shelfIndex,dockLiftIndex,begins,saves,cancels;boolean eligible=true,tilesDirty;
 void retarget(boolean snap){}void wake(){}
 public boolean eligible(){return eligible&&!shelf.isEmpty();}public List<HomeApps.Item> items(){return shelf;}public int index(){return shelfIndex;}
 public void begin(){begins++;dockLift.target=1;}
 public void move(int from,int to){shiftDockTiles(from,to);}public void finish(List<HomeApps.Item> order){finishDockTiles(order);}
 public void saved(){saves++;}public void canceled(){cancels++;}
 void key(int code,boolean down){editor.onKey(new KeyEvent(code,down?0:1,0,false));}
 void enter(){editor.arm();handler.advance(500);check(editor.editing(),"hold enters");}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''+block(home,'    private void shiftDockTiles(')+block(home,'    private void finishDockTiles(')+'''
 public static void main(String[] ignored){
  DockHarness h=new DockHarness();h.editor.arm();h.handler.advance(499);check(!h.editor.editing(),"500ms threshold");
  h.editor.cancelHold();h.handler.advance(1);check(h.begins==0,"short press canceled");
  h.editor.arm();h.eligible=false;h.handler.advance(500);check(!h.editor.editing(),"focus eligibility rechecked at hold");
  h.eligible=true;h.enter();h.key(23,true);check(h.editor.editing()&&Prefs.writes==0,"hold repeats cannot save");
  Spring focus=h.tileFocus[0],slide=h.dockSlide[0];LinearGradient shader=h.tileShaders[0];
  h.key(21,true);check(h.shelfIndex==0,"left boundary");h.key(22,true);
  check(h.shelfIndex==1&&h.shelf.get(1)==h.a&&h.tileFocus[1]==focus&&h.dockSlide[1]==slide&&h.tileShaders[1]==shader,"item focus slide and gradient move together");
  check(slide.value==-100&&h.dockSlide[0].value==100,"animated displacement preserved");
  h.key(19,true);h.key(20,true);check(h.editor.editing(),"vertical arrows do not leave edit");
  h.key(23,false);check(h.editor.editing()&&Prefs.writes==0,"initial release arms drop only");
  h.key(4,true);h.editor.onKey(new KeyEvent(4,1,0,true));check(h.editor.editing(),"canceled Back ignored");
  h.key(4,true);h.key(4,false);
  check(!h.editor.editing()&&h.shelf.equals(Arrays.asList(h.a,h.b,h.settings))&&h.shelfIndex==0,"Back restores order and selected item");
  check(h.tileFocus[0]==focus&&h.tileShaders[0]==shader&&h.dockSlide[0]==slide&&slide.value==0&&h.dockLift.target==0,"rollback preserves animation identity and offsets");
  check(Prefs.writes==0&&h.cancels==1,"cancel never persists");
  for(int ok:new int[]{23,66,160}){
   h.enter();h.key(ok,false);h.editor.move(1);h.key(ok,true);
   check(!h.editor.editing()&&h.saves>0&&Prefs.stored.contains("io.tvos.virtual.tvsettings"),"all OK aliases save with virtual settings alias");
  }
  int writes=Prefs.writes;h.enter();List<HomeApps.Item> original=new ArrayList<>(h.shelf);h.editor.move(-1);h.editor.pause();
  check(!h.editor.editing()&&h.shelf.equals(original)&&Prefs.writes==writes,"pause rolls back without saving");
  h.editor.arm();h.editor.pause();h.handler.advance(500);check(!h.editor.editing(),"pause cancels scheduled hold");
  h.shelf.clear();h.editor.arm();h.handler.advance(500);check(!h.editor.editing(),"empty Dock cannot edit");
  System.out.println("PASS: full Dock controller, hold, keys, commit/rollback, pause, virtual app and real focus/slide/gradient preservation");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-dock-editor-') as tmp:
 root=Path(tmp)
 for name,value in {'android/os/Handler.java':handler,'android/view/KeyEvent.java':keys,'io/tvos/HomeDockEditor.java':Path('app/src/io/tvos/HomeDockEditor.java').read_text(),'io/tvos/DockHarness.java':harness}.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.DockHarness'],check=True)
