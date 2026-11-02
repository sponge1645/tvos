"""Compile/run actual device scheduler and reflection adapter against 9/14-shaped APIs."""
from pathlib import Path
import subprocess
import tempfile

source = Path('tools/volume-hold/VolumeHoldDevice.java').read_text()
files = {
'io/tvos/tools/VolumeHoldDevice.java': source,
'android/view/InputEvent.java': 'package android.view;public class InputEvent {}',
'android/view/KeyEvent.java': '''package android.view;public class KeyEvent extends InputEvent {public final int action,code;public KeyEvent(long down,long now,int a,int c,int repeat,int meta,int device,int scan,int flags,int source){if(down!=123||now!=123||repeat!=0||meta!=0||device!=-1||scan!=0||flags!=0||source!=257)throw new AssertionError();action=a;code=c;}}''',
'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long uptimeMillis(){return 123;}}',
'android/hardware/input/Recorder.java': '''package android.hardware.input;public class Recorder {public static String events="";public static boolean rejectDown;public static boolean send(android.view.InputEvent e,int mode){if(mode!=2)throw new AssertionError();android.view.KeyEvent k=(android.view.KeyEvent)e;events+=k.code+":"+k.action+";";return !rejectDown||k.action!=0;}}''',
'io/tvos/tools/DeviceHarness.java': '''package io.tvos.tools;import android.hardware.input.Recorder;
public class DeviceHarness {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  int[] p=VolumeHoldDevice.parse("[ 1.1] /dev/input/event2: 0001 0073 00000001");check(p[0]==115&&p[1]==1);
  check(VolumeHoldDevice.parse("/dev/input/event2: 0004 0004 000c006c")==null);
  check(VolumeHoldDevice.parse("/dev/input/event2: 0001 0190 00000001")==null);
  VolumeHoldDevice.Hold h=new VolumeHoldDevice.Hold();
  h.event("a",115,1,0);check(h.take(399)==0);check(h.take(400)==24);check(h.take(400)==0);
  h.event("b",115,0,410);check(h.take(450)==24);h.event("a",115,0,460);check(h.take(600)==0);
  h.event("a",114,1,1000);check(h.take(1400)==25);h.event("a",114,2,1401);check(h.take(1600)==0);
  h.event("a",114,0,1700);h.event("a",115,1,2000);h.event("b",114,1,2001);check(h.take(2500)==0);
  h.event("b",114,0,2501);check(h.take(2600)==0);h.event("a",115,0,2601);
  h.event("a",115,1,3000);check(h.take(7000)==0);h.event("a",115,0,7100);
  h.event("a",114,1,8000);for(int i=0;i<40;i++)check(h.take(8400+i*50)==25);check(h.take(10400)==0);
  h.event("a",114,0,11000);h.event("a",115,1,12000);check(h.take(13000)==24);check(h.take(13000)==0);
  h.event("a",115,1,13001);check(h.take(13500)==0);h.stop();check(h.take(14000)==0);
  VolumeHoldDevice.Session s=new VolumeHoldDevice.Session();s.heartbeat=100;check(!s.expired(3099)&&s.expired(3100));s.stop();check(s.stopped);
  VolumeHoldDevice.Injector injector=new VolumeHoldDevice.Injector();injector.press(24);check(Recorder.events.equals("24:0;24:1;"));
  Recorder.events="";Recorder.rejectDown=true;boolean failed=false;try{injector.press(25);}catch(IllegalStateException expected){failed=true;}
  check(failed&&Recorder.events.equals("25:0;25:1;"));
  System.out.println("PASS: actual device parser/scheduler, repeat and release boundaries, ambiguity, caps, watchdog, heartbeat, reflection injection and release-on-rejection");
 }
}'''
}
for manager in ['InputManager', 'InputManagerGlobal']:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        sources = dict(files)
        sources['android/hardware/input/'+manager+'.java'] = '''package android.hardware.input;public class NAME {public static NAME getInstance(){return new NAME();}public boolean injectInputEvent(android.view.InputEvent e,int mode){return Recorder.send(e,mode);}}'''.replace('NAME',manager)
        for name, content in sources.items():
            p=root/name
            p.parent.mkdir(parents=True,exist_ok=True)
            p.write_text(content)
        subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all','-Werror','-d',directory,*map(str,root.rglob('*.java'))],check=True)
        subprocess.run(['java','-cp',directory,'io.tvos.tools.DeviceHarness'],check=True)

