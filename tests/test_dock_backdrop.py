"""Production focus scheduling and channel/image selection without network or Android runtime."""
from pathlib import Path
import tempfile,subprocess
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/DockBackdrop.java').read_text()
host=block(Path('app/src/io/tvos/HomeView.java').read_text(),'    private void updateDockBackdrop(')
methods='\n'.join(block(source, key) for key in ['    void focus(', '    void pause(', '    static int priority(', '    static void add('])
harness='''import java.util.*;public class DockHarness {
 static class Handler {Runnable queued;long delay;void removeCallbacks(Runnable r){queued=null;}void postDelayed(Runnable r,long d){queued=r;delay=d;}}
 static class Artwork {} final Handler main=new Handler();final Runnable load=()->{};int generation;String focused,cachedPackage;Artwork cached;List<Artwork> results=new ArrayList<>();java.util.function.Consumer<Artwork> changed=results::add;
 METHODS
 static class HomeFocus {
 static final int Z_SHELF=1;boolean running=true,searchMode,sheetOpen,drawerOpen;int zone=Z_SHELF,shelfIndex;
 static class Prefs {static int count;static int categoryCount(){return count;}}
 static class HomeApps {static final int APP=0;}
 static class Item {int kind;String pkg;Item(String p){pkg=p;}}
 static class Dock {String pkg;int calls,pauses;void focus(String p){pkg=p;calls++;}void pause(){pauses++;}}
 Dock dockBackdrop=new Dock();List<Item> shelf=Arrays.asList(new Item("watch.kino"),new Item("top.rootu.lampa"),new Item("com.seena.app"));
 HOST
 }
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] a){HomeFocus home=new HomeFocus();home.updateDockBackdrop();check(home.dockBackdrop.pkg.equals("watch.kino"),"KinoPub owns background");home.zone=2;home.updateDockBackdrop();home.zone=3;home.updateDockBackdrop();home.sheetOpen=true;home.updateDockBackdrop();check(home.dockBackdrop.calls==1,"upper controls, lower controls and widget keep last service/background and pending load");home.sheetOpen=false;home.zone=1;home.shelfIndex=1;home.updateDockBackdrop();check(home.dockBackdrop.pkg.equals("top.rootu.lampa"),"another Dock service replaces background");home.shelfIndex=2;home.updateDockBackdrop();check(home.dockBackdrop.pkg.equals("com.seena.app"),"Seena replaces background");home.searchMode=true;home.updateDockBackdrop();check(home.dockBackdrop.pauses==1,"search cancels pending Dock work");home.searchMode=false;HomeFocus.Prefs.count=1;home.updateDockBackdrop();check(home.dockBackdrop.pauses==2,"enabled categories cancel Dock work");DockHarness h=new DockHarness();h.focus("watch.kino");check(h.main.delay==450&&h.generation==1,"debounced focus");h.focus("watch.kino");check(h.generation==1,"same focus does not restart load");h.focus("com.seena.app");check(h.generation==2,"new focus invalidates earlier worker token");h.focus(null);check(h.main.queued==null&&h.results.size()==1&&h.results.get(0)==null,"leaving Dock cancels load and restores gradient");
 h.cachedPackage="watch.kino";h.cached=new Artwork();h.focus("watch.kino");check(h.main.queued==null&&h.results.get(1)==h.cached,"reuse decoded current photo");h.pause();check(h.focused==null&&h.cached==null&&h.main.queued==null,"pause releases cache and pending request");
 check(priority("Новые фильмы","watch.kino/channel")>priority("Фильмы","movies"),"new movies preferred without rejecting package name");check(priority("История","history")<0&&priority("Continue","continued")<0,"history excluded from recommendation channels");check(priority("Recommendations","recs")>priority("Series","series"),"recommendations preferred");
 List<String> urls=new ArrayList<>();add(urls,"http://example.com/a");add(urls,"file:///private");check(urls.isEmpty(),"only supported remote/provider schemes");for(int i=0;i<10;i++)add(urls,"https://example.com/"+i);check(urls.size()==6,"candidate work bounded");add(urls,urls.get(0));check(urls.size()==6,"duplicates skipped");System.out.println("PASS: Dock focus debounce, cancellation, cache reuse, channel ranking and bounded image candidates");}
}'''.replace('METHODS',methods).replace('HOST',host)
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'DockHarness.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,str(p)],check=True)
 subprocess.run(['java','-cp',d,'DockHarness'],check=True)
