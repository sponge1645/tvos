"""Execute the production IME routing with native system keys and Home/capture exclusions."""
from pathlib import Path
import subprocess, tempfile, xml.etree.ElementTree as ET
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/RemoteInputMethod.java').read_text()
method=block(source,'    private boolean handle(')
harness='''package io.tvos;
class KeyEvent {final int code;KeyEvent(int c){code=c;}int getKeyCode(){return code;}}
class RemoteShortcuts {int calls;boolean consumes;boolean onKey(KeyEvent e){calls++;return consumes;}}
class RemoteKeys {static int calls;static boolean consumes;static boolean onKey(Object c,KeyEvent e){calls++;return consumes;}}
public class Test {
 String targetPackage="";RemoteShortcuts shortcuts=new RemoteShortcuts();String getPackageName(){return "io.tvos";}
'''+method+'''
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  Test t=new Test();t.shortcuts.consumes=true;
  for(String pkg:new String[]{"","io.tvos"}){t.targetPackage=pkg;check(!t.handle(new KeyEvent(185)),"Home/capture keeps original");}
  check(t.shortcuts.calls==0&&RemoteKeys.calls==0,"no duplicate handling");
  t.targetPackage="video.app";
  for(int code:new int[]{3,4,19,20,21,22,24,25,26,164,223,224})
   for(int repeat=0;repeat<8;repeat++)check(!t.handle(new KeyEvent(code)),"native reserved key stream");
  check(t.shortcuts.calls==0&&RemoteKeys.calls==0,"volume and navigation never enter gesture engine");
  check(t.handle(new KeyEvent(185))&&t.shortcuts.calls==1&&RemoteKeys.calls==0,"global binding consumes first");
  t.shortcuts.consumes=false;RemoteKeys.consumes=true;check(t.handle(new KeyEvent(185))&&RemoteKeys.calls==1,"legacy app mapping fallback");
  RemoteKeys.consumes=false;check(!t.handle(new KeyEvent(185)),"unassigned key reaches target app");
  System.out.println("PASS: IME routes assignments, avoids Home duplicates and leaves native volume/navigation streams untouched");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);p=root/'io/tvos';p.mkdir(parents=True);(p/'Test.java').write_text(harness)
 q=p/'remote';q.mkdir();(q/'RemoteBindings.java').write_text(Path('app/src/io/tvos/remote/RemoteBindings.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.Test'],check=True)
service=ET.parse('app/res/xml/remote_key_service.xml').getroot();android='{http://schemas.android.com/apk/res/android}'
assert service.get(android+'canRequestFilterKeyEvents')=='false'
assert 'flagRequestFilterKeyEvents' not in service.get(android+'accessibilityFlags','')
assert 'onKeyEvent(' not in Path('app/src/io/tvos/RemoteKeyService.java').read_text()
assert 'volume-hold.jar' not in Path('build.sh').read_text()
assert 'ACTION_ACCESSIBILITY_SETTINGS' not in Path('app/src/io/tvos/SetupActivity.java').read_text()
assert 'ensureRemoteService' not in Path('app/src/io/tvos/HomeMode.java').read_text()
assert '"automatic".equals(key)' in Path('app/src/io/tvos/LiveTvTargets.java').read_text()
