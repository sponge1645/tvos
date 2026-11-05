"""Real request lifecycle: duplicate input, delayed results, cancellation and bounded worker rejection."""
from pathlib import Path
import subprocess,tempfile
files={
 'io/tvos/SettingsTvProviderLoader.java':Path('app/src/io/tvos/SettingsTvProviderLoader.java').read_text(),
 'io/tvos/UiRequest.java':Path('app/src/io/tvos/UiRequest.java').read_text(),
 'io/tvos/ProviderTest.java':'''package io.tvos;import java.util.*;import java.util.concurrent.*;
public class ProviderTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(4));
  List<Runnable> posted=Collections.synchronizedList(new ArrayList<>());boolean[] active={true};int[] reads={0},delivered={0};boolean[] fail={false};
  SettingsTvProviderLoader loader=new SettingsTvProviderLoader(io,posted::add,()->active[0],()->{reads[0]++;if(fail[0])throw new IllegalStateException();return Collections.singletonList(new String[]{"app","TV"});});
  try{
   loader.load(rows->delivered[0]++);loader.load(rows->delivered[0]++);io.submit(()->{}).get(3,TimeUnit.SECONDS);
   check(reads[0]==1&&posted.size()==1,"duplicate confirm submits one request");
   loader.cancel();active[0]=false;active[0]=true;posted.remove(0).run();check(delivered[0]==0,"queued result cannot reopen picker after pause/resume or back/reentry");
   loader.load(rows->delivered[0]++);io.submit(()->{}).get(3,TimeUnit.SECONDS);active[0]=false;posted.remove(0).run();check(delivered[0]==0,"another screen rejects result");
   active[0]=true;loader.load(rows->{check(rows.size()==1,"provider list intact");delivered[0]++;});io.submit(()->{}).get(3,TimeUnit.SECONDS);posted.remove(0).run();check(delivered[0]==1,"new visit can load after discarded result");
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
   io.execute(()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}});entered.await(3,TimeUnit.SECONDS);
   int before=reads[0];loader.load(rows->delivered[0]++);loader.cancel();check(io.getQueue().isEmpty(),"cancel removes queued work");release.countDown();io.submit(()->{}).get(3,TimeUnit.SECONDS);check(reads[0]==before,"cancelled queued lookup never runs");
   fail[0]=true;loader.load(rows->{check(rows.isEmpty(),"platform failure preserves automatic-only fallback");delivered[0]++;});io.submit(()->{}).get(3,TimeUnit.SECONDS);posted.remove(0).run();check(delivered[0]==2,"failure unlocks loader and delivers fallback");
   active[0]=false;loader.load(rows->delivered[0]++);check(io.getQueue().isEmpty()&&posted.isEmpty(),"inactive request is ignored");
   io.shutdown();active[0]=true;loader.load(rows->delivered[0]++);loader.cancel();check(delivered[0]==2,"rejected executor does not crash or deliver");
   System.out.println("PASS: duplicate input, old visit rejection, navigation guard, queued cancellation, retry and error/rejection fallback");
  }finally{io.shutdownNow();}
 }
}'''}
with tempfile.TemporaryDirectory(prefix='tvos-tv-provider-') as tmp:
 root=Path(tmp)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ProviderTest'],check=True)
