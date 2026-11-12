"""Execute actual storage navigation/confirmation: selection is never a delete, Back preserves focus."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/StorageSettings.java').read_text()
methods='\n'.join(block(source,sig) for sig in ['        void reveal(', '        void activate(', '        boolean key(', '        @Override public boolean onTouchEvent('])
harness='''import java.util.*;
class KeyEvent{static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_BACK=4,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22;int code,action;KeyEvent(int c,int a){code=c;action=a;}int getKeyCode(){return code;}int getAction(){return action;}boolean isCanceled(){return false;}}
class MotionEvent{static final int ACTION_DOWN=0,ACTION_UP=1;int action;float x,y;MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}int getAction(){return action;}float getX(){return x;}float getY(){return y;}}
class Base{public boolean onTouchEvent(MotionEvent e){return false;}}
class Test extends Base{
 static class Item{boolean removable;Item(boolean r){removable=r;}}List<Item> items=new ArrayList<>();boolean loading,deleting,loadFailed,finished,resumed;int focus,offset,deleted,selected,loads,pressed;float k=1;
 static class Spring{void snap(float v){}}Spring focusSpring=new Spring();boolean ready(){return true;}void finish(){finished=true;}void invalidate(){}void select(){selected++;}void delete(){deleted++;}void load(){loads++;}
'''+methods+'''
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}void tap(int code){key(new KeyEvent(code,0));key(new KeyEvent(code,1));}
 public static void main(String[] args){Test t=new Test();for(int i=0;i<14;i++)t.items.add(new Item(i!=0));
  t.tap(23);check(t.deleted==0,"launcher cannot open uninstall");t.tap(20);check(t.focus==1&&t.deleted==0,"selection alone never opens uninstall");
  t.tap(23);check(t.deleted==1,"trash OK immediately requests native uninstall");
  t.onTouchEvent(new MotionEvent(0,1500,1000));t.onTouchEvent(new MotionEvent(1,1500,1000));check(t.deleted==1,"blank area cannot open uninstall");
  t.onTouchEvent(new MotionEvent(0,1500,290));t.onTouchEvent(new MotionEvent(1,1500,290));check(t.deleted==1,"touching app label only selects");
  t.onTouchEvent(new MotionEvent(0,1800,290));t.onTouchEvent(new MotionEvent(1,1800,290));check(t.deleted==2,"touching basket opens native uninstall");
  t.focus=13;t.reveal();check(t.offset==5,"long list keeps final row visible");t.tap(4);check(t.finished,"Back returns to exact parent entry");
  t=new Test();t.loadFailed=true;t.tap(23);check(t.loads==1&&t.deleted==0,"failed list supports retry");
  System.out.println("PASS: actual native-uninstall activation, protected launcher, touch bounds, scroll visibility and Back focus");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'Test.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'Test'],check=True)
assert all(name in source for name in ['SettingsBackground','SettingsListRenderer','Ui.displaySemibold','drawTrash','loadBanner'])

assert 'confirmFocus' not in source
assert 'pm uninstall' not in Path('app/src/io/tvos/StorageApps.java').read_text()
assert 'REQUEST_DELETE_PACKAGES' in Path('app/AndroidManifest.xml').read_text()
