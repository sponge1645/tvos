"""Wizard Home installation uses the same mode request/monitor contract as System settings."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SetupActivity.java').read_text()
code='''package io.tvos;import io.tvos.setup.SetupState;
class HomeTest {
 boolean active=true,homeRequested;int page=SetupState.HOME,skips;
 static class Mode {boolean busy,free;int pending;boolean busy(){return busy;}boolean freeActive(){return free;}void markPending(){pending++;busy=true;}}
 final Mode homeMode=new Mode();static class Finish {int retries;void retryHome(){retries++;}}final Finish finishView=new Finish();
 static class HomeMode {static final String FREE="free";static int requests;static void request(Object owner,String mode){if(!FREE.equals(mode))throw new AssertionError();requests++;}}
 void skip(){skips++;}
'''+block(source,'    private void installHome(')+block(source,'    private void homeModeChanged(')+'''
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){HomeTest h=new HomeTest();h.installHome();h.installHome();check(HomeMode.requests==1&&h.homeRequested&&h.homeMode.pending==1,"one pending HomeMode FREE request");h.homeModeChanged();check(h.skips==0,"wait for actual mode completion");h.homeMode.busy=false;h.homeModeChanged();check(h.finishView.retries==1,"failed/expired attempt can be retried");h.homeMode.free=true;h.active=false;h.homeModeChanged();check(h.skips==0,"paused wizard cannot finish from stale callback");h.active=true;h.homeModeChanged();check(h.skips==1,"successful mode transition completes wizard");System.out.println("PASS: wizard installs through HomeMode, guards pending requests and waits for success");}
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);p=root/'io/tvos';p.mkdir(parents=True);(p/'HomeTest.java').write_text(code);(p/'setup').mkdir();(p/'setup/SetupState.java').write_text(Path('app/src/io/tvos/setup/SetupState.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.HomeTest'],check=True)
