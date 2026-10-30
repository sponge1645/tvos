"""Actual RSSI buckets, Android-version receiver registration, cleanup and permission fallback."""
from pathlib import Path
import subprocess,tempfile
files={
'io/tvos/network/WifiSignal.java':Path('app/src/io/tvos/network/WifiSignal.java').read_text(),
'android/os/Build.java':'package android.os;public class Build {public static class VERSION {public static int SDK_INT=30;}}',
'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
'android/content/Intent.java':'package android.content;public class Intent {}',
'android/content/IntentFilter.java':'package android.content;public class IntentFilter {public IntentFilter(String s){}}',
'android/content/Context.java':'''package android.content;public class Context {public static final String WIFI_SERVICE="wifi";public static final int RECEIVER_EXPORTED=2;public final android.net.wifi.WifiManager wifi=new android.net.wifi.WifiManager();public int registrations,unregistrations,flags;public BroadcastReceiver receiver;public Context getApplicationContext(){return this;}public Object getSystemService(String s){return wifi;}public Intent registerReceiver(BroadcastReceiver r,IntentFilter f){registrations++;receiver=r;return null;}public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){this.flags=flags;return registerReceiver(r,f);}public void unregisterReceiver(BroadcastReceiver r){unregistrations++;}}''',
'android/net/wifi/WifiInfo.java':'package android.net.wifi;public class WifiInfo {public int rssi=-127;public int getRssi(){return rssi;}}',
'android/net/wifi/WifiManager.java':'package android.net.wifi;public class WifiManager {public static final String RSSI_CHANGED_ACTION="rssi";public boolean denied;public final WifiInfo info=new WifiInfo();public WifiInfo getConnectionInfo(){if(denied)throw new SecurityException();return info;}}',
'WifiHarness.java':'''import io.tvos.network.WifiSignal;public class WifiHarness {static void check(boolean b,String s){if(!b)throw new AssertionError(s);}public static void main(String[] args){int[][] cases={{-127,-1},{0,-1},{Integer.MIN_VALUE,-1},{-95,0},{-86,0},{-85,1},{-71,1},{-70,2},{-56,2},{-55,3},{-40,3}};for(int[] c:cases)check(WifiSignal.levelForRssi(c[0])==c[1],"RSSI boundary");android.content.Context c=new android.content.Context();int[] changes={0};WifiSignal s=new WifiSignal(c,()->changes[0]++);s.start();s.start();check(c.registrations==1,"single registration on Android 11");c.wifi.info.rssi=-62;check(s.level()==2,"live RSSI read");c.receiver.onReceive(c,new android.content.Intent());check(changes[0]==1,"system event requests refresh");c.wifi.denied=true;check(s.level()==-1,"permission failure never invents signal strength");s.stop();s.stop();c.receiver.onReceive(c,new android.content.Intent());check(c.unregistrations==1&&changes[0]==1,"no stale events after stop");android.os.Build.VERSION.SDK_INT=34;s.start();check(c.registrations==2&&c.flags==android.content.Context.RECEIVER_EXPORTED,"Android 13+ registration flags");s.stop();System.out.println("PASS: Wi-Fi signal buckets, system events, lifecycle and unknown strength fallback");}}'''}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,s in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'WifiHarness'],check=True,timeout=10)
