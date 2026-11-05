"""Run actual HomeActivity routing/lifecycle methods so pending OK cannot open a new focus target."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/HomeActivity.java').read_text()
def method(signature):
 start=source.index(signature);brace=source.index('{',start);depth=1;end=brace+1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
methods='\n'.join(method(s) for s in ['    @Override protected void onNewIntent(', '    @Override protected void onPause(', '    @Override public void onWindowFocusChanged(', '    @Override public boolean dispatchKeyEvent('])
harness='''import java.util.*;
class KeyEvent {static final int KEYCODE_DPAD_UP=19;int code=19;KeyEvent(){}KeyEvent(int key){code=key;}int getKeyCode(){return code;}}class Intent {boolean restart;boolean getBooleanExtra(String s,boolean d){return restart;}}
class Base {protected void onNewIntent(Intent i){}protected void onPause(){}public void onWindowFocusChanged(boolean f){}public boolean dispatchKeyEvent(KeyEvent e){Test.log.add("platform");return true;}void setIntent(Intent i){}void recreate(){Test.log.add("recreate");}}
class RemoteShortcuts {boolean handled;void cancel(){Test.log.add("cancel");}boolean onKey(KeyEvent e){Test.log.add("shortcut");return handled;}}
class RemoteKeys {static boolean handled;static boolean onKey(Object c,KeyEvent e){Test.log.add("legacy");return handled;}}
class Home {void cancelRecommendationHold(){Test.log.add("hold-cancel");}boolean onKey(KeyEvent e){Test.log.add("home-key");return false;}void pause(){Test.log.add("home-pause");}void reset(boolean b){Test.log.add("reset");}}
class Dream {void focused(boolean f){}void paused(){Test.log.add("dream-pause");}}
class HomeUpdatePrompt {void pause(){}void changed(){}}
class SelfUpdate {static SelfUpdate get(Object c){return new SelfUpdate();}void removeListener(Object l){}}
class HomeData {static HomeData get(Object c){return new HomeData();}void settingsChanged(){Test.log.add("changed");}}
class HomeHarness extends Base {HomeUpdatePrompt updates=new HomeUpdatePrompt();RemoteShortcuts shortcuts=new RemoteShortcuts();Home home=new Home();Dream dreamReturn=new Dream();Object updateListener=new Object();boolean probe;boolean runWatchNextProbe(Intent i){return probe;}
'''+methods+'''}
public class Test {static List<String> log=new ArrayList<>();static void check(boolean b,String s){if(!b)throw new AssertionError(s);}public static void main(String[] a){HomeHarness h=new HomeHarness();h.shortcuts.handled=true;h.dispatchKeyEvent(new KeyEvent());check(log.equals(Arrays.asList("shortcut")),"mapped tap precedes legacy/native handlers");log.clear();h.shortcuts.handled=false;RemoteKeys.handled=false;h.dispatchKeyEvent(new KeyEvent());check(log.equals(Arrays.asList("shortcut","home-key","platform")),"unmapped routing preserved");log.clear();h.onPause();check(log.indexOf("cancel")<log.indexOf("home-pause"),"pending tap canceled before Home pauses");log.clear();h.onNewIntent(new Intent());check(log.equals(Arrays.asList("cancel","reset")),"Home button cancels old OK before focus reset");log.clear();h.onWindowFocusChanged(false);check(log.equals(Arrays.asList("hold-cancel","cancel")),"focus loss cancels hold and pending click even without onPause");log.clear();h.onWindowFocusChanged(true);check(log.isEmpty(),"focus gain does not cancel new sequence");h.shortcuts.handled=true;log.clear();h.dispatchKeyEvent(new KeyEvent(21));check(log.equals(Arrays.asList("hold-cancel","shortcut")),"another mapped key cancels recommendation hold before shortcut dispatch");h.shortcuts=null;h.onPause();h.onNewIntent(new Intent());h.onWindowFocusChanged(false);System.out.println("PASS: actual Home Activity routing, Home intent/reset, pause and window focus cancellation with null-safe startup/teardown");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'Test.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'Test'],check=True)
