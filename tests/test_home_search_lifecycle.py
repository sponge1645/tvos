"""Exercise the production search controller and HomeView wiring with a virtual main loop."""
from pathlib import Path
import os
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
    '    private void refreshSearch(',
    '    private void searchInputDismissed(',
    '    private void searchQueryChanged(',
    '    private void beginSearch(',
    '    private void restoreSearchRequest(',
    '    private void setSearchInputOpen(',
    '    private void cancelSearchWork(',
    '    private void acceptSearchResults(',
])
# Check lifecycle/dialog integration, in addition to executing the actual controller below.
assert 'searchWork.pause(search.active);' in method('    void pause(')
assert 'searchWork.resume(search.query);' in source
assert 'searchWork.flush();' in method('    private void searchInputDismissed(')
assert 'searchWork.changed(query);' in method('    private void searchQueryChanged(')
for signature in ('    void reset(', '    private void selectTab(', '    private void closeSearch('):
    assert 'cancelSearchWork();' in method(signature)
    assert 'search.close();' in method(signature)

handler = r'''package android.os;
import java.util.*;
public class Handler {
 private long now;
 private final Map<Runnable,Long> queue=new LinkedHashMap<>();
 public void postDelayed(Runnable r,long delay){queue.put(r,now+delay);}
 public void removeCallbacks(Runnable r){queue.remove(r);}
 public int size(){return queue.size();}
 public void advance(long ms){now+=ms;while(true){Runnable next=null;
  for(Map.Entry<Runnable,Long> e:queue.entrySet())if(e.getValue()<=now){next=e.getKey();break;}
  if(next==null)return;queue.remove(next);next.run();}}
}
'''
recs = r'''package io.tvos;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.*;
class Recs {
 static class Pick {final int id;Pick(int id){this.id=id;}}
 static class Call {
  final String query;final Consumer<List<Pick>> callback;final UiRequest request;
  Call(String q,Consumer<List<Pick>> c,UiRequest r){query=q;callback=c;request=r;}
  void deliver(int id){callback.accept(new ArrayList<>(Arrays.asList(new Pick(id))));}
 }
 final List<Call> calls=new ArrayList<>();
 final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
 UiRequest search(String q,Consumer<List<Pick>> callback){
  UiRequest r=new UiRequest(executor);calls.add(new Call(q,callback,r));return r;
 }
 List<Pick> window;int windows,loads;
 void setPosterWindow(List<Pick> w){window=w;windows++;}
 void poster(Pick p){loads++;}
}
'''
harness = r'''package io.tvos;
import java.util.*;
public class HomeWorkHarness {static class RandomPoster {void clear(){}}final RandomPoster randomPoster=new RandomPoster();
 boolean running=true,heroDirty,statusDirty;
 int cardPage,cardIndex,rouletteCancels,updates,zone,topIndex;static final int Z_TOP=4,Z_CARDS=5;int availableTopButton(int id){return id;}static class Glass {int hides;void hide(){hides++;}}final Glass searchGlass=new Glass();
 final HomeSearchState<Recs.Pick> search=new HomeSearchState<>();
 final android.os.Handler handler=new android.os.Handler();
 final Recs recs=new Recs();
 final SearchController searchWork=new SearchController(handler,recs,()->running&&search.active,this::acceptSearchResults);
 void cancelRouletteWork(){rouletteCancels++;}void wake(){}
 void layoutLight(){}void updateCards(){updates++;}void retarget(boolean b){}
 void change(String query){if(query.equals(search.query))return;
  searchQueryChanged(query);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
''' + body + r'''
 public static void main(String[] args){
  HomeWorkHarness h=new HomeWorkHarness();h.search.query="movie";
  h.beginSearch();h.restoreSearchRequest();
  check(h.recs.calls.size()==1,"entry requests query");
  Recs.Call first=h.recs.calls.get(0);h.beginSearch();h.restoreSearchRequest();
  check(h.recs.calls.size()==1 && first.request.active(),"opening input cannot duplicate active request");
  first.deliver(1);h.cardPage=2;h.cardIndex=3;
  List<Recs.Pick> result=h.search.results;h.beginSearch();h.restoreSearchRequest();
  check(h.recs.calls.size()==1 && h.search.results==result && h.cardPage==2 && h.cardIndex==3,
        "reopen keeps results and page without network");
  h.change("new query");h.beginSearch();h.restoreSearchRequest();h.handler.advance(399);
  check(h.recs.calls.size()==1,"reopening does not bypass debounce");
  h.handler.advance(1);check(h.recs.calls.size()==2,"query starts at 400 ms");
  Recs.Call stale=h.recs.calls.get(1);h.change("latest");
  check(!stale.request.active(),"query change cancels previous request");
  stale.deliver(2);check(h.search.results.isEmpty(),"late callback cannot replace new query");
  h.handler.advance(200);h.change("last text");h.handler.advance(399);
  check(h.recs.calls.size()==2 && h.handler.size()==1,"typing replaces pending work");
  h.searchInputDismissed();check(h.searchGlass.hides==1&&h.zone==Z_TOP&&h.topIndex==5,"dismiss resets backing and leaves empty search at action button");check(h.handler.size()==0 && h.recs.calls.size()==3,"Done submits pending query once");
  check(h.recs.calls.get(2).query.equals("last text"),"Done submits latest text");
  h.handler.advance(1000);h.restoreSearchRequest();check(h.recs.calls.size()==3,"no duplicate after Done");
  Recs.Call paused=h.recs.calls.get(2);h.running=false;h.searchWork.pause(h.search.active);
  check(!paused.request.active(),"pause cancels request");paused.deliver(3);
  check(h.search.results.isEmpty(),"paused callback ignored");
  h.running=true;h.searchWork.resume(h.search.query);h.searchWork.resume(h.search.query);
  check(h.recs.calls.size()==4,"resume restarts interrupted request once");
  paused.deliver(4);check(h.search.results.isEmpty(),"old callback ignored after resume");
  h.recs.calls.get(3).deliver(5);check(h.search.results.get(0).id==5 && h.cardPage==0 && h.cardIndex==0,
    "current result updates view and resets page");
  h.running=false;h.searchWork.pause(h.search.active);h.running=true;h.searchWork.resume(h.search.query);
  check(h.recs.calls.size()==4,"completed result is not fetched again on resume");
  h.change("pending at pause");h.running=false;h.searchWork.pause(h.search.active);
  h.handler.advance(1000);check(h.recs.calls.size()==4 && h.handler.size()==0,"pause removes debounce");
  h.running=true;h.searchWork.resume(h.search.query);check(h.recs.calls.size()==5,"resume restores pending query");
  Recs.Call leaving=h.recs.calls.get(4);h.cancelSearchWork();h.search.active=false;leaving.deliver(6);
  check(h.search.results.isEmpty() && !leaving.request.active(),"leaving search cancels and rejects late delivery");
  h.running=false;h.searchWork.pause(false);h.running=true;h.searchWork.resume(h.search.query);
  check(h.recs.calls.size()==5,"closed search does not restart");
  h.beginSearch();h.change("ab");h.searchWork.flush();h.restoreSearchRequest();h.handler.advance(1000);
  check(h.recs.calls.size()==5,"short query is not sent");
  h.change("😀😀");h.handler.advance(400);check(h.recs.calls.size()==5,"minimum counts Unicode code points");
  h.change("😀😀😀");h.handler.advance(400);check(h.recs.calls.size()==6,"three code points accepted");
  h.cancelSearchWork();h.search.active=false;h.cardPage=2;h.cardIndex=3;h.search.heading.snap(1);h.beginSearch();
  check(h.cardPage==0 && h.cardIndex==0 && h.search.heading.value==0,"fresh entry resets navigation and heading");
  String retained=h.search.query;List<Recs.Pick> cleared=h.search.results;h.search.close();check(!h.search.active&&h.search.query.equals(retained)&&cleared.isEmpty(),"closing clears existing results but retains query");
  h.beginSearch();List<Recs.Pick> fresh=h.search.results;check(fresh!=cleared,"fresh session owns a new result list");h.search.accept(new ArrayList<>(Arrays.asList(new Recs.Pick(99))));h.cardPage=2;h.cardIndex=3;h.searchInputDismissed();check(h.zone==Z_CARDS&&h.cardPage==2&&h.cardIndex==3,"dismiss with results preserves page and focuses posters");
  h.beginSearch();check(h.search.results.get(0).id==99&&h.cardPage==2&&h.cardIndex==3,"reopen keeps accepted results and navigation");h.searchWork.cancel();h.recs.executor.shutdownNow();
  System.out.println("PASS: search debounce, cancellation, stale callbacks, Done, lifecycle, result/page reuse, Unicode");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-home-work-') as directory:
    root = Path(directory)
    (root / 'android/os').mkdir(parents=True)
    (root / 'android/os/Handler.java').write_text(handler)
    (root / 'Recs.java').write_text(recs)
    (root / 'HomeWorkHarness.java').write_text(harness)
    for name in ('Spring', 'HomeSearchState', 'SearchController', 'UiRequest'):
        (root / (name + '.java')).write_text(Path('app/src/io/tvos/' + name + '.java').read_text())
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory,
                    *map(str, root.rglob('*.java'))], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.HomeWorkHarness'], check=True)
