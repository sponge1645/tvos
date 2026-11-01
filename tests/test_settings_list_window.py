"""Exercise list-window policy and real D-pad/accessibility geometry adapters."""
from pathlib import Path
import subprocess
import tempfile
from tmdb_metadata_fixture import block
from settings_remote_fixture import write as write_remote
from settings_list_renderer_fixture import geometry_sources
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
harness='''import io.tvos.settings.*;import android.view.KeyEvent;import android.graphics.RectF;import static io.tvos.settings.SettingsScreen.*;
public class WindowHarness {
 final SettingsListWindow listWindow=new SettingsListWindow(10);int focus,screen=PICKER,size=35,clicks,invalidations,announcements;
 final Click tvosClick=new Click();class Click {void move(){clicks++;}}
 final Spoken spoken=new Spoken();class Spoken {void focusMoved(){announcements++;}}
 boolean scrolling(){return screen==PICKER||screen==SHELF_EDIT;}int rowCount(){return scrolling()?listWindow.visibleRows(size):8;}int listSize(){return size;}void invalidate(){invalidations++;}
 final SettingsRemoteInput input=new SettingsRemoteInput();
 final SettingsRemoteInput.Actions actions=new SettingsRemoteInput.Actions(){public void vertical(int d,boolean down,boolean first){moveFocus(d,down,first);}public void horizontal(int d,boolean down,boolean first){}public void confirm(){}public void back(boolean activate,boolean root){}};
 boolean key(int code,boolean down,int repeat){return input.onKey(new KeyEvent(down?KeyEvent.ACTION_DOWN:KeyEvent.ACTION_UP,code,repeat,false),screen==MAIN,actions);}
''' + block(source,'        private void moveFocus(') + '''
 static final float LIST_TOP=186,ROW_H=62,MAIN_ROW_GAP=22,ABOUT_ROW_GAP=22,ABOUT_NAME_Y_OFFSET=3,ABOUT_DETAILS_Y_OFFSET=13,LIST_X=1000,LIST_R=1824,ABOUT_NAME_RIGHT_EXTENSION=6;
 static class Position {float value;}final Position mainScroll=new Position(),listScroll=new Position();final RectF focusBox=new RectF();float k=1;
''' + block(source,'        private RectF rowBox(')+block(source,'        private SettingsListRenderer.Layout rowLayout(') + '''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void near(float a,float b){check(Math.abs(a-b)<.001f,a+" != "+b);}
 public static void main(String[] args){
 for(int capacity:new int[]{1,3,10})for(int size=0;size<100;size++){
  SettingsListWindow w=new SettingsListWindow(capacity);int rows=w.visibleRows(size),focus=0;
  check(!w.canMove(-1,focus,rows,size,true),"top boundary");
  for(int at=1;at<size;at++){
   check(w.canMove(1,focus,rows,size,true),"next item available");focus=w.move(1,focus,rows,size,true);
   check(w.absoluteRow(focus)==at&&focus>=0&&focus<rows&&w.offset()>=0&&w.offset()+rows<=size,"one item per move, bounded window");
  }
  check(!w.canMove(1,focus,rows,size,true),"bottom boundary");int end=w.offset();check(w.move(1,focus,rows,size,true)==focus&&w.offset()==end,"bottom no-op");
  for(int at=size-2;at>=0;at--){focus=w.move(-1,focus,rows,size,true);check(w.absoluteRow(focus)==at,"reverse movement");}
  check(w.offset()==0&&focus==0,"returns to first item");
  for(int selected=-1;selected<size;selected++){
   focus=w.openPicker(selected,size);check(w.absoluteRow(focus)==Math.max(0,selected),"saved item retained");
   check(w.offset()==Math.max(0,Math.min(Math.max(0,selected)-3,size-rows)),"picker preserves context and end clamp");
  }
 }
 WindowHarness v=new WindowHarness();
 v.key(2,true,0);check(v.focus==1&&v.clicks==1&&v.invalidations==1&&v.announcements==1,"key down moves, sounds and announces");
 v.key(2,true,1);check(v.focus==2&&v.clicks==1,"repeat moves silently");v.key(2,false,0);check(v.focus==2&&v.clicks==1&&v.announcements==3,"key release announces without moving");
 v.focus=9;v.key(2,true,0);check(v.focus==9&&v.listWindow.offset()==1&&v.clicks==2,"scroll at lower edge");
 v.focus=0;v.key(1,true,0);check(v.focus==0&&v.listWindow.offset()==0&&v.clicks==3,"scroll at upper edge");
 v.key(1,true,0);check(v.clicks==3,"no boundary sound");v.screen=ABOUT;int n=v.announcements;v.key(2,true,0);check(v.focus==0&&v.announcements==n,"About ignores directional input");
 v.listWindow.openPicker(20,35);int offset=v.listWindow.offset();v.screen=GENERAL;v.focus=7;v.key(2,true,0);check(v.focus==7&&v.listWindow.offset()==offset,"fixed page ignores stored list window");
 for(float k:new float[]{2f/3,1,2})for(int screen=0;screen<=24;screen++)for(int row=0;row<10;row++){
  v.k=k;v.screen=screen;v.mainScroll.value=.5f;v.listScroll.value=v.listWindow.offset()+.25f;RectF box=v.rowBox(row);
  float expected=189+row*84+(screen==ABOUT&&row!=0?10:0)-(screen==MAIN?42:0)-(v.scrolling()?21:0);
  near(box.top,expected*k);near(box.bottom,(expected+62)*k);near(box.left,1000*k);near(box.right,1830*k);
 }
 SettingsListWindow edits=new SettingsListWindow(10);int f=edits.openPicker(20,35);int oldOffset=edits.offset();check(edits.keepVisible(-1,10,f)==f&&edits.offset()==oldOffset,"rejected edit no-op");
 f=edits.keepVisible(2,10,f);check(f==0&&edits.offset()==2,"follow reordering above window");f=edits.keepVisible(30,10,f);check(f==9&&edits.offset()==21,"follow reordering below window");
 System.out.println("PASS: list traversal, boundaries, empty/saved pickers, fixed pages, actual key down/up/repeats/sound/accessibility, all page bounds at 720p/1080p/4K and reorder focus");
 }}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);write_remote(root)
 for name,body in geometry_sources().items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 (root/'WindowHarness.java').write_text(harness)
 for name in ['SettingsListWindow','SettingsScreen']:
  (root/(name+'.java')).write_text(Path('app/src/io/tvos/settings',name+'.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'WindowHarness'],check=True)
