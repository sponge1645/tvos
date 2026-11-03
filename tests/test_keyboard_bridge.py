"""Run the production controller worker with bounded simulated ADB/platform responses."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
worker=block(Path('app/src/io/tvos/RemoteKeyboardBridge.java').read_text(),'    private void run()')
harness='''package io.tvos;import java.io.*;import java.util.*;import io.tvos.remote.KeyboardHandoff;
class SystemClock {static long now;static long elapsedRealtime(){long value=now;now+=400;return value;}}
class PowerManager {boolean isInteractive(){return true;}}
class AppLog {static void w(String s){}}
class R {static class string {static int settings_remote_keyboard_failed=1;}}
class AppStrings {static String get(int id){return "failed";}}
class Toast {static final int LENGTH_LONG=1;static int errors;static Toast makeText(Object c,String s,int n){return new Toast();}void show(){errors++;}}
class Adb implements AutoCloseable {
 static final List<String> commands=new ArrayList<>();static String selected,mode;static int connections,polls,closes;
 static Adb connect(Object c,int ms,Object approval)throws IOException{connections++;if(mode.equals("no-adb"))throw new IOException();return new Adb();}
 String shell(String command)throws IOException{
  commands.add(command);
  if(command.startsWith("ime set '")){selected=command.substring(9,command.length()-1);return "selected";}
  polls++;
  if(mode.equals("unknown"))return "unsupported";
  if(mode.equals("failure")&&polls==3)throw new IOException();
  if(mode.equals("manual")&&polls==3)selected="manual/.Keyboard";
  return "mInputShown="+(polls<=4);
 }
 public void close(){closes++;}
}
public class Test {
 static final String OWN="io.tvos/.RemoteInputMethod",TEXT="com.google.android.inputmethod.latin/.LatinIME",POWER_SERVICE="power";
 static final int STOP_FOREGROUND_REMOVE=1;
 volatile boolean running=true,restoreRequested;volatile Adb connection;static long quietUntil;boolean stopped,foregroundRemoved;
 static String target(Object c){return TEXT;}static String ownId(Object c){return OWN;}
 String selected(){return Adb.selected;}static boolean same(String a,String b){return Objects.equals(a,b);}
 Object getSystemService(String key){return new PowerManager();}Object getMainLooper(){return null;}
 void stopForeground(int what){foregroundRemoved=true;}void stopSelf(){stopped=true;}
'''+worker+'''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Test scenario(String mode,String selected){Adb.commands.clear();Adb.mode=mode;Adb.selected=selected;Adb.polls=Adb.connections=Adb.closes=0;SystemClock.now=0;Toast.errors=0;return new Test();}
 static long switches(){return Adb.commands.stream().filter(c->c.startsWith("ime set")).count();}
 public static void main(String[] args){
  Test t=scenario("normal",OWN);t.run();check(Adb.selected.equals(OWN)&&switches()==2,"select Gboard and restore once on close");check(t.stopped&&t.foregroundRemoved&&Adb.closes==1,"close connection and remove transient foreground service");
  t=scenario("no-adb",OWN);t.run();check(Adb.selected.equals(OWN)&&switches()==0&&Toast.errors==1&&t.stopped,"ADB failure cannot strand text keyboard");
  t=scenario("unknown",OWN);t.run();check(switches()==0&&t.stopped,"preflight rejects unknown firmware format");
  t=scenario("normal","manual/.Keyboard");t.run();check(switches()==0,"manual choice before connection wins");
  t=scenario("manual",OWN);t.run();check(switches()==1&&Adb.selected.equals("manual/.Keyboard"),"manual switch during typing is never reversed");
  t=scenario("failure",OWN);t.run();check(Adb.selected.equals(OWN)&&switches()==2&&Adb.connections==2,"poll failure reconnects once and rolls back own delegation");
  t=scenario("normal",TEXT);t.restoreRequested=true;t.run();check(Adb.selected.equals(OWN)&&switches()==1,"explicit notification restores assignments");
  System.out.println("PASS: actual controller worker delegates/restores, preflights ADB/state, respects manual choices, rolls back polling failure and cleans up foreground lifetime");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);p=root/'io/tvos';p.mkdir(parents=True);(p/'Test.java').write_text(harness)
 q=p/'remote';q.mkdir();(q/'KeyboardHandoff.java').write_text(Path('app/src/io/tvos/remote/KeyboardHandoff.java').read_text())
 android=root/'android/os';android.mkdir(parents=True);(android/'Handler.java').write_text('package android.os;public class Handler {public Handler(Object looper){}public void post(Runnable r){r.run();}}')
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.Test'],check=True,timeout=20)
