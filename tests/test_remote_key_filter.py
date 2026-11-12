"""Actual accessibility filter and capture IPC: native-key passthrough, leases and lifecycle."""
from pathlib import Path
import subprocess,tempfile
files={
'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=33;}}',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now=1000;public static long elapsedRealtime(){return now;}public static long uptimeMillis(){return now;}}',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler{public static List<Runnable> jobs=new ArrayList<>();public Handler(Looper l){}public void postDelayed(Runnable r,long delay){jobs.add(r);}public void removeCallbacks(Runnable r){jobs.removeIf(x->x==r);}}''',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver{public abstract void onReceive(Context c,Intent i);}',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter{public String action;public IntentFilter(String a){action=a;}public void addAction(String a){}}',
'android/content/Intent.java':'''package android.content;import java.util.*;public class Intent{public static final String ACTION_SCREEN_OFF="screen-off",ACTION_SCREEN_ON="screen-on";public String action,pkg;public String getAction(){return action;}Map<String,Object> extras=new HashMap<>();public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public boolean getBooleanExtra(String k,boolean d){Object v=extras.get(k);return v==null?d:(Boolean)v;}public int getIntExtra(String k,int d){Object v=extras.get(k);return v==null?d:(Integer)v;}public String getStringExtra(String k){return (String)extras.get(k);}public long getLongExtra(String k,long d){Object v=extras.get(k);return v==null?d:(Long)v;}@SuppressWarnings("unchecked")public <T>T getParcelableExtra(String k){return (T)extras.get(k);}}''',
'android/content/Context.java':'''package android.content;import java.util.*;public class Context{public static final String AUDIO_SERVICE="audio";public Object getSystemService(String n){return new android.media.AudioManager();}public static final int RECEIVER_NOT_EXPORTED=4;static class Entry{BroadcastReceiver r;IntentFilter f;Entry(BroadcastReceiver r,IntentFilter f){this.r=r;this.f=f;}}static List<Entry> receivers=new ArrayList<>();public static boolean protectedOnly=true;public int registered,unregistered;public String getPackageName(){return "io.tvos";}public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,android.os.Handler h){protectedOnly&="io.tvos.permission.REMOTE_CONTROL".equals(p);registered++;receivers.add(new Entry(r,f));return null;}public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,android.os.Handler h,int flags){protectedOnly&=flags==4;return registerReceiver(r,f,p,h);}public void unregisterReceiver(BroadcastReceiver r){unregistered++;receivers.removeIf(x->x.r==r);}public void sendBroadcast(Intent i,String p){protectedOnly&="io.tvos.permission.REMOTE_CONTROL".equals(p)&&"io.tvos".equals(i.pkg);for(Entry e:new ArrayList<>(receivers))if(e.f.action.equals(i.action))e.r.onReceive(this,i);}}''',
'android/view/KeyEvent.java':'''package android.view;public class KeyEvent{public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25;public long getDownTime(){return 10;}public boolean isCanceled(){return false;}public KeyEvent repeated(){repeat=1;return this;}int code,action,scan,source=257,device=1,repeat;public KeyEvent(int code,int action){this.code=code;this.action=action;scan=code;}public KeyEvent(KeyEvent e){code=e.code;action=e.action;scan=e.scan;source=e.source;device=e.device;repeat=e.repeat;}public InputDevice getDevice(){return device==1?new InputDevice():null;}public KeyEvent vendor(int scanCode){scan=scanCode;return this;}public KeyEvent keyboard(){device=2;return this;}public int getKeyCode(){return code;}public int getAction(){return action;}public int getScanCode(){return scan;}public int getSource(){return source;}public int getDeviceId(){return device;}public int getRepeatCount(){return repeat;}}''',
'android/view/accessibility/AccessibilityEvent.java':'''package android.view.accessibility;public class AccessibilityEvent{public static final int TYPE_WINDOW_STATE_CHANGED=32;String pkg,cls;public AccessibilityEvent(String p,String c){pkg=p;cls=c;}public int getEventType(){return 32;}public CharSequence getPackageName(){return pkg;}public CharSequence getClassName(){return cls;}}''',
'android/accessibilityservice/AccessibilityServiceInfo.java':'package android.accessibilityservice;public class AccessibilityServiceInfo{public static final int FLAG_REQUEST_FILTER_KEY_EVENTS=32;public int flags=8;}',
'android/accessibilityservice/AccessibilityService.java':'''package android.accessibilityservice;public class AccessibilityService extends android.content.Context{public AccessibilityServiceInfo info=new AccessibilityServiceInfo();protected void onServiceConnected(){}public AccessibilityServiceInfo getServiceInfo(){return info;}public void setServiceInfo(AccessibilityServiceInfo i){info=i;}protected boolean onKeyEvent(android.view.KeyEvent e){return false;}public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e){}public void onInterrupt(){}public void onDestroy(){}}''',
'io/tvos/RemoteShortcuts.java':'''package io.tvos;class RemoteShortcuts{static int calls,cancels;static boolean mapped,homeOnly;RemoteShortcuts(Object c,boolean global,Object original){}boolean onKey(android.view.KeyEvent e){calls++;return mapped;}boolean homeBinding(android.view.KeyEvent e){return homeOnly;}void cancel(){cancels++;}void close(){}}''',
'io/tvos/ChiqBootGuard.java':'package io.tvos;class ChiqBootGuard{static void onWindowEvent(Object c,Object e){}}',
'io/tvos/AppLog.java':'package io.tvos;class AppLog{static void w(String s){}}',
}
files['android/view/InputDevice.java']='package android.view;public class InputDevice{public String getName(){return "MStar Smart TV IR Receiver";}}'
files['android/view/ViewConfiguration.java']='package android.view;public class ViewConfiguration{public static int getKeyRepeatTimeout(){return 500;}}'
files['android/media/AudioManager.java']='package android.media;public class AudioManager{public static final int ADJUST_RAISE=1,ADJUST_LOWER=-1,USE_DEFAULT_STREAM_TYPE=-1,FLAG_SHOW_UI=1;public static int steps,last;public void adjustSuggestedStreamVolume(int d,int s,int f){steps++;last=d;}}'
files['io/tvos/AdbVolumeKeys.java']='package io.tvos;class AdbVolumeKeys{interface Learned{void accept(String t,int m,String d,int s);}static int closes,saves;static String token;static Learned learned;static java.util.function.Consumer<String> ready;static java.util.function.Consumer<Boolean> connection;AdbVolumeKeys(Object c,Learned l,java.util.function.Consumer<String> r,java.util.function.Consumer<Boolean> connected){learned=l;ready=r;connection=connected;connected.accept(true);}void learn(String t,int m,long e){token=t;}void save(int m,String d,int s){saves++;}void close(){closes++;}}'
for name in ['RemoteKeyService','RemoteKeyCapture']:
 files['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
for name in ['CaptureLease','RemoteBindings']:
 files['io/tvos/remote/'+name+'.java']=Path('app/src/io/tvos/remote/'+name+'.java').read_text()
files['io/tvos/FilterTest.java']='''package io.tvos;import android.view.*;import android.view.accessibility.*;import android.os.*;import android.content.*;import io.tvos.remote.*;import java.util.*;
class FilterTest{
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}static KeyEvent key(int c,int a){return new KeyEvent(c,a);}
 public static void main(String[] args){
  for(int sdk:new int[]{28,33}){android.os.Build.VERSION.SDK_INT=sdk;RemoteShortcuts.mapped=false;RemoteShortcuts.homeOnly=false;
  RemoteKeyService s=new RemoteKeyService();s.onServiceConnected();check(s.info.flags==40,"request filter without removing other flags");AdbVolumeKeys.connection.accept(false);check(s.info.flags==8,"ADB loss restores native key repeat by disabling early filter");AdbVolumeKeys.connection.accept(true);check(s.info.flags==40,"relay ready restores assigned key filter");
  for(int code:new int[]{0,3,4,19,20,21,22,23,24,25,26,66,160,164,223,224})check(!s.onKeyEvent(key(code,0)),"reserved/navigation/volume/OK stays native: "+code);
  check(!s.onKeyEvent(key(265,0)),"unassigned app key stays native");
  RemoteShortcuts.mapped=true;check(s.onKeyEvent(key(265,0)),"assigned global key enters existing sequence engine");RemoteShortcuts.homeOnly=true;
  check(!s.onKeyEvent(key(265,0)),"Home-only binding not run in another app");s.onAccessibilityEvent(new AccessibilityEvent("io.tvos","io.tvos.HomeActivity"));check(s.onKeyEvent(key(265,0)),"Home-only binding on Home");RemoteShortcuts.homeOnly=false;RemoteShortcuts.mapped=false;
  Context ui=new Context();List<KeyEvent> got=new ArrayList<>();RemoteKeyCapture bridge=new RemoteKeyCapture(ui,got::add);bridge.start();
  check(s.onKeyEvent(key(265,0))&&s.onKeyEvent(key(265,1))&&got.size()==2,"capture forwards press/release once before system handler");
  for(int code:new int[]{23,24,25,3,4,26,19})check(!s.onKeyEvent(key(code,0)),"capture does not swallow native key");
  s.onKeyEvent(key(249,0));bridge.stop();int before=got.size();check(s.onKeyEvent(key(249,1))&&got.size()==before,"stopping capture consumes trailing release without late delivery");
  bridge.start();SystemClock.now+=5001;check(!s.onKeyEvent(key(265,0)),"settings crash/lease expiry ends capture");bridge.stop();bridge.start();s.onAccessibilityEvent(new AccessibilityEvent("other","OtherActivity"));check(!s.onKeyEvent(key(265,0)),"foreign foreground window clears capture");
  bridge.stop();bridge.start();bridge.close();bridge.close();check(ui.unregistered==1&&Handler.jobs.isEmpty(),"close unregisters and cancels renewal once");check(!s.onKeyEvent(key(265,0)),"closed capture no longer consumes unassigned key");
  s.onInterrupt();s.onDestroy();check(s.unregistered==1,"service receiver cleanup");
  }
  RemoteKeyService volumeService=new RemoteKeyService();volumeService.onServiceConnected();Context volumeUi=new Context();final int[] done={0},ready={0};RemoteKeyCapture volumeBridge=new RemoteKeyCapture(volumeUi,e->{});
  volumeBridge.startVolume(1,()->ready[0]++,()->done[0]++);String session=AdbVolumeKeys.token;
  AdbVolumeKeys.ready.accept(session);check(ready[0]==1,"calibration waits for real relay acknowledgement");
  volumeService.onAccessibilityEvent(new AccessibilityEvent("com.android.systemui","VolumePanel"));
  AdbVolumeKeys.learned.accept(session,1,"Generic remote",30);check(done[0]==1&&AdbVolumeKeys.saves==1,"native volume panel does not abort paired calibration");
  volumeBridge.startVolume(-1,()->{},()->done[0]++);session=AdbVolumeKeys.token;volumeBridge.stop();AdbVolumeKeys.learned.accept(session,-1,"Generic remote",33);check(done[0]==1&&AdbVolumeKeys.saves==1,"cancelled calibration never saves late events");volumeBridge.close();volumeService.onDestroy();
  CaptureLease lease=new CaptureLease();String a="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",b="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  lease.update(a,6000,1000);lease.update(b,0,1000);check(lease.token(1000).equals(a),"old session stop cannot cancel newer capture");lease.update(b,9000,1000);check(lease.token(1000).equals(a),"overlong lease rejected");lease.update(a,0,1000);check(lease.token(1000).isEmpty(),"matching stop clears lease");
  check(RemoteBindings.gameController(0x401,100)&&RemoteBindings.gameController(0x401,23),"real controller buttons excluded");check(!RemoteBindings.gameController(0x401,265)&&!RemoteBindings.gameController(0x401,249),"TV launch keys accepted on hybrid remotes");
  check(Context.protectedOnly,"all capture IPC explicitly packaged and signature protected");
  System.out.println("PASS: actual key filter + capture bridge, native keys, Home/global scope, press/release, lease expiry, foreign windows, cleanup and private IPC");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.FilterTest'],check=True)
