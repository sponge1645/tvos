"""Execute the actual shell relay with a fake input device and injection API; test release and owner loss."""
from pathlib import Path
import os,subprocess,tempfile,time,threading
files={
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long elapsedRealtime(){return System.nanoTime()/1000000;}public static long uptimeMillis(){return elapsedRealtime();}}',
'android/util/Base64.java':'package android.util;public class Base64{public static final int NO_WRAP=2,DEFAULT=0;public static String encodeToString(byte[] b,int f){return java.util.Base64.getEncoder().encodeToString(b);}public static byte[] decode(String s,int f){return java.util.Base64.getDecoder().decode(s);}}',
'android/view/InputEvent.java':'package android.view;public class InputEvent{}',
'android/view/InputDevice.java':'package android.view;public class InputDevice{public static final int SOURCE_KEYBOARD=257;}',
'android/view/KeyEvent.java':'package android.view;public class KeyEvent extends InputEvent{public static final int ACTION_DOWN=0,ACTION_UP=1,FLAG_FROM_SYSTEM=8;public int action,code;public KeyEvent(long d,long t,int a,int c,int r,int m,int dev,int s,int f,int src){action=a;code=c;}public static KeyEvent changeAction(KeyEvent e,int a){return new KeyEvent(0,0,a,e.code,0,0,0,0,0,0);}}',
'android/hardware/input/InputManager.java':'package android.hardware.input;public class InputManager{public static InputManager getInstance(){return new InputManager();}public boolean injectInputEvent(android.view.InputEvent e,int mode){android.view.KeyEvent k=(android.view.KeyEvent)e;System.out.println("INJECT "+k.action+" "+k.code);return true;}}',
'org/json/JSONArray.java':'package org.json;public class JSONArray{public JSONArray(String s){}public int length(){return 0;}public JSONObject getJSONObject(int i){return null;}}',
'org/json/JSONObject.java':'package org.json;public class JSONObject{public String getString(String k){return "";}public int getInt(String k){return 0;}}',
}
for name in ['io/tvos/VolumeRelay.java','io/tvos/remote/RawVolumeState.java']:files[name]=Path('app/src/'+name).read_text()
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,source in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 device=root/'getevent'
 device.write_text('''#!/usr/bin/env python3
import sys,time,os
if '-lp' in sys.argv:
 print('add device 1: /dev/input/event1')
 print('  name:     "generic remote"')
else:
 print('[ 1.0] /dev/input/event1: 0001 0073 00000001',flush=True)
 if os.getenv('RELEASE')=='1':
  time.sleep(1.2)
  print('[ 2.2] /dev/input/event1: 0001 0073 00000000',flush=True)
  print('RELEASED',flush=True)
 time.sleep(30)
''');device.chmod(0o755)
 def run(release,owner_eof):
  env=dict(os.environ,PATH=tmp+os.pathsep+os.environ['PATH'],RELEASE=str(release))
  proc=subprocess.Popen(['java','-cp',tmp,'io.tvos.VolumeRelay'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
  lines=[];reader=threading.Thread(target=lambda:lines.extend(proc.stdout),daemon=True);reader.start()
  start=time.monotonic()
  while 'READY\n' not in lines and time.monotonic()-start<3:time.sleep(.02)
  assert 'READY\n' in lines,lines
  if owner_eof:
   for _ in range(5):proc.stdin.write('PING\n');proc.stdin.flush();time.sleep(.35)
   proc.stdin.close();proc.wait(timeout=2)
  else:
   proc.stdin.write('PING\n');proc.stdin.flush();proc.wait(timeout=4)
  reader.join(timeout=1);assert proc.returncode==0,(lines,proc.stderr.read())
  downs=[s for s in lines if s=='INJECT 0 24\n'];ups=[s for s in lines if s=='INJECT 1 24\n']
  assert 1<=len(downs)<=20 and len(downs)==len(ups),(downs,ups,lines)
  # Physical release occurs at 1.2s: all generated downs must stop well before owner EOF at 1.75s.
  if release:assert len(downs)<=9,lines
 run(1,True);run(0,False)
 print('PASS: actual shell relay injects paired system events, stops on physical release, owner EOF and heartbeat timeout')
