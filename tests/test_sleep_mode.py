"""Exercise actual system sleep commands, validation and failure verification without a TV."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public Context getApplicationContext(){return this;}public static class Store {public Store edit(){return this;}public Store putInt(String k,int v){return this;}public void apply(){}}public Store getSharedPreferences(String k,int mode){return new Store();}}''',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void post(Runnable r){r.run();}}',
'android/widget/Toast.java':'package android.widget;public class Toast {public static final int LENGTH_SHORT=0,LENGTH_LONG=1;public static Toast makeText(android.content.Context c,String t,int n){return new Toast();}public void show(){}}',
'io/tvos/Adb.java':'package io.tvos;class Adb implements AutoCloseable {interface Approval {void waiting();}static Adb connect(android.content.Context c,int timeout,Approval a)throws java.io.IOException{return new Adb();}String shell(String c)throws java.io.IOException{return "";}public void close(){}}',
'io/tvos/AppLog.java':'package io.tvos;class AppLog {static void w(String s){}}',
'io/tvos/AppStrings.java':'package io.tvos;class AppStrings {static String get(int id){return "text";}}',
'io/tvos/R.java':'package io.tvos;class R {static class string {static final int settings_sleep_busy=1,runtime_accept_allow_usb_debugging_on_the_tv_tick_always_allow=2,settings_sleep_saved=3,settings_sleep_failed=4;}}',
'io/tvos/SleepMode.java':Path('app/src/io/tvos/SleepMode.java').read_text(),
'io/tvos/SleepHarness.java':'''package io.tvos;import java.util.*;class SleepHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  long[] expected={300000,600000,900000,1800000,3600000,7200000,14400000};
  for(int i=0;i<expected.length;i++){final long value=expected[i];List<String> commands=new ArrayList<>();SleepMode.apply(c->{commands.add(c);return c.contains(" get ")?value+"\\n":"";},i);check(commands.equals(Arrays.asList("settings --user current put secure sleep_timeout "+value,"settings --user current get secure sleep_timeout")),"exact duration, current user and readback");}
  List<String> commands=new ArrayList<>();SleepMode.apply(c->{commands.add(c);return "";},-1);check(commands.equals(Arrays.asList("input keyevent 223")),"explicit sleep never toggles or wakes power");
  for(int invalid:new int[]{-2,7,Integer.MAX_VALUE}){commands.clear();try{SleepMode.apply(c->{commands.add(c);return "";},invalid);throw new AssertionError("invalid choice accepted");}catch(IllegalArgumentException expectedError){}check(commands.isEmpty(),"invalid choice executes nothing");}
  try{SleepMode.apply(c->c.contains(" get ")?"600000":"",0);throw new AssertionError("readback mismatch accepted");}catch(java.io.IOException expectedError){}
  try{SleepMode.apply(c->"Permission denied",0);throw new AssertionError("write denial accepted");}catch(java.io.IOException expectedError){}
  try{SleepMode.apply(c->"SecurityException",-1);throw new AssertionError("sleep denial accepted");}catch(java.io.IOException expectedError){}
  System.out.println("PASS: seven native inactivity timeouts, readback, rejection, explicit sleep and invalid input");
 }
}'''}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.SleepHarness'],check=True)
