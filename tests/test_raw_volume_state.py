"""Run the actual raw-event state against standard and arbitrary remotes, without Android callbacks."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/remote/RawVolumeState.java').read_text()
harness=r'''import io.tvos.remote.RawVolumeState;
class Test {
 static int steps,last,learned;static String token="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static String event(String path,int scan,int value){return "[ 237676.355245] "+path+": 0001 "+String.format("%04x %08x",scan,value);}
 static RawVolumeState make(){RawVolumeState s=new RawVolumeState(new RawVolumeState.Sink(){public void step(int d){steps++;last=d;}public void learned(String t,int d,String device,int scan){check(t.equals(token),"session");learned++;}});s.line("add device 4: /dev/input/event1",0);s.line("  name:     \"arbitrary TV remote\"",0);return s;}
 public static void main(String[] args){RawVolumeState s=make();
  s.line(event("/dev/input/event1",115,1),0);s.tick(599);check(steps==0,"first press native, wait for hold");s.tick(600);check(steps==1&&last==1,"standard volume up");s.tick(679);check(steps==1,"bounded repeat rate");s.tick(680);check(steps==2,"repeat");s.line(event("/dev/input/event1",115,0),681);s.tick(800);check(steps==2,"release stops immediately");
  s.line(event("/dev/input/event1",114,1),1000);s.tick(1600);check(last==-1,"standard down");s.line(event("/dev/input/event1",114,2),1601);int count=steps;s.tick(1800);check(steps==count,"hardware repeats suppress synthetic repeats");
  s.line(event("/dev/input/event1",115,1),2000);s.line(event("/dev/input/event1",30,1),2100);s.tick(3000);check(steps==count,"unrelated key cancels held repeat; letters never predefined volume");
  s.learn(1,token,4000);s.line(event("/dev/input/event1",30,1),4010);s.line(event("/dev/input/event1",33,0),4020);check(learned==0,"mismatched UP never calibrates");s.line(event("/dev/input/event1",30,0),4030);check(learned==1,"custom mapping needs complete pair");s.learn(0,"",4040);s.line(event("/dev/input/event1",30,1),4100);s.tick(4700);check(last==1&&steps==count+1,"learned arbitrary scan works");s.cancel();
  s=make();s.map("arbitrary TV remote",33,-1);s.line("add device 5: /dev/input/event9",0);s.line("  name:     \"arbitrary TV remote\"",0);s.line(event("/dev/input/event9",33,1),5000);s.tick(5600);check(last==-1,"mapping survives device path changes");s.cancel();
  s.line("add device 6: /dev/input/event8",0);s.line("  name:     \"physical keyboard\"",0);count=steps;s.line(event("/dev/input/event8",33,1),6000);s.tick(7000);check(steps==count,"same scan on another device stays native");
  s.learn(1,token,8000);s.line(event("/dev/input/event1",40,1),9600);s.line(event("/dev/input/event1",40,0),9700);check(learned==1,"expired calibration cannot save");
  s.learn(0,"",9800);s.line(event("/dev/input/event1",115,1),10000);s.tick(40000);check(steps==count,"lost release bounded at 30 seconds");
  s.line("garbage",0);s.line(event("/dev/input/event1",115,999),0);s.tick(50000);check(steps==count,"malformed and unknown values ignored");
  System.out.println("PASS: raw volume standard/custom mapping, paired learning, device identity, repeat, release, cancellation and deadlines");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);p=root/'io/tvos/remote';p.mkdir(parents=True);(p/'RawVolumeState.java').write_text(source);(root/'Test.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,str(p/'RawVolumeState.java'),str(root/'Test.java')],check=True)
 subprocess.run(['java','-cp',tmp,'Test'],check=True)
