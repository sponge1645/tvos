"""Execute actual pure tap state machine and bounded atomic mapping storage."""
from pathlib import Path
import subprocess,tempfile
harness=r'''import io.tvos.remote.*;import java.util.*;import java.nio.file.*;
public class RemoteTapsTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static class Clock implements TapSequence.Scheduler {
  long now;final Map<Runnable,Long> jobs=new HashMap<>();
  public void later(Runnable r,long delay){jobs.put(r,now+delay);}public void cancel(Runnable r){jobs.remove(r);}
  void advance(long n){long until=now+n;while(true){Runnable next=null;long at=Long.MAX_VALUE;for(Map.Entry<Runnable,Long> e:jobs.entrySet())if(e.getValue()<at){next=e.getKey();at=e.getValue();}if(next==null||at>until)break;now=at;jobs.remove(next);next.run();}now=until;}
 }
 static class Sink implements TapSequence.Sink {final List<String> events=new ArrayList<>();public void action(String a){events.add(a);}public void original(boolean d,boolean c){events.add((d?"down":"up")+(c?":cancel":""));}}
 static void tap(TapSequence t,Clock c){t.down(0);c.advance(30);t.up(false);}
 public static void main(String[] args)throws Exception {
  Clock c=new Clock();Sink s=new Sink();TapSequence t=new TapSequence(c,s,new String[]{"default","settings","vpn"},true);
  tap(t,c);check(s.events.isEmpty(),"single waits for multi tap window");c.advance(299);check(s.events.isEmpty(),"no premature click");c.advance(1);check(s.events.equals(Arrays.asList("down","up")),"original once after window");s.events.clear();
  tap(t,c);c.advance(100);tap(t,c);c.advance(300);check(s.events.equals(Arrays.asList("settings")),"double only");s.events.clear();
  for(int i=0;i<3;i++){tap(t,c);c.advance(40);}check(s.events.equals(Arrays.asList("vpn")),"triple once without launching single");c.advance(1000);check(s.events.size()==1,"no stale timer");s.events.clear();
  tap(t,c);t.cancel();c.advance(1000);check(s.events.isEmpty(),"pause/navigation cancels pending action");
  t.down(0);t.up(true);c.advance(1000);check(s.events.isEmpty(),"cancelled release never clicks");
  t.down(0);t.down(1);c.advance(500);check(s.events.equals(Arrays.asList("down")),"home hold forwarded once");t.cancel();check(s.events.equals(Arrays.asList("down","up:cancel")),"cancelled native hold does not open card");s.events.clear();
  TapSequence global=new TapSequence(c,s,new String[]{"settings","",""},false);global.down(0);c.advance(2000);global.up(false);c.advance(1000);check(s.events.isEmpty(),"global hold never injects or fires short action");tap(global,c);check(s.events.equals(Arrays.asList("settings")),"single-only binding has no 300ms wait");s.events.clear();
  TapSequence noDouble=new TapSequence(c,s,new String[]{"default","none","vpn"},true);tap(noDouble,c);tap(noDouble,c);c.advance(300);check(s.events.isEmpty(),"explicit do-nothing differs from unassigned");
  for(int code:new int[]{0,3,4,19,20,21,22,24,25,26,164,223,224})check(!RemoteBindings.allowed(code),"reserved key "+code);
  boolean rejected=false;try{new RemoteBindings.Binding(23,0,"remote",true,new String[]{"none","","vpn"});}catch(IllegalArgumentException e){rejected=true;}check(rejected,"global OK forbidden");
  Path dir=Files.createTempDirectory("bindings");try {
   RemoteBindings.Binding b=new RemoteBindings.Binding(23,353,"Xiaomi RC",false,new String[]{"default","","vpn"});String[] copy=b.actions();copy[0]="none";check(b.actions()[0].equals("default"),"defensive action array");
   RemoteBindings.write(dir.toFile(),Arrays.asList(b));List<RemoteBindings.Binding> read=RemoteBindings.read(dir.toFile());check(read.size()==1&&read.get(0).matches(23,353,"Xiaomi RC")&&!read.get(0).matches(23,353,"Other"),"identity and roundtrip");
   check(read.get(0).actions()[2].equals("vpn"),"triple persisted");
   for(String bad:new String[]{"app:foo;reboot","shell:input keyevent 3","app:bad name"})check(!RemoteBindings.validAction(bad),"arbitrary command excluded");
   Files.write(dir.resolve("remote-bindings.bin"),new byte[70000]);check(RemoteBindings.read(dir.toFile()).isEmpty(),"oversize fails closed");Files.write(dir.resolve("remote-bindings.bin"),new byte[]{1,2});check(RemoteBindings.read(dir.toFile()).isEmpty(),"truncated fails closed");
   RemoteBindings.write(dir.toFile(),Collections.emptyList());check(RemoteBindings.read(dir.toFile()).isEmpty(),"remove all");
  }finally{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}}
  System.out.println("PASS: actual tap sequences, original/double/triple/hold, cancellation, no global injection, reserved keys, identity, atomic persistence and bounded corrupt input");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'RemoteTapsTest.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',tmp,'app/src/io/tvos/remote/TapSequence.java','app/src/io/tvos/remote/RemoteBindings.java',str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'RemoteTapsTest'],check=True)
