"""Execute the production remote protocol with the Activity's actual input adapters."""
from pathlib import Path
import subprocess
import tempfile
from settings_remote_fixture import write
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
harness='''import android.view.KeyEvent;import static android.view.KeyEvent.*;import io.tvos.settings.*;import static io.tvos.settings.SettingsScreen.*;import java.util.*;
public class RemoteHarness {
 class Hold {boolean onKey(KeyEvent e){return false;}}final Hold tizenHold=new Hold();
 class Panel {boolean onKey(KeyEvent e,boolean visible){return false;}}final Panel icloudPanel=new Panel();
 final SettingsRemoteInput remoteInput=new SettingsRemoteInput();final SettingsListWindow listWindow=new SettingsListWindow(10);
 int screen=GENERAL,focus,invalidations,moves,opens,backs,activations,backActions,announcements,clickChecks;boolean clickAllowed=true,adjustable=true;
 final List<String> edits=new ArrayList<>();
 final Click tvosClick=new Click();class Click {void move(){moves++;}void open(){opens++;}void back(){backs++;}}
 final Spoken spoken=new Spoken();class Spoken {boolean keyClick(){clickChecks++;return clickAllowed;}void focusMoved(){announcements++;}}
 final Dialog updateDialog=new Dialog();class Dialog {boolean shown,result=true;int events;boolean visible(){return shown;}boolean onKey(KeyEvent e){events++;return result;}}
 final List<Integer> buttonEditor=Arrays.asList(0,1,2,3,4,5);
 void activate(){activations++;}void backFromScreen(){backActions++;screen=MAIN;}void invalidate(){invalidations++;}
 boolean scrolling(){return screen==PICKER||screen==SHELF_EDIT;}int rowCount(){return 10;}int listSize(){return 20;}
 boolean canAdjustMenu(){return adjustable;}void moveMenuButton(int d){edits.add("button:"+d);}void moveCategory(int d){edits.add("category:"+d);}void moveShelfApp(int d){edits.add("shelf:"+d);}void adjustMenu(int d){edits.add("value:"+d);}
''' + block(source,'        private final SettingsRemoteInput.Actions remoteActions = new SettingsRemoteInput.Actions()')+';\n'+''.join(block(source,s) for s in ['        boolean onKey(', '        private void moveFocus(', '        private void adjustFocusedRow('])+'''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 boolean event(int action,int code,int repeat,boolean canceled){return onKey(new KeyEvent(action,code,repeat,canceled));}
 void down(int code){check(event(ACTION_DOWN,code,0,false),"down consumed");}void up(int code){check(event(ACTION_UP,code,0,false),"up consumed");}
 public static void main(String[] args){
 for(int code:new int[]{KEYCODE_DPAD_CENTER,KEYCODE_ENTER,KEYCODE_NUMPAD_ENTER}){
  RemoteHarness v=new RemoteHarness();v.up(code);check(v.activations==0&&v.clickChecks==0,"unpaired release ignored");
  v.event(ACTION_DOWN,code,3,false);v.up(code);check(v.activations==0,"repeat without initial press ignored");
  v.down(code);for(int n=1;n<10;n++)v.event(ACTION_DOWN,code,n,false);check(v.activations==0,"holding never activates");
  v.up(code);v.up(code);check(v.activations==1&&v.opens==1&&v.clickChecks==1,"one activation on release");
  v.down(code);v.event(ACTION_UP,code,0,true);v.up(code);check(v.activations==1,"canceled release disarms");
  v.down(code);v.remoteInput.reset();v.up(code);check(v.activations==1,"pause/reset disarms confirm");
  v.clickAllowed=false;v.down(code);v.up(code);check(v.activations==1&&v.opens==1&&v.clickChecks==2,"TalkBack click suppression retains sound/action ordering");
 }
 RemoteHarness v=new RemoteHarness();v.up(KEYCODE_BACK);check(v.backActions==0&&v.invalidations==1&&v.announcements==1,"unpaired Back refreshes without navigation");
 v.down(KEYCODE_BACK);v.event(ACTION_DOWN,KEYCODE_BACK,1,false);check(v.backActions==0,"held Back waits for release");v.up(KEYCODE_BACK);check(v.backActions==1&&v.backs==1&&v.screen==MAIN,"Back navigates once on release");
 check(!v.event(ACTION_DOWN,KEYCODE_BACK,0,false)&&!v.event(ACTION_UP,KEYCODE_BACK,0,false)&&v.backs==2&&v.backActions==1,"root Back falls through, sounds only on release");
 check(!v.event(ACTION_UP,KEYCODE_BACK,0,true)&&v.backs==2,"canceled root release silent");
 v=new RemoteHarness();v.down(KEYCODE_BACK);v.event(ACTION_UP,KEYCODE_BACK,0,true);v.up(KEYCODE_BACK);check(v.backActions==0&&v.backs==0,"canceled Back disarms");v.down(KEYCODE_BACK);v.down(KEYCODE_ENTER);v.remoteInput.reset();v.up(KEYCODE_BACK);v.up(KEYCODE_ENTER);check(v.backActions==0&&v.activations==0,"reset clears both held keys");
 v=new RemoteHarness();v.screen=MENU_BUTTONS;v.down(KEYCODE_DPAD_RIGHT);v.event(ACTION_DOWN,KEYCODE_DPAD_RIGHT,1,false);v.up(KEYCODE_DPAD_RIGHT);check(v.edits.equals(Arrays.asList("button:1","button:1"))&&v.moves==1&&v.invalidations==2,"horizontal repeat edits but does not replay sound; release inert");
 v.screen=CATEGORY_EDIT;v.down(KEYCODE_DPAD_LEFT);v.screen=SHELF_EDIT;v.down(KEYCODE_DPAD_RIGHT);v.screen=GENERAL;v.down(KEYCODE_DPAD_LEFT);check(v.edits.subList(2,5).equals(Arrays.asList("category:-1","shelf:1","value:-1")),"row adapters preserve direction and editor routing");
 v.adjustable=false;int moves=v.moves;v.down(KEYCODE_DPAD_RIGHT);check(v.moves==moves&&v.edits.size()==5,"nonadjustable values remain inert");
 v.screen=ABOUT;int updates=v.invalidations;v.down(KEYCODE_DPAD_DOWN);v.up(KEYCODE_DPAD_UP);check(v.invalidations==updates,"About ignores arrows");
 v=new RemoteHarness();v.updateDialog.shown=true;v.down(KEYCODE_ENTER);check(v.opens==0&&v.activations==0,"modal key down delegated without sound");v.up(KEYCODE_ENTER);v.up(KEYCODE_DPAD_UP);v.up(KEYCODE_BACK);check(v.opens==1&&v.moves==1&&v.backs==1&&v.activations==0&&v.updateDialog.events==4,"modal owns keys with existing release sounds");
 v.updateDialog.result=false;check(!v.event(ACTION_UP,99,0,false),"modal return value preserved");v.updateDialog.shown=false;v.up(KEYCODE_ENTER);check(v.activations==0,"modal press did not arm regular confirm");
 check(!v.event(ACTION_DOWN,99,0,false),"unknown key falls through");
 System.out.println("PASS: actual remote/Activity wiring, confirm aliases/hold/cancel/reset, TalkBack gating, Back/root fallthrough, horizontal edits/repeats, read-only About and modal ownership/sounds");
 }}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);(root/'DownloadGroups.java').write_text(Path('app/src/io/tvos/DownloadGroups.java').read_text().replace('package io.tvos;', ''));write(root)
 (root/'RemoteHarness.java').write_text(harness)
 for name in ['SettingsListWindow','SettingsScreen']:
  (root/(name+'.java')).write_text(Path('app/src/io/tvos/settings',name+'.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'RemoteHarness'],check=True)
