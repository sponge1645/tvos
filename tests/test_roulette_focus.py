"""Run production roulette state and remote handlers against deterministic main-loop time."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import subprocess
import tempfile

source = Path('app/src/io/tvos/HomeView.java').read_text()
def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

body = ''.join(method(s) for s in [
    '    private void move(', '    private void back(', '    private void cancelRouletteWork(',
    '    private void spinRoulette(', '    private void finishRoulette(',
    '    private void loadRoulette(',
])
assert 'rouletteWork.reset();' in method('    private void selectTab(')
assert 'cancelRouletteWork();' in method('    void pause(')
assert 'cancelRouletteWork();' in method('    private void beginSearch(')
assert 'if (rouletteWork.spinning()) return;' in method('    private void activate(')
assert 'if (s.spinning) return;' in Path('app/src/io/tvos/HomeAccessibility.java').read_text()

handler = '''package android.os;
import java.util.*;
public class Handler {
 private final Map<Runnable,Long> queue=new LinkedHashMap<>();
 public void postDelayed(Runnable r,long delay){queue.put(r,SystemClock.now+delay);}
 public void removeCallbacks(Runnable r){queue.remove(r);}
 public int size(){return queue.size();}
 public void advance(long ms){SystemClock.now+=ms;while(true){Runnable next=null;
  for(Map.Entry<Runnable,Long> e:queue.entrySet())if(e.getValue()<=SystemClock.now){next=e.getKey();break;}
  if(next==null)return;queue.remove(next);next.run();}}
}
'''
clock = '''package android.os;
public class SystemClock {public static long now=123;public static long uptimeMillis(){return now;}}
'''
recs = '''package io.tvos;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.*;
class Recs {
 static class Pick {final int id;Pick(int id){this.id=id;}}
 static class Call {
  final Consumer<List<Pick>> callback;final UiRequest request;
  Call(Consumer<List<Pick>> c,UiRequest r){callback=c;request=r;}
  void deliver(int count){List<Pick> list=new ArrayList<>();for(int i=0;i<count;i++)list.add(new Pick(i));callback.accept(list);}
 }
 final List<Call> calls=new ArrayList<>();
 final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
 UiRequest loadAcclaimed(Consumer<List<Pick>> callback){
  UiRequest r=new UiRequest(executor);calls.add(new Call(callback,r));return r;
 }
 List<Pick> window;int windows,loads;
 void setPosterWindow(List<Pick> w){window=w;windows++;}
 void poster(Pick p){loads++;}
}
'''
harness = '''package io.tvos;
import java.util.*;
public class RouletteFocusHarness {
 static final int Z_SHELF=1,Z_CARDS=2,Z_HERO=3,Z_STATUS=4,Z_DRAWER=5,WIDGETS=6;
 boolean running=true,enabled=true,sheetOpen,searchMode,tilesDirty,heroDirty;
 int zone=Z_CARDS,shelfIndex,statusIndex,widgetIndex,drawerIndex,lightMoves,cardIndex,tab=4,changes,toasts,retargets;
 static class Item {} static class HomeApps {static class Item extends RouletteFocusHarness.Item {}}
 static class RectF {float centerX(){return 0;}}static class LinearGradient {}
 static class Hero {boolean button;} Hero hero;
 List<HomeApps.Item> shelf=new ArrayList<>(),statusItems=new ArrayList<>(),drawerItems=new ArrayList<>();
 RectF[] statusRects={new RectF()},tileRects={new RectF()};LinearGradient[] tileShaders;
 static class Target {void move(){}void invalidate(){}}Target click=new Target(),platter=new Target();
 static class Ui {static boolean ru=false;}
 final android.os.Handler handler=new android.os.Handler();
 final Recs recs=new Recs();
 final RouletteController rouletteWork=new RouletteController(handler,recs,()->running,()->enabled,()->tab==4,
  ()->changes++,this::finishRoulette);
 static class Dock {boolean editing(){return false;}void move(int dx){}void finish(boolean save){}} final Dock dockEditor=new Dock();
 void moveGrid(int dx,int dy){}
 boolean moveLight(int dx,int dy){lightMoves++;zone=Z_SHELF;return true;}
 int nearest(RectF[] r,float x){return 0;}boolean browse(int dx){return true;}
 void openDrawer(){zone=Z_DRAWER;}void closeDrawer(){}static class Accessibility {boolean talkBack(){return false;}} final Accessibility accessibility=new Accessibility();
 List<Integer> widgets(){return Collections.emptyList();}void retarget(boolean snap){retargets++;}
 void finishDockEdit(boolean save){}void closeSheet(){}void closeSearch(){}
 void wake(){}void showToast(String message){toasts++;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
''' + body + '''
 public static void main(String[] args){
  RouletteFocusHarness h=new RouletteFocusHarness();
  h.running=false;h.loadRoulette();h.running=true;h.enabled=false;h.loadRoulette();h.enabled=true;
  check(h.recs.calls.isEmpty(),"hidden or disabled home does not load");
  h.spinRoulette();h.spinRoulette();
  check(h.recs.calls.size()==1 && h.rouletteWork.loading() && h.toasts==2,"empty spin loads once and keeps loading feedback");
  Recs.Call first=h.recs.calls.get(0);h.cancelRouletteWork();
  check(!first.request.active() && !h.rouletteWork.loading(),"cancel releases load");
  h.loadRoulette();first.deliver(10);
  check(h.rouletteWork.picks().isEmpty() && h.rouletteWork.loading() && h.changes==0,"stale load cannot replace new request");
  h.recs.calls.get(1).deliver(40);
  check(h.rouletteWork.picks().size()==40 && !h.rouletteWork.loading() && h.changes==1,"current load updates catalog once");
  try {h.rouletteWork.picks().clear();throw new AssertionError("mutable catalog exposed");}
  catch(UnsupportedOperationException expected){}
  h.spinRoulette();int winner=h.rouletteWork.winner();long started=h.rouletteWork.started();
  check(h.rouletteWork.spinning() && !h.rouletteWork.finished() && winner>=0 && winner<40,"spin selects valid winner");
  check(h.zone==Z_CARDS && h.cardIndex==0 && h.handler.size()==1,"spin focuses strip and schedules one completion");
  h.spinRoulette();check(h.rouletteWork.winner()==winner && h.rouletteWork.started()==started && h.handler.size()==1,
   "repeat OK cannot restart active spin");
  for(int repeat=0;repeat<20;repeat++)for(int[] d:new int[][]{{0,1},{0,-1},{1,0},{-1,0}})h.move(d[0],d[1]);
  check(h.zone==Z_CARDS && h.lightMoves==0,"arrows and held-key repeats cannot leave running roulette");
  for(int slot=57;slot>=0;slot--){
   h.rouletteWork.preparePosters(slot);
   check(h.recs.window.size()==14,"bounded roulette prefetch");
   check(h.recs.window.get(0)==h.rouletteWork.picks().get(winner),"winner loads first and stays resident");
   for(int offset=-3;offset<=3;offset++){
    int id=Math.floorMod(winner-slot+offset,40);boolean present=false;
    for(Recs.Pick p:h.recs.window)present|=p.id==id;
    check(present,"visible roulette posters stay inside window");
   }
   int count=h.recs.windows;h.rouletteWork.preparePosters(slot);
   check(h.recs.windows==count,"same strip slot does not rebuild window");
  }
  h.back();
  check(!h.rouletteWork.spinning() && !h.rouletteWork.finished() && !h.rouletteWork.loading() && h.zone==Z_SHELF,
        "Back cancels running roulette and returns focus to Dock");
  check(h.handler.size()==0 && h.rouletteWork.started()==0,"Back removes completion and resets start");
  h.handler.advance(6000);check(h.zone==Z_SHELF && !h.rouletteWork.finished(),"cancelled timer cannot steal focus");
  h.spinRoulette();h.handler.advance(4999);check(h.rouletteWork.spinning(),"spin lasts full five seconds");
  h.handler.advance(1);check(!h.rouletteWork.spinning() && h.rouletteWork.finished() && h.zone==Z_CARDS,
   "completion exposes actionable winner at five seconds");
  h.move(0,1);check(h.zone==Z_SHELF && h.lightMoves==1,"normal navigation resumes after winner");
  winner=h.rouletteWork.winner();h.cancelRouletteWork();
  check(h.rouletteWork.finished() && h.rouletteWork.winner()==winner,"pause after completion preserves winner");
  h.rouletteWork.reset();h.zone=Z_CARDS;h.move(0,1);
  check(h.zone==Z_SHELF && h.lightMoves==2 && !h.rouletteWork.finished(),"category reset clears winner presentation and unlocks navigation");
  h.spinRoulette();h.running=false;h.cancelRouletteWork();int count=h.recs.windows;
  h.rouletteWork.preparePosters(20);h.handler.advance(6000);
  check(h.recs.windows==count && !h.rouletteWork.finished(),"paused home loads no posters and completes no spin");
  h.running=true;h.spinRoulette();h.tab=0;h.rouletteWork.reset();h.zone=Z_SHELF;h.handler.advance(6000);
  check(h.zone==Z_SHELF && !h.rouletteWork.finished(),"leaving category cannot receive delayed winner focus");
  h.tab=4;h.loadRoulette();Recs.Call hidden=h.recs.calls.get(2);h.tab=0;hidden.deliver(7);
  check(h.rouletteWork.picks().size()==40,"load callback outside selected category is ignored");
  h.cancelRouletteWork();h.tab=4;h.loadRoulette();h.recs.calls.get(3).deliver(0);h.spinRoulette();
  check(h.recs.calls.size()==5 && !h.rouletteWork.spinning(),"empty response allows loading retry without spinning");
  h.recs.calls.get(4).deliver(1);h.spinRoulette();
  check(h.rouletteWork.winner()==0 && h.recs.window.size()==14,"single-movie catalog remains safe");
  h.cancelRouletteWork();h.recs.executor.shutdownNow();
  System.out.println("PASS: roulette loading, stale callbacks, five-second spin, focus lock, Back, pause, tab reset, winner retention, poster windows");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-roulette-focus-') as directory:
    root = Path(directory)
    write_strings(root)
    (root / 'android/os').mkdir(parents=True)
    (root / 'android/os/Handler.java').write_text(handler)
    (root / 'android/os/SystemClock.java').write_text(clock)
    (root / 'Recs.java').write_text(recs)
    (root / 'RouletteFocusHarness.java').write_text(harness)
    for name in ('RouletteController', 'UiRequest'):
        (root / (name + '.java')).write_text(Path('app/src/io/tvos/' + name + '.java').read_text())
    subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', directory,
                    *map(str, root.rglob('*.java'))], check=True)
    subprocess.run(['java', '-cp', directory, 'io.tvos.RouletteFocusHarness'], check=True)
