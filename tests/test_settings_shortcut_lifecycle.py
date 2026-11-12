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
class View {void cancelTizenHold(){}boolean handles;boolean onKey(KeyEvent e){Test.log.add("view");return handles;}void pause(){Test.log.add("pause");}}
class Updates {void pause(){Test.log.add("updates-pause");}void changed(){}}
public class Test extends Base {
 static class RemoteShortcutSettings {void pause(){}void close(){}void windowFocus(boolean f){}boolean dispatchKeyEvent(KeyEvent e){return false;}}RemoteShortcutSettings remoteSettings;
 static class StorageSettings {void pause(){}void close(){}void windowFocus(boolean f){}boolean dispatchKeyEvent(KeyEvent e){return false;}}StorageSettings storageSettings;
 static class TizenVersionPicker{void windowFocus(boolean f){}void close(){}}TizenVersionPicker tizenVersions;
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
# Button interception no longer registers a system keyboard or a keyboard handoff service.
manifest=Path('app/AndroidManifest.xml').read_text()
assert 'BIND_INPUT_METHOD' not in manifest
assert 'RemoteKeyboardBridge' not in manifest
assert not Path('app/src/io/tvos/RemoteInputMethod.java').exists()
assert 'ACTION_INPUT_METHOD_SETTINGS' not in Path('app/src/io/tvos/RemoteShortcutSettings.java').read_text()
