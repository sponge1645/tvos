"""Execute application work with separately controlled worker and UI queues."""
from pathlib import Path
import subprocess,tempfile
files={
'android/content/Context.java':'package android.content; public class Context {}',
'android/os/Handler.java':'''package android.os;public class Handler {public final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>();public boolean post(Runnable r){queue.add(r);return true;}public void drain(){while(!queue.isEmpty())queue.remove().run();}}''',
'io/tvos/Fakes.java':'''package io.tvos;import android.content.Context;import java.util.*;import java.io.IOException;
class HomeApps {static class Item {final String name;Item(String n){name=n;}}static final List<String> calls=new ArrayList<>();static Context expected;
 static List<Item> result(Context c,String s){if(c!=expected)throw new AssertionError("context");calls.add(s);return new ArrayList<>(Arrays.asList(new Item(s)));}
 static List<Item> shelf(Context c,boolean refresh){if(!refresh)throw new AssertionError("refresh");return result(c,"shelf");}
 static List<Item> allInputs(Context c){return result(c,"input");}static List<Item> drawerShortcuts(){calls.add("shortcut");return new ArrayList<>(Arrays.asList(new Item("shortcut")));}static List<Item> others(Context c){return result(c,"others");}}
class VpnControl {static int calls,fail;static void toggle(Context c)throws IOException {if(c!=HomeApps.expected)throw new AssertionError("context");calls++;if(fail==1)throw new IOException();if(fail==2)throw new IllegalStateException();}}
class AppLog {static int warnings;static void w(String s){warnings++;}}
''',
'io/tvos/WorkTest.java':'''package io.tvos;import android.content.Context;import android.os.Handler;import java.util.*;import java.util.concurrent.Executor;
public class WorkTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}static class Queue implements Executor {ArrayDeque<Runnable> q=new ArrayDeque<>();public void execute(Runnable r){q.add(r);}void run(){q.remove().run();}}
 public static void main(String[] args){Context c=new Context();HomeApps.expected=c;Handler ui=new Handler();Queue worker=new Queue();boolean[] active={true};int[] delivered={0},errors={0};
 HomeAppWork w=new HomeAppWork(c,worker,ui,()->active[0],(s,d,o)->{check(s.get(0).name.equals("shelf")&&d.size()==2&&d.get(0).name.equals("input")&&d.get(1).name.equals("shortcut")&&o.get(0).name.equals("others"),"list sources and input order");delivered[0]++;},()->errors[0]++);
 w.load();w.load();check(HomeApps.calls.isEmpty(),"IO deferred");worker.run();worker.run();check(delivered[0]==0,"UI delivery deferred");ui.drain();check(delivered[0]==2,"same generation loads both delivered");check(HomeApps.calls.equals(Arrays.asList("shelf","input","shortcut","others","shelf","input","shortcut","others")),"shared queue order");
 w.load();w.invalidate();check(w.epoch()==1,"generation shared with launch host");worker.run();ui.drain();check(delivered[0]==2&&HomeApps.calls.size()==12,"invalidated work runs but cannot deliver");
 w.load();worker.run();active[0]=false;ui.drain();check(delivered[0]==2,"inactive delivery suppressed");active[0]=true;w.load();worker.run();w.invalidate();ui.drain();check(delivered[0]==2,"resume cannot revive old delivery");w.load();worker.run();ui.drain();check(delivered[0]==3,"new generation delivers");
 VpnControl.fail=1;w.toggleVpn();w.toggleVpn();check(worker.q.size()==1,"busy prevents duplicate command");worker.run();w.toggleVpn();check(worker.q.isEmpty()&&errors[0]==0,"busy until UI completion");ui.drain();check(errors[0]==1&&AppLog.warnings==1,"IO failure reported once");
 VpnControl.fail=2;w.toggleVpn();worker.run();w.invalidate();ui.drain();check(errors[0]==1&&AppLog.warnings==2,"stale runtime failure silent");w.toggleVpn();worker.run();active[0]=false;ui.drain();check(errors[0]==1,"inactive failure silent");active[0]=true;VpnControl.fail=0;w.toggleVpn();worker.run();ui.drain();check(VpnControl.calls==4&&errors[0]==1,"busy resets after stale/inactive delivery; success silent");
 System.out.println("PASS: application IO ordering, lifecycle generations, UI delivery and VPN busy/error handling");}}
'''}
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 files['io/tvos/HomeAppWork.java']=Path('app/src/io/tvos/HomeAppWork.java').read_text()
 for name,source in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.WorkTest'],check=True)
