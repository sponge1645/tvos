"""Real remote filter: Xiaomi scan identity, configured launch, repeats and foreign devices."""
from pathlib import Path
import tempfile,subprocess
files={
'io/tvos/RemoteKeys.java':Path('app/src/io/tvos/RemoteKeys.java').read_text(),
'android/os/Build.java':'package android.os;public class Build {public static String MANUFACTURER="Other",BRAND="Other";}',
'android/util/Log.java':'package android.util;public class Log {public static void i(String t,String s){}public static void w(String t,String s){}public static void e(String t,String s,Exception e){}}',
'android/view/InputDevice.java':'''package android.view;public class InputDevice {public static final int SOURCE_GAMEPAD=1025,SOURCE_JOYSTICK=16777232;public static InputDevice connected;public String name;public InputDevice(String n){name=n;}public String getName(){return name;}public static int[] getDeviceIds(){return connected==null?new int[0]:new int[]{1};}public static InputDevice getDevice(int i){return connected;}}''',
'android/view/KeyEvent.java':'''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0;public int action,repeat,source,scan,code;public InputDevice device;public int getSource(){return source;}public int getKeyCode(){return code;}public int getScanCode(){return scan;}public int getAction(){return action;}public int getRepeatCount(){return repeat;}public InputDevice getDevice(){return device;}}''',
'android/content/Intent.java':'package android.content;public class Intent {public static final int FLAG_ACTIVITY_NEW_TASK=1;public void addFlags(int f){}}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager {public android.content.Intent getLeanbackLaunchIntentForPackage(String p){return new android.content.Intent();}public android.content.Intent getLaunchIntentForPackage(String p){return null;}}',
'android/content/Context.java':'package android.content;public class Context {public int launches;public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public void startActivity(Intent i){launches++;}}',
'io/tvos/Prefs.java':'''package io.tvos;class Prefs {static final int KEY_MI=-10001,KEY_NETFLIX=4062,KEY_YOUTUBE=190,KEY_PRIME=4099;static final int[] KEYS={4062,190,4099,4020,KEY_MI};static String selected="";static String keyApp(int key){if(key!=KEY_MI)throw new AssertionError("wrong assignment");return selected;}}''',
'io/tvos/MiHarness.java':'''package io.tvos;import android.view.*;public class MiHarness {static void check(boolean b){if(!b)throw new AssertionError();}public static void main(String[] args){android.content.Context c=new android.content.Context();check(!RemoteKeys.supported());InputDevice.connected=new InputDevice("Xiaomi RC");check(RemoteKeys.supported());KeyEvent e=new KeyEvent();e.device=InputDevice.connected;e.scan=400;e.code=185;check(!RemoteKeys.onKey(c,e)&&c.launches==0);Prefs.selected="chosen.app";check(RemoteKeys.onKey(c,e)&&c.launches==1);e.repeat=1;check(RemoteKeys.onKey(c,e)&&c.launches==1);e.action=1;check(RemoteKeys.onKey(c,e)&&c.launches==1);e.action=0;e.repeat=0;e.device=new InputDevice("Other remote");check(!RemoteKeys.onKey(c,e)&&c.launches==1);e.device=InputDevice.connected;e.scan=401;check(!RemoteKeys.onKey(c,e));e.scan=400;e.source=InputDevice.SOURCE_GAMEPAD;check(!RemoteKeys.onKey(c,e));System.out.println("PASS: Xiaomi MI identity, default passthrough, selected launch, repeat/up suppression and foreign/gamepad exclusion");}}'''}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,s in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.MiHarness'],check=True)
