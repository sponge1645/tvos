"""Run actual UI rehearsal through a deterministic Handler; no network or package installation."""
from pathlib import Path
import subprocess,tempfile
files={
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {long now;Map<Runnable,Long> tasks=new LinkedHashMap<>();public boolean postDelayed(Runnable r,long delay){tasks.put(r,now+delay);return true;}public void removeCallbacks(Runnable r){tasks.remove(r);}public void advance(long amount){long end=now+amount;while(true){Runnable next=null;long at=Long.MAX_VALUE;for(Map.Entry<Runnable,Long> e:tasks.entrySet())if(e.getValue()<at){next=e.getKey();at=e.getValue();}if(next==null||at>end)break;now=at;tasks.remove(next);next.run();}now=end;}}''',
'io/tvos/SelfUpdate.java':'package io.tvos;class SelfUpdate {static final int IDLE=0,CHECKING=1,CURRENT=2,AVAILABLE=3,DOWNLOADING=4,READY=5,ERROR=6;}',
'io/tvos/FakeUpdate.java':Path('app/src/io/tvos/FakeUpdate.java').read_text(),
'io/tvos/FakeHarness.java':'''package io.tvos;import android.os.Handler;import java.util.*;public class FakeHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){Handler h=new Handler();List<Integer> states=new ArrayList<>(),percent=new ArrayList<>();FakeUpdate f=new FakeUpdate(h,(k,p)->{states.add(k);percent.add(p);});
 f.check();check(states.equals(Arrays.asList(1)),"checking starts immediately");h.advance(1499);check(states.size()==1,"checking remains visible");h.advance(1);check(states.get(1)==3,"test update found");
 f.download();check(states.get(2)==4&&percent.get(2)==0,"download starts at zero");f.download();h.advance(8000);check(percent.get(percent.size()-1)==100&&states.get(states.size()-1)==4,"full progress then verification frame");h.advance(400);check(states.get(states.size()-1)==6&&!states.contains(5),"rehearsal ends with explicit message, never READY");
 int count=states.size();f.check();f.cancel();h.advance(2000);check(states.size()==count+1,"disable during check cancels delivery");
 f.check();h.advance(1500);f.download();h.advance(800);f.cancel();count=states.size();h.advance(10000);check(states.size()==count,"disable during download cancels remaining frames");
 f.check();f.check();count=states.size();h.advance(1500);check(states.size()==count+1,"new test generation rejects old callbacks");
 System.out.println("PASS: real fake-update check, all progress percentages, no READY, duplicate input, toggle cancellation and stale generations");
 }
}'''
}
# Verify production routing enters rehearsal before HTTP/file work and blocks installation.
source=Path('app/src/io/tvos/SelfUpdate.java').read_text()
assert source.index('if (fakeSession) { fakeUpdate.download(); return; }') < source.index('File tmp =')
assert 'if (fakeSession) return;' in source[source.index('    void install('):]
assert 'prefs.getBoolean("fake_update", false) ||' in source
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for n,s in files.items():
  p=root/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.FakeHarness'],check=True)
