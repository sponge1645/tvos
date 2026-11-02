"""Execute the actual accessibility boundary and app-key policy for opt-in key and lifecycle diagnostics."""
from pathlib import Path
import subprocess
import tempfile

files = {
    'io/tvos/RemoteShortcuts.java': 'package io.tvos;class RemoteShortcuts {static boolean consume,home;RemoteShortcuts(android.content.Context c,boolean g,Object o){}boolean onKey(android.view.KeyEvent e){return consume;}boolean homeBinding(android.view.KeyEvent e){return home;}void cancel(){}void close(){}}',
    'io/tvos/RemoteVolume.java': 'package io.tvos;final class RemoteVolume {static int starts,closes,pauses;RemoteVolume(android.content.Context c){}void start(){starts++;}void close(){closes++;}void interrupted(){pauses++;}}',
    'io/tvos/RemoteKeyService.java': Path('app/src/io/tvos/RemoteKeyService.java').read_text(),
    'io/tvos/version/SoftwareBuild.java': Path('app/src/io/tvos/version/SoftwareBuild.java').read_text(),
    'io/tvos/RemoteKeys.java': Path('app/src/io/tvos/RemoteKeys.java').read_text(),
    'android/content/Context.java': 'package android.content;public class Context {public android.content.pm.PackageManager getPackageManager(){throw new AssertionError("volume launch lookup");}public void startActivity(Intent i){throw new AssertionError("volume launch");}}',
    'android/content/Intent.java': 'package android.content;public class Intent {public static final int FLAG_ACTIVITY_NEW_TASK=1;public void addFlags(int f){}}',
    'android/content/pm/PackageManager.java': 'package android.content.pm;public class PackageManager {public android.content.Intent getLeanbackLaunchIntentForPackage(String p){return null;}public android.content.Intent getLaunchIntentForPackage(String p){return null;}}',
    'android/os/Build.java': 'package android.os;public class Build {public static String MANUFACTURER="Other",BRAND="Other";}',
    'android/util/Log.java': '''package android.util;public class Log {
        public static final int DEBUG=3;public static boolean enabled;public static int count,checks;public static String last;
        public static boolean isLoggable(String tag,int level){if(!tag.equals("tvOS-Volume")||level!=DEBUG)throw new AssertionError();checks++;return enabled;}
        public static int d(String tag,String s){count++;last=s;return 0;}
        public static int i(String t,String s){throw new AssertionError();}public static int w(String t,String s){throw new AssertionError();}public static int e(String t,String s,Exception e){throw new AssertionError();}
    }''',
    'android/view/InputDevice.java': '''package android.view;public class InputDevice {public static final int SOURCE_GAMEPAD=1025,SOURCE_JOYSTICK=16777232;public String getName(){return "Xiaomi RC";}public static int[] getDeviceIds(){return new int[0];}public static InputDevice getDevice(int id){return null;}}''',
    'android/view/KeyEvent.java': '''package android.view;public class KeyEvent {
        public static final int ACTION_DOWN=0,KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25;
        public int code,action,repeat;public int getKeyCode(){return code;}public int getAction(){return action;}public int getRepeatCount(){return repeat;}
        public long getDownTime(){return 100;}public long getEventTime(){return 300;}public int getDeviceId(){return 2;}public int getScanCode(){return 115;}public int getFlags(){return 8;}public int getSource(){return 0;}public InputDevice getDevice(){return new InputDevice();}
    }''',
    'android/view/accessibility/AccessibilityEvent.java': 'package android.view.accessibility;public class AccessibilityEvent {public static final int TYPE_WINDOW_STATE_CHANGED=32;public String pkg="",cls="";public int getEventType(){return 32;}public CharSequence getPackageName(){return pkg;}public CharSequence getClassName(){return cls;}}',
    'android/accessibilityservice/AccessibilityServiceInfo.java': 'package android.accessibilityservice;public class AccessibilityServiceInfo {public int flags=32;}',
    'android/accessibilityservice/AccessibilityService.java': '''package android.accessibilityservice;public abstract class AccessibilityService extends android.content.Context {public int connected,unbound,destroyed;public AccessibilityServiceInfo info=new AccessibilityServiceInfo();protected void onServiceConnected(){connected++;}public AccessibilityServiceInfo getServiceInfo(){return info;}public boolean onUnbind(android.content.Intent i){unbound++;return true;}public void onDestroy(){destroyed++;}protected boolean onKeyEvent(android.view.KeyEvent e){return false;}public abstract void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e);public abstract void onInterrupt();}''',
    'io/tvos/ChiqBootGuard.java': 'package io.tvos;final class ChiqBootGuard {static void onWindowEvent(android.content.Context c,android.view.accessibility.AccessibilityEvent e){}}',
    'io/tvos/Prefs.java': '''package io.tvos;final class Prefs {static final int KEY_MI=-10001,KEY_NETFLIX=4062,KEY_YOUTUBE=190,KEY_PRIME=4099;static final int[] KEYS={4062,190,4099,4020,KEY_MI};static String keyApp(int k){throw new AssertionError("volume assignment lookup");}}''',
    'io/tvos/VolumeHarness.java': '''package io.tvos;import android.view.KeyEvent;import android.util.Log;
    public class VolumeHarness {
        static void check(boolean b){if(!b)throw new AssertionError();}
        public static void main(String[] args){
            RemoteKeyService service=new RemoteKeyService();KeyEvent e=new KeyEvent();
            for(String brand:new String[]{"Other","Xiaomi","TCL","CHiQ"}){
                android.os.Build.BRAND=brand;android.os.Build.MANUFACTURER=brand;
                for(int code:new int[]{24,25,19,20,4,7777})for(boolean debug:new boolean[]{false,true}){
                    Log.enabled=debug;int before=Log.count;e.code=code;
                    for(int repeat=0;repeat<4;repeat++){e.action=0;e.repeat=repeat;check(!service.onKeyEvent(e));}
                    e.action=1;e.repeat=0;check(!service.onKeyEvent(e));
                    check(Log.count==before+(debug?5:0));
                    if(debug)check(Log.last.equals("key="+code+" action=1 repeat=0 down=100 time=300 device=2 scan=115 source=0 flags=8 handled=false"));
                }
            }
            Log.enabled=false;int count=Log.count;
            service.onServiceConnected();service.onInterrupt();check(service.onUnbind(null));service.onDestroy();
            check(Log.count==count&&service.connected==1&&service.unbound==1&&service.destroyed==1);
            Log.enabled=true;service.onServiceConnected();check(Log.last.equals("CONNECTED build="+io.tvos.version.SoftwareBuild.SEQUENCE+" flags=32"));
            service.info=null;service.onServiceConnected();check(Log.last.endsWith(" flags=-1"));
            android.view.accessibility.AccessibilityEvent window=new android.view.accessibility.AccessibilityEvent();window.pkg="io.tvos";window.cls="io.tvos.RemoteShortcutSettingsActivity";service.onAccessibilityEvent(window);RemoteShortcuts.consume=true;check(!service.onKeyEvent(e));
            window.pkg="other.app";window.cls="Other";service.onAccessibilityEvent(window);check(service.onKeyEvent(e));RemoteShortcuts.consume=false;
            service.onInterrupt();check(Log.last.equals("INTERRUPT"));
            check(service.onUnbind(null));check(Log.last.equals("UNBIND"));
            service.onDestroy();check(Log.last.equals("DESTROY"));
            check(service.connected==3&&service.unbound==2&&service.destroyed==2);
            check(RemoteVolume.starts==3&&RemoteVolume.closes==3&&RemoteVolume.pauses==2);
            System.out.println("PASS: volume/arrows/unknown down/up/repeats passthrough across brands, live opt-in metadata and lifecycle tracing with superclass delegation");
        }
    }''',
}
with tempfile.TemporaryDirectory() as d:
    root = Path(d)
    for name, source in files.items():
        p = root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(source)
    subprocess.run(['java', 'com.sun.tools.javac.Main', '-Xlint:all', '-Werror', '-d', d,
                    *map(str, root.rglob('*.java'))], check=True)
    subprocess.run(['java', '-cp', d, 'io.tvos.VolumeHarness'], check=True)
