"""Execute the actual button callback with missing, blocked and broken OEM settings."""
from pathlib import Path
import tempfile,subprocess
from app_launch_fixture import files
from tmdb_metadata_fixture import block
sources=files()
sources['android/provider/Settings.java']=sources['android/provider/Settings.java'].replace('public class Settings {','public class Settings {public static final String ACTION_ACCESSIBILITY_SETTINGS="accessibility";')
method=block(Path('app/src/io/tvos/RemoteShortcutSettings.java').read_text(),'    private void connection(')
sources['io/tvos/Test.java']='''package io.tvos;
import android.content.*;import android.provider.Settings;
class Test extends Context {
 static class R {static class string {static final int remote_accessibility_unavailable=1;}}
 int messages;void message(int id){if(id!=1)throw new AssertionError("wrong message");messages++;}
'''+method+'''
 public static void main(String[] args){
  Test t=new Test();t.connection();
  if(t.starts!=1||t.messages!=0||!Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(t.launched.action))throw new AssertionError("normal launch");
  for(RuntimeException error:new RuntimeException[]{new ActivityNotFoundException("missing"),new SecurityException("blocked"),new IllegalStateException("OEM failure")}){
   t=new Test();t.startFailure=error;t.connection();
   if(t.starts!=1||t.messages!=1)throw new AssertionError("failure must show one message and preserve screen");
   t.startFailure=null;t.connection();if(t.messages!=1||t.launched==null)throw new AssertionError("failure must not poison retry");
  }
  System.out.println("PASS: actual interception button handles absent, blocked and broken accessibility settings and supports retry");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 subprocess.run(['javac','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.Test'],check=True)
