"""Exercise production directional navigation from Dock and empty search cards."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/HomeView.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
header='''import java.util.*;
public class SearchFocusHarness {
 static class Prefs {static int[] topButtonOrder(){return new int[]{0,1,2,3,5,4};}}
 static final int Z_SHELF=1,Z_CARDS=2,Z_TOP=3;
 int zone=Z_SHELF,tab=1,topIndex,cardIndex,cardPage,shelfIndex,actions=63;
 boolean searchMode=true,enabled=true,rouletteFinished;int retargets,clicks;
 List<Object> shelf=new ArrayList<>(Arrays.asList(new Object())),cards=new ArrayList<>(),searchResults=new ArrayList<>();
 static class RectF {float centerX(){return 10;}}
 RectF[] tileRects={new RectF()},cardRects={new RectF(),new RectF(),new RectF(),new RectF(),new RectF()},topRects=new RectF[11];
 SearchFocusHarness(){Arrays.fill(topRects,new RectF());}
 static class Recs {List<Object> catalog(Object kind){return new ArrayList<>();}}
 Recs recs=new Recs();Object[] TAB_KIND=new Object[5];
 class Click {void move(){clicks++;}}Click click=new Click();
 boolean topControlVisible(int id){return id<5?!searchMode&&enabled:topButtonEnabled(id-5);}
 boolean picksOn(){return enabled;}boolean topButtonEnabled(int i){return (actions&(1<<i))!=0;}boolean clockButtonEnabled(){return topButtonEnabled(4);}
 int posterCornerTarget(boolean right){return right?9:5;}int nextTopControl(int current,int dx){return current+dx;}
 void selectTab(int t){tab=t;}void spinRoulette(){}void updateCards(){}void retarget(boolean snap){retargets++;}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
'''
tests='''
 public static void main(String[] args){
  for(int mask=0;mask<64;mask++)for(int tab=0;tab<5;tab++){
   SearchFocusHarness h=new SearchFocusHarness();h.actions=mask;h.tab=tab;
   h.moveLight(0,-1);
   if(mask==0){check(h.zone==Z_SHELF,"all hidden keeps focus in Dock");continue;}
   check(h.zone==Z_TOP&&h.topIndex>=5,"empty search never focuses an invisible category");
   check(h.topIndex>=5&&h.topIndex<=10&&h.topButtonEnabled(h.topIndex-5),"lands on enabled visible action or network fallback");
   check(h.retargets==1&&h.clicks==1,"normal focus movement feedback");
   h.moveLight(0,1);check(h.zone==Z_SHELF,"down returns to Dock without entering nonexistent result");
   h.moveLight(0,-1);check(h.zone==Z_TOP,"repeat up remains reachable without Back");
   h.zone=Z_CARDS;h.moveLight(0,-1);check(h.zone==Z_TOP&&h.topIndex>=5,"empty result focus can also return up");
  }
  SearchFocusHarness full=new SearchFocusHarness();full.cards.add(new Object());full.moveLight(0,-1);
  check(full.zone==Z_CARDS,"nonempty results retain normal Dock-to-results navigation");
  SearchFocusHarness category=new SearchFocusHarness();category.searchMode=false;category.tab=2;category.moveLight(0,-1);
  check(category.zone==Z_TOP&&category.topIndex==2,"empty ordinary category still focuses its visible tab");
  SearchFocusHarness noCatalog=new SearchFocusHarness();noCatalog.enabled=false;noCatalog.searchMode=false;noCatalog.actions=0;noCatalog.moveLight(0,-1);
  check(noCatalog.zone==Z_SHELF,"no catalog and all actions hidden keeps focus in Dock");
  System.out.println("PASS: empty search focus across all tabs/button masks, repeated up/down, empty cards, populated results, ordinary empty category");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'SearchFocusHarness.java';p.write_text(header+method('    private boolean moveLight(')+method('    private int availableTopButton(')+method('    private static int nearest(RectF[] rects, int count, float x)')+tests)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,str(p)],check=True)
 subprocess.run(['java','-cp',d,'SearchFocusHarness'],check=True)
