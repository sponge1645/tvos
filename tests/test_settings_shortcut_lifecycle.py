"""Production Settings key dispatch must execute global mappings once and clean up on pause."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
methods='\n'.join(block(source,sig) for sig in ['    @Override public boolean dispatchKeyEvent(', '    @Override protected void onPause(', '    @Override protected void onDestroy(', '    @Override public void onWindowFocusChanged('])
harness='''import java.util.*;
class KeyEvent {}
class Base {protected void onPause(){}protected void onDestroy(){}public void onWindowFocusChanged(boolean f){}public boolean dispatchKeyEvent(KeyEvent e){Test.log.add("system");return false;}}
class RemoteShortcuts {boolean handles;void cancel(){Test.log.add("cancel");}void close(){Test.log.add("close");}boolean onKey(KeyEvent e){Test.log.add("shortcut");return handles;}}
class RemoteKeys {static boolean handles;static boolean onKey(Object c,KeyEvent e){Test.log.add("legacy");return handles;}}
class Preview {boolean showing,handles;boolean active(){return showing;}boolean onKey(KeyEvent e){Test.log.add("preview");return handles;}void close(){Test.log.add("preview-close");}}
class View {boolean handles;boolean onKey(KeyEvent e){Test.log.add("view");return handles;}void pause(){Test.log.add("pause");}}
class Updates {void pause(){Test.log.add("updates-pause");}void changed(){}}
public class Test extends Base {
 static final List<String> log=new ArrayList<>();RemoteShortcuts shortcuts=new RemoteShortcuts();Preview preview=new Preview();View view=new View();Updates automaticUpdates=new Updates();
'''+methods+'''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){Test t=new Test();KeyEvent e=new KeyEvent();
  t.shortcuts.handles=true;check(t.dispatchKeyEvent(e)&&log.equals(Arrays.asList("shortcut")),"global action consumed before Settings navigation");
  log.clear();t.shortcuts.handles=false;t.view.handles=true;check(t.dispatchKeyEvent(e)&&log.equals(Arrays.asList("shortcut","preview","view")),"unassigned navigation reaches Settings without retired presets");
  log.clear();t.onWindowFocusChanged(false);check(log.equals(Arrays.asList("cancel")),"dialog focus loss cancels pending taps");
  log.clear();t.onPause();check(log.indexOf("cancel")<log.indexOf("pause"),"pause cancels before view teardown");
  log.clear();t.onDestroy();check(t.shortcuts==null&&log.equals(Arrays.asList("close")),"destroy unregisters adapter");
  System.out.println("PASS: actual Settings dispatch, global/vendor shortcut ownership, navigation fallback and lifecycle cancellation/cleanup");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);(root/'Test.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,str(root/'Test.java')],check=True)
 subprocess.run(['java','-cp',directory,'Test'],check=True)
# The transport must preserve the pending show request but never draw its own keyboard.
ime=Path('app/src/io/tvos/RemoteInputMethod.java').read_text()
assert 'onCreateInputView() { return null; }' in ime
assert 'onCreateCandidatesView() { return null; }' in ime
assert 'onEvaluateInputViewShown() { return false; }' in ime
assert 'out.touchableRegion.setEmpty()' in ime
assert 'return RemoteKeyboardBridge.request(this)' in ime

# Android keyboard rows must enable/select the IME, rather than launch the shortcut editor.
import xml.etree.ElementTree as ET
a='{http://schemas.android.com/apk/res/android}'
metadata=ET.parse('app/res/xml/remote_input_method.xml').getroot()
assert metadata.get(a+'settingsActivity') is None
subtypes=metadata.findall('subtype')
assert any(s.get(a+'imeSubtypeMode')=='keyboard' and s.get(a+'isAsciiCapable')=='true' for s in subtypes)
