"""Run real native-uninstaller return routing and storage refresh lifecycle."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
activity=Path('app/src/io/tvos/SettingsActivity.java').read_text()
controller=Path('app/src/io/tvos/StorageSettings.java').read_text()
callback=block(activity,'    @Override protected void onActivityResult(')
returned=block(controller,'    void uninstallReturned(')
resume=block(controller,'    void resume(')
harness='''class Intent{}class Base{int baseCalls;protected void onActivityResult(int request,int result,Intent data){baseCalls++;}}
class Test extends Base{static class TizenVersionPicker{static final int UNINSTALL_REQUEST=905,INSTALL_REQUEST=906;TizenVersionPicker(Test t){}void uninstallReturned(){}void installationReturned(){}}TizenVersionPicker tizenVersions;
 static class StorageSettings{static final int UNINSTALL_REQUEST=904;boolean deleting=true,resumed;int loads;static class Shortcuts{void reload(){}}Shortcuts shortcuts=new Shortcuts();void load(){loads++;}
'''+returned+resume+'''}StorageSettings storageSettings=new StorageSettings();
'''+callback+'''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){Test t=new Test();t.onActivityResult(999,0,null);check(t.baseCalls==1&&t.storageSettings.deleting&&t.storageSettings.loads==0,"other activity results stay untouched");
  t.onActivityResult(904,0,null);check(!t.storageSettings.deleting&&t.storageSettings.loads==0,"cancel while paused releases lock; refresh waits for resume");t.storageSettings.resume();check(t.storageSettings.loads==1,"return from native UI reloads storage");
  t.storageSettings.deleting=true;t.onActivityResult(904,-1,null);check(!t.storageSettings.deleting&&t.storageSettings.loads==2,"successful native uninstall refreshes active storage");t.storageSettings=null;t.onActivityResult(904,0,null);check(t.baseCalls==4,"late result after controller close is safe");System.out.println("PASS: actual native uninstall callback, cancellation/success refresh and closed-controller safety");}
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'Test.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'Test'],check=True)
