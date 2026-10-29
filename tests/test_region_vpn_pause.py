"""Actual state-checked VPN pause: original off/on, failed lookup cleanup and failed restoration."""
from pathlib import Path
import subprocess,tempfile
files={
'io/tvos/AppLog.java':'package io.tvos;class AppLog {static void w(String s){}}',
'android/content/Context.java':'package android.content;public class Context {public static final String CONNECTIVITY_SERVICE="connectivity";public static final int MODE_PRIVATE=0;public Object getSystemService(String s){return null;}public Context getSharedPreferences(String n,int m){return this;}public String getString(String k,String d){return "llc.itdev.incy";}}',
'android/net/ConnectivityManager.java':'package android.net;public class ConnectivityManager {}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long elapsedRealtime(){return 0;}}',
'io/tvos/NetworkStatus.java':'package io.tvos;class NetworkStatus {static boolean detectVpn(Object m){return false;}}',
'io/tvos/VpnControl.java':'package io.tvos;class VpnControl {static final String PACKAGE="llc.itdev.incy";static void toggle(Object c){}}',
'io/tvos/RegionVpnPause.java':Path('app/src/io/tvos/RegionVpnPause.java').read_text(),
'io/tvos/PauseHarness.java':'''package io.tvos;import java.io.*;public class PauseHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static class Fake implements RegionVpnPause.Control {boolean on,stuck,failRestore;long time;int toggles;public boolean connected(){return on;}public void toggle()throws IOException{toggles++;if(failRestore&&toggles==2)throw new IOException("restore refused");if(!stuck)on=!on;}public long now(){return time;}public void waitStep(){time+=100;}}
 public static void main(String[] args)throws Exception{
 Fake f=new Fake();try(RegionVpnPause p=new RegionVpnPause(f)){p.begin();}check(!f.on&&f.toggles==0,"originally off stays off");
 f=new Fake();f.on=true;RegionVpnPause p=new RegionVpnPause(f);p.begin();check(!f.on&&p.remainingMillis()==30000,"connected VPN paused and budget starts after disconnect");f.time+=5000;check(p.remainingMillis()==25000,"remaining budget decreases");p.close();check(f.on&&f.toggles==2,"VPN restored");p.close();check(f.toggles==2,"cleanup idempotent");
 f=new Fake();f.on=true;try(RegionVpnPause q=new RegionVpnPause(f)){q.begin();throw new IOException("lookup failed");}catch(IOException expected){}check(f.on,"lookup error still restores");
 f=new Fake();f.on=true;p=new RegionVpnPause(f);p.begin();f.on=true;p.close();check(f.on&&f.toggles==1,"already reconnected VPN never toggled off");
 f=new Fake();f.on=true;f.stuck=true;p=new RegionVpnPause(f);try{p.begin();throw new AssertionError("timeout expected");}catch(IOException expected){}check(f.on&&f.toggles==1,"failed disconnect leaves original connection alone");
 f=new Fake();f.on=true;f.failRestore=true;p=new RegionVpnPause(f);p.begin();try{p.close();throw new AssertionError("restore failure expected");}catch(IOException expected){}check(!f.on,"restore refusal surfaced, never reported as success");
 System.out.println("PASS: actual Incy pause budget, off/on preservation, cleanup on failure, idempotence, timeout and restoration errors");
 }}'''}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,source in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.PauseHarness'],check=True)
assert 'AutoRegion.ensure' not in Path('app/src/io/tvos/HomeData.java').read_text()
assert 'AutoRegion.ensure' not in Path('app/src/io/tvos/HomeView.java').read_text()
assert 'screen == REGION && !regionWasOpen' in Path('app/src/io/tvos/SettingsActivity.java').read_text()
