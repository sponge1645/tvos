"""Compile/run actual device scheduler and reflection adapter against 9/14-shaped APIs."""
from pathlib import Path
import subprocess
import tempfile
import importlib.util

spec=importlib.util.spec_from_file_location('device_runner',Path('tools/test-volume-hold-device.py'))
runner=importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

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
  h.event("a",115,1,3000);check(h.take(9000)==0);h.event("a",115,0,9100);
  h.event("a",114,1,10000);for(int i=0;i<100;i++)check(h.take(10400+i*50)==25);check(h.take(15400)==0);
  h.event("a",114,0,16000);h.event("a",115,1,17000);check(h.take(18000)==24);check(h.take(18000)==0);
  h.event("a",115,1,18001);check(h.take(18500)==0);h.stop();check(h.take(19000)==0);
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
        runner.compile_sources('java', root, list(root.rglob('*.java')))
        subprocess.run(['java','-cp',directory,'io.tvos.tools.DeviceHarness'],check=True)

# Exercise both direct AudioService signatures, independent of input-manager injection.
for modern in [False, True]:
    with tempfile.TemporaryDirectory() as directory:
        root=Path(directory)
        sources={name:content for name,content in files.items() if not name.endswith('DeviceHarness.java')}
        sources['android/os/IBinder.java']='package android.os;public interface IBinder {}'
        sources['android/os/ServiceManager.java']='package android.os;public class ServiceManager {public static IBinder getService(String name){if(!name.equals("audio"))throw new AssertionError();return new IBinder(){};}}'
        sources['android/media/Recorder.java']='package android.media;public class Recorder {public static String events="";public static boolean reject;}'
        legacy='void adjustSuggestedStreamVolume(int direction,int stream,int flags,String pkg,String caller);'
        legacy_impl='public void adjustSuggestedStreamVolume(int direction,int stream,int flags,String pkg,String caller){if(stream!=Integer.MIN_VALUE||flags!=4101||!pkg.equals("com.android.shell")||!caller.equals("tvOS-volume-test"))throw new AssertionError();Recorder.events+=direction+";";if(Recorder.reject)throw new SecurityException("denied");}'
        modern_method='void handleVolumeKey(android.view.KeyEvent event,boolean tv,String pkg,String caller);'
        modern_impl='public void handleVolumeKey(android.view.KeyEvent event,boolean tv,String pkg,String caller){if(!tv||!pkg.equals("com.android.shell")||!caller.equals("tvOS-volume-test"))throw new AssertionError();Recorder.events+=event.code+":"+event.action+";";if(Recorder.reject&&event.action==0)throw new SecurityException("denied");}'
        methods=modern_method if modern else legacy
        impl=modern_impl if modern else legacy_impl
        sources['android/media/IAudioService.java']='package android.media;public interface IAudioService {'+methods+'class Stub {public static IAudioService asInterface(android.os.IBinder b){return new Fake();}}class Fake implements IAudioService {'+impl+'}}'
        sources['io/tvos/tools/AudioHarness.java']='''package io.tvos.tools;import android.media.Recorder;
public class AudioHarness {public static void main(String[] args)throws Exception {
 VolumeHoldDevice.AudioStepper stepper=new VolumeHoldDevice.AudioStepper();stepper.press(24);stepper.press(25);
 String expected=MODERN?"24:0;24:1;25:0;25:1;":"1;-1;";if(!expected.equals(Recorder.events))throw new AssertionError(Recorder.events);
 Recorder.events="";Recorder.reject=true;boolean failed=false;
 try{stepper.press(24);}catch(java.lang.reflect.InvocationTargetException e){failed=e.getCause() instanceof SecurityException;}
 if(!failed||!(MODERN?"24:0;24:1;":"1;").equals(Recorder.events))throw new AssertionError("failure fallback or missing release");
 System.out.println("PASS: actual direct-audio adapter, TV key pair / legacy suggested stream, shell attribution, flags and no fallback on denial");
}}'''.replace('MODERN','true' if modern else 'false')
        for name,content in sources.items():
            p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
        runner.compile_sources('java',root,list(root.rglob('*.java')))
        subprocess.run(['java','-cp',directory,'io.tvos.tools.AudioHarness'],check=True)

# Run the actual host lifecycle against fake adb; interrupt must send STOP and clean only its own files.
import json
import os
import signal
import sys
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
 assert a[2].endswith('app_process /data/local/tmp io.tvos.tools.VolumeHoldDevice audio')
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
    harness.write_text('import importlib.util\nfrom pathlib import Path\ns=importlib.util.spec_from_file_location("runner",'+repr(str(Path('tools/test-volume-hold-device.py').resolve()))+')\nm=importlib.util.module_from_spec(s);s.loader.exec_module(m)\nm.run(Path("fake.jar"),audio=True)\n')
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
