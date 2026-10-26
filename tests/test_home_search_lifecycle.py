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
    '    private void beginSearch(',
    '    private void restoreSearchRequest(',
    '    private void setSearchInputOpen(',
    '    private void cancelSearchWork(',
    '    private void acceptSearchResults(',
])
# Check lifecycle/dialog integration, in addition to executing the actual controller below.
assert 'searchWork.pause(searchMode);' in method('    void pause(')
assert 'searchWork.resume(searchQuery);' in source
assert 'searchWork.flush();' in method('    private void searchInputDismissed(')
assert 'searchWork.changed(query);' in method('    private void searchQueryChanged(')
for signature in ('    void reset(', '    private void selectTab(', '    private void closeSearch('):
    assert 'cancelSearchWork();' in method(signature)

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
public class HomeWorkHarness {
 boolean running=true,searchMode,heroDirty,statusDirty;
 String searchQuery="";int cardPage,cardIndex,rouletteCancels,updates;
 List<Recs.Pick> searchResults=new ArrayList<>();
 final android.os.Handler handler=new android.os.Handler();
 final Recs recs=new Recs();final Spring searchHeadingOpacity=new Spring(.42f,1,0);
 final SearchController searchWork=new SearchController(handler,recs,()->running&&searchMode,this::acceptSearchResults);
 void cancelRouletteWork(){rouletteCancels++;}void wake(){}
 void layoutLight(){}void updateCards(){updates++;}void retarget(boolean b){}
 void change(String query){if(query.equals(searchQuery))return;
  searchQuery=query;searchResults=new ArrayList<>();cardPage=cardIndex=0;searchWork.changed(query);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
''' + body + r'''
 public static void main(String[] args){
  HomeWorkHarness h=new HomeWorkHarness();h.searchQuery="movie";
  h.beginSearch();h.restoreSearchRequest();
  check(h.recs.calls.size()==1 && h.rouletteCancels==1,"entry requests query and cancels hidden roulette");
  Recs.Call first=h.recs.calls.get(0);h.beginSearch();h.restoreSearchRequest();
  check(h.recs.calls.size()==1 && first.request.active(),"opening input cannot duplicate active request");
  first.deliver(1);h.cardPage=2;h.cardIndex=3;
  List<Recs.Pick> result=h.searchResults;h.beginSearch();h.restoreSearchRequest();
  check(h.recs.calls.size()==1 && h.searchResults==result && h.cardPage==2 && h.cardIndex==3,
        "reopen keeps results and page without network");
  h.change("new query");h.beginSearch();h.restoreSearchRequest();h.handler.advance(399);
  check(h.recs.calls.size()==1,"reopening does not bypass debounce");
  h.handler.advance(1);check(h.recs.calls.size()==2,"query starts at 400 ms");
  Recs.Call stale=h.recs.calls.get(1);h.change("latest");
  check(!stale.request.active(),"query change cancels previous request");
  stale.deliver(2);check(h.searchResults.isEmpty(),"late callback cannot replace new query");
  h.handler.advance(200);h.change("last text");h.handler.advance(399);
  check(h.recs.calls.size()==2 && h.handler.size()==1,"typing replaces pending work");
  h.searchWork.flush();check(h.handler.size()==0 && h.recs.calls.size()==3,"Done submits pending query once");
  check(h.recs.calls.get(2).query.equals("last text"),"Done submits latest text");
  h.handler.advance(1000);h.restoreSearchRequest();check(h.recs.calls.size()==3,"no duplicate after Done");
  Recs.Call paused=h.recs.calls.get(2);h.running=false;h.searchWork.pause(h.searchMode);
  check(!paused.request.active(),"pause cancels request");paused.deliver(3);
  check(h.searchResults.isEmpty(),"paused callback ignored");
  h.running=true;h.searchWork.resume(h.searchQuery);h.searchWork.resume(h.searchQuery);
  check(h.recs.calls.size()==4,"resume restarts interrupted request once");
  paused.deliver(4);check(h.searchResults.isEmpty(),"old callback ignored after resume");
  h.recs.calls.get(3).deliver(5);check(h.searchResults.get(0).id==5 && h.cardPage==0 && h.cardIndex==0,
    "current result updates view and resets page");
  h.running=false;h.searchWork.pause(h.searchMode);h.running=true;h.searchWork.resume(h.searchQuery);
  check(h.recs.calls.size()==4,"completed result is not fetched again on resume");
  h.change("pending at pause");h.running=false;h.searchWork.pause(h.searchMode);
  h.handler.advance(1000);check(h.recs.calls.size()==4 && h.handler.size()==0,"pause removes debounce");
  h.running=true;h.searchWork.resume(h.searchQuery);check(h.recs.calls.size()==5,"resume restores pending query");
  Recs.Call leaving=h.recs.calls.get(4);h.cancelSearchWork();h.searchMode=false;leaving.deliver(6);
  check(h.searchResults.isEmpty() && !leaving.request.active(),"leaving search cancels and rejects late delivery");
  h.running=false;h.searchWork.pause(false);h.running=true;h.searchWork.resume(h.searchQuery);
  check(h.recs.calls.size()==5,"closed search does not restart");
  h.beginSearch();h.change("ab");h.searchWork.flush();h.restoreSearchRequest();h.handler.advance(1000);
  check(h.recs.calls.size()==5,"short query is not sent");
  h.change("😀😀");h.handler.advance(400);check(h.recs.calls.size()==5,"minimum counts Unicode code points");
  h.change("😀😀😀");h.handler.advance(400);check(h.recs.calls.size()==6,"three code points accepted");
  h.cancelSearchWork();h.searchMode=false;h.cardPage=2;h.cardIndex=3;h.searchHeadingOpacity.snap(1);h.beginSearch();
  check(h.cardPage==0 && h.cardIndex==0 && h.searchHeadingOpacity.value==0,"fresh entry resets navigation and heading");
  h.searchWork.cancel();h.recs.executor.shutdownNow();
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
    for name in ('Spring', 'SearchController', 'UiRequest'):
        (root / (name + '.java')).write_text(Path('app/src/io/tvos/' + name + '.java').read_text())
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory,
                    *map(str, root.rglob('*.java'))], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.HomeWorkHarness'], check=True)
