"""Production focus-frame scheduling stops at rest and retains delayed focus transitions."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
settings=Path('app/src/io/tvos/SettingsActivity.java').read_text();home=Path('app/src/io/tvos/HomeView.java').read_text()
assert 'new Scene(' not in settings and 'Choreographer' not in settings
assert 'okDown = backDown = false;' in block(settings,'        void pause(')
assert 'okDown = backDown = false;' in block(home,'    void pause(')
assert 'appLoader.cancel();' in block(settings,'        void pause(')
assert 'appLoader.cancel();' in block(settings,'        private void backFromScreen(')
assert 'needsFocusFrame(up)' in block(settings,'        @Override protected void onDraw(')
assert 'postInvalidateDelayed(16)' in block(settings,'        private void drawPageTitle(')
assert 'postInvalidateDelayed(16)' in block(settings,'        private void drawMenuTransition(')
assert 'AppLaunch.start(' in block(settings,'        private void openTvSettings(')
assert 'ACTION_DATE_SETTINGS' not in settings
harness='''package io.tvos;public class IdleTest {
 boolean running=true;long focusReadyAt;Spring[] pop={new Spring(.28f,.85f,0)};
 class Dialog {boolean shown;boolean visible(){return shown;}}Dialog updateDialog=new Dialog();
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+block(settings,'        private boolean needsFocusFrame(')+'''
 public static void main(String[] args){IdleTest t=new IdleTest();check(!t.needsFocusFrame(0),"static page schedules no frame");
  t.focusReadyAt=280;check(t.needsFocusFrame(279),"delayed focus continues to deadline");check(!t.needsFocusFrame(280),"deadline alone does not keep polling");
  t.pop[0].target=1;check(t.needsFocusFrame(280),"focus change needs animation");int frames=0;while(t.needsFocusFrame(300)){t.pop[0].step(1f/60);if(++frames>180)throw new AssertionError("never rests");}check(frames>1,"smooth focus retained");
  t.pop[0].target=0;t.running=false;check(!t.needsFocusFrame(300),"paused view schedules no frame");t.running=true;t.updateDialog.shown=true;check(!t.needsFocusFrame(300),"modal owns its own animation");
  t.updateDialog.shown=false;t.pop[0].snap(0);check(!t.needsFocusFrame(300),"reduced motion snapped focus rests immediately");System.out.println("PASS: idle settings stop frame polling, delayed/animated/reduced-motion focus, modal and pause guards");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'IdleTest.java').write_text(harness);(p/'Spring.java').write_text(Path('app/src/io/tvos/Spring.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,p.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.IdleTest'],check=True)
