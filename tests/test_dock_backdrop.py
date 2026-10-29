"""Production focus scheduling and channel/image selection without network or Android runtime."""
from pathlib import Path
import tempfile,subprocess
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/DockBackdrop.java').read_text()
methods='\n'.join(block(source, key) for key in ['    void focus(', '    void pause(', '    static int priority(', '    static void add('])
harness='''import java.util.*;public class DockHarness {
 static class Handler {Runnable queued;long delay;void removeCallbacks(Runnable r){queued=null;}void postDelayed(Runnable r,long d){queued=r;delay=d;}}
 static class Backdrop {} final Handler main=new Handler();final Runnable load=()->{};int generation;String focused,cachedPackage;Backdrop cached;List<Backdrop> results=new ArrayList<>();java.util.function.Consumer<Backdrop> changed=results::add;
 METHODS
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] a){DockHarness h=new DockHarness();h.focus("watch.kino");check(h.main.delay==450&&h.generation==1,"debounced focus");h.focus("watch.kino");check(h.generation==1,"same focus does not restart load");h.focus("com.seena.app");check(h.generation==2,"new focus invalidates earlier worker token");h.focus(null);check(h.main.queued==null&&h.results.size()==1&&h.results.get(0)==null,"leaving Dock cancels load and restores gradient");
 h.cachedPackage="watch.kino";h.cached=new Backdrop();h.focus("watch.kino");check(h.main.queued==null&&h.results.get(1)==h.cached,"reuse decoded current photo");h.pause();check(h.focused==null&&h.cached==null&&h.main.queued==null,"pause releases cache and pending request");
 check(priority("Новые фильмы","watch.kino/channel")>priority("Фильмы","movies"),"new movies preferred without rejecting package name");check(priority("История","history")<0&&priority("Continue","continued")<0,"history excluded from recommendation channels");check(priority("Recommendations","recs")>priority("Series","series"),"recommendations preferred");
 List<String> urls=new ArrayList<>();add(urls,"http://example.com/a");add(urls,"file:///private");check(urls.isEmpty(),"only supported remote/provider schemes");for(int i=0;i<10;i++)add(urls,"https://example.com/"+i);check(urls.size()==6,"candidate work bounded");add(urls,urls.get(0));check(urls.size()==6,"duplicates skipped");System.out.println("PASS: Dock focus debounce, cancellation, cache reuse, channel ranking and bounded image candidates");}
}'''.replace('METHODS',methods)
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'DockHarness.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,str(p)],check=True)
 subprocess.run(['java','-cp',d,'DockHarness'],check=True)
