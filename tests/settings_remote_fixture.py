"""Android event boundary for executing the production settings remote protocol."""
from pathlib import Path

def write(root):
 sources={
 'android/view/KeyEvent.java':'''package android.view;public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MULTIPLE=2,KEYCODE_DPAD_UP=1,KEYCODE_DPAD_DOWN=2,KEYCODE_DPAD_LEFT=3,KEYCODE_DPAD_RIGHT=4,KEYCODE_DPAD_CENTER=5,KEYCODE_ENTER=6,KEYCODE_NUMPAD_ENTER=7,KEYCODE_BACK=8;
 private final int action,code,repeats;private final boolean canceled;
 public KeyEvent(int a,int c,int r,boolean canceled){action=a;code=c;repeats=r;this.canceled=canceled;}
 public int getAction(){return action;}public int getKeyCode(){return code;}public int getRepeatCount(){return repeats;}public boolean isCanceled(){return canceled;}}''',
 'io/tvos/settings/SettingsRemoteInput.java':Path('app/src/io/tvos/settings/SettingsRemoteInput.java').read_text()}
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
