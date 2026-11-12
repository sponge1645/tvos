"""The actual opacity curve never scales artwork or overlaps outgoing/incoming phases."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/settings/SettingsTileMotion.java').read_text()
harness='''import io.tvos.settings.SettingsTileMotion;class Test{static void check(boolean b){if(!b)throw new AssertionError();}public static void main(String[] args){check(SettingsTileMotion.out(0)==1&&SettingsTileMotion.in(0)==0);check(SettingsTileMotion.out(70)==.5f&&SettingsTileMotion.in(70)==0);check(SettingsTileMotion.out(140)==0&&SettingsTileMotion.in(140)==0);check(SettingsTileMotion.out(210)==0&&SettingsTileMotion.in(210)==.5f);check(SettingsTileMotion.in(280)==1&&SettingsTileMotion.out(280)==0);float old=1,next=0;for(int t=0;t<500;t++){float a=SettingsTileMotion.out(t),b=SettingsTileMotion.in(t);check(a>=0&&a<=old&&b>=next&&b<=1);check(a==0||b==0);old=a;next=b;}System.out.println("PASS: sequential opacity phases, timing, bounds and monotonic easing");}}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);p=root/'io/tvos/settings';p.mkdir(parents=True);(p/'SettingsTileMotion.java').write_text(source);(root/'Test.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,str(p/'SettingsTileMotion.java'),str(root/'Test.java')],check=True)
 subprocess.run(['java','-cp',tmp,'Test'],check=True)
ui=Path('app/src/io/tvos/StorageSettings.java').read_text()
assert 'c.scale(' not in ui
assert 'bitmapPaint.setAlpha' in ui and 'saveLayerAlpha' in ui
assert 'transitions.drawList' in ui and 'sourceView.draw' in ui
assert 'SettingsTileMotion.in' in ui and 'SettingsTileMotion.out' in ui