# Run the actual host lifecycle against fake adb; interrupt must send STOP and clean only its own files.
import importlib.util
import json
import os
import signal
import sys
spec=importlib.util.spec_from_file_location('device_runner',Path('tools/test-volume-hold-device.py'))
runner=importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
with tempfile.TemporaryDirectory() as directory:
    root=Path(directory)
    fake=root/'adb'
    fake.write_text('#!'+sys.executable+'''\nimport json,os,sys
from pathlib import Path
a=sys.argv[1:]
with Path(os.environ['VOLUME_DEVICE_CALLS']).open('a') as f:f.write(json.dumps(a)+'\\n')
if a==['shell','settings','get','secure','enabled_accessibility_services']:
 print('other/Service:io.tvos/io.tvos.RemoteKeyService')
elif a==['shell','mktemp','-d','/data/local/tmp/tvos-volume.XXXXXX']:
 print('/data/local/tmp/tvos-volume.TEST123')
elif a[0]=='push':pass
elif a[:2]==['shell','-T']:
 assert 'app_process /data/local/tmp io.tvos.tools.VolumeHoldDevice' in a[2]
 print('READY FAKE DEVICE',flush=True)
 for line in sys.stdin:
  if line.strip()=='STOP':
   print('STOPPED FAKE DEVICE',flush=True)
   break
elif a==['shell','rm','-f','/data/local/tmp/tvos-volume.TEST123/helper.jar']:pass
elif a==['shell','rmdir','/data/local/tmp/tvos-volume.TEST123']:pass
else:raise SystemExit('Unexpected arguments: '+repr(a))
''')
    fake.chmod(0o755)
    env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],VOLUME_DEVICE_CALLS=str(root/'calls'))
    harness=root/'host.py'
    harness.write_text('import importlib.util\nfrom pathlib import Path\ns=importlib.util.spec_from_file_location("runner",'+repr(str(Path('tools/test-volume-hold-device.py').resolve()))+')\nm=importlib.util.module_from_spec(s);s.loader.exec_module(m)\nm.run(Path("fake.jar"))\n')
    process=subprocess.Popen([sys.executable,str(harness)],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    import queue
    import threading
    lines=queue.Queue()
    captured=[]
    def collect():
        for line in process.stdout:
            captured.append(line)
            lines.put(line)
    reader=threading.Thread(target=collect,daemon=True)
    reader.start()
    try:
        import time
        deadline=time.monotonic()+8
        ready=False
        while time.monotonic()<deadline:
            try:line=lines.get(timeout=.2)
            except queue.Empty:continue
            if 'READY FAKE DEVICE' in line:
                ready=True
                break
        assert ready,captured
        process.send_signal(signal.SIGINT)
        process.wait(timeout=8)
        reader.join(timeout=2)
        assert not reader.is_alive()
        assert process.returncode==0 and any('STOPPED FAKE DEVICE' in line for line in captured),captured
    finally:
        if process.poll() is None:process.kill();process.wait()
        reader.join(timeout=2)
        process.stdout.close()
    calls=[json.loads(line) for line in (root/'calls').read_text().splitlines()]
    assert calls[-2:]==[['shell','rm','-f','/data/local/tmp/tvos-volume.TEST123/helper.jar'],['shell','rmdir','/data/local/tmp/tvos-volume.TEST123']],calls
print('PASS: actual host runner, heartbeat control, Ctrl+C STOP delivery, child shutdown and scoped temporary cleanup')
