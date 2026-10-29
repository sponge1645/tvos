"""Exercise actual key dispatch + both navigation handlers together, with real Dock long-press control."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
methods='\n'.join(block(home,sig) for sig in ['    private boolean vpnFocused(', '    private void openVpnOnHold(', '    boolean onKey(', '    private void move(', '    private boolean moveLight(', '    private void back(', '    private void beginSearch(', '    private void closeSearch(', '    private void cancelRouletteWork(', '    private boolean topControlVisible(', '    private boolean topButtonEnabled(', '    private int nextTopControl(', '    private int availableTopButton(', '    private static boolean tabEnabled(', '    private int posterCornerTarget(', '    private static int nearest(RectF[] rects, int count, float x)'])
handler='''package android.os;import java.util.*;public class Handler {long now;final Map<Runnable,Long> pending=new LinkedHashMap<>();public void postDelayed(Runnable r,long ms){pending.put(r,now+ms);}public void removeCallbacks(Runnable r){pending.remove(r);}public void advance(long ms){now+=ms;for(Runnable r:new ArrayList<>(pending.keySet()))if(pending.get(r)<=now){pending.remove(r);r.run();}}}'''
keys='''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160,KEYCODE_BACK=4;final int action,key,repeat;public KeyEvent(int action,int key,int repeat){this.action=action;this.key=key;this.repeat=repeat;}public int getAction(){return action;}public int getKeyCode(){return key;}public int getRepeatCount(){return repeat;}public boolean isCanceled(){return false;}}'''
harness='''package io.tvos;
import java.util.*;import android.os.Handler;import android.view.KeyEvent;
class HomeApps {static class Item {String pkg,key;Item(int i){pkg=key="app"+i;}}}
class Prefs {static int saves;static final int[] order={0,1,2,3,5,4};static int[] topButtonOrder(){return order;}static int[] categoryOrder(){return new int[]{0,1,3,4};}static int firstCategory(){return 0;}static boolean categoryEnabled(int id){return true;}static boolean topButtonEnabled(int id){return true;}static void setShelf(List<String> order){saves++;}}
public class RemoteNavigationHarness {
 static class RandomPoster {void clear(){}boolean resetAway(int p,int t){return false;}}final RandomPoster randomPoster=new RandomPoster();
 static final int Z_SHELF=2,Z_DRAWER=3,Z_TOP=4,Z_CARDS=5,WIDGETS=8;
 int zone=Z_SHELF,shelfIndex,drawerIndex,widgetIndex,topIndex,cardIndex,cardPage,tab,launches,moves,retargets,work;
 boolean running=true,vpnPress;int vpnOpens;final Runnable vpnHold=this::openVpnOnHold;void openVpnApp(){vpnOpens++;}
 boolean okDown,backDown,sheetOpen,searchMode,drawerOpen,enabled=true,heroDirty,statusDirty;
 List<HomeApps.Item> shelf=new ArrayList<>(),drawerItems=new ArrayList<>();List<Object> cards=new ArrayList<>(),searchResults=new ArrayList<>();
 static class RectF {final float x;RectF(float x){this.x=x;}float centerX(){return x;}}
 RectF[] tileRects=new RectF[0],cardRects={new RectF(150),new RectF(550),new RectF(950),new RectF(1350),new RectF(1750)},topRects=new RectF[11];
 static final String[] TAB_KIND={null,"movie",null,"tv",null};
 static class Recs {List<Object> catalog(String kind){return Arrays.asList(new Object(),new Object(),new Object());}}final Recs recs=new Recs();
 static class Roulette {boolean spin,done;boolean spinning(){return spin;}boolean finished(){return done;}void cancel(){spin=false;}}final Roulette rouletteWork=new Roulette();
 class Click {void move(){moves++;}}final Click click=new Click();static class Spoken {boolean keyClick(){return true;}}final Spoken spoken=new Spoken();
 static class Accessibility {boolean talkBack(){return false;}}final Accessibility accessibility=new Accessibility();
 static class Genres {void cancelHold(){}void arm(){}void released(){}}final Genres genres=new Genres(),tvGenres=new Genres();
 List<Object> currentCatalog(){return searchMode?searchResults:recs.catalog(TAB_KIND[tab]);}
 final Handler handler=new Handler();final HomeDockEditor dockEditor=new HomeDockEditor(handler,new HomeDockEditor.Host(){
  public boolean eligible(){return okDown&&!sheetOpen&&zone==Z_SHELF&&!shelf.isEmpty();}public List<HomeApps.Item> items(){return shelf;}public int index(){return shelfIndex;}public void begin(){}
  public void move(int a,int b){Collections.swap(shelf,a,b);shelfIndex=b;}
  public void finish(List<HomeApps.Item> before){if(before!=null){HomeApps.Item selected=shelf.get(shelfIndex);shelf=before;shelfIndex=shelf.indexOf(selected);}}public void saved(){}public void canceled(){}
 });
 RemoteNavigationHarness(int n){for(int i=0;i<11;i++)topRects[i]=new RectF(i*100);for(int i=0;i<n;i++)shelf.add(new HomeApps.Item(i));tileRects=new RectF[n];for(int i=0;i<n;i++)tileRects[i]=new RectF(120+i*240);drawerItems.add(new HomeApps.Item(99));updateCards();}
 boolean picksOn(){return enabled;}void wake(){}void moveGrid(int dx,int dy){}void openDrawer(){drawerOpen=true;zone=Z_DRAWER;}void closeDrawer(){drawerOpen=false;zone=Z_SHELF;}
 List<Integer> widgets(){return Collections.emptyList();}void retarget(boolean snap){retargets++;}void closeSheet(){sheetOpen=false;}void layoutLight(){}void setSearchInputOpen(boolean open){}void cancelSearchWork(){}void selectTab(int id){tab=id;}
 void updateCards(){cards=new ArrayList<>(searchMode?searchResults:recs.catalog(null));}void spinRoulette(){rouletteWork.spin=true;}void activate(){launches++;}
 void event(int key,boolean down,int repeats){check(onKey(new KeyEvent(down?0:1,key,repeats)),"direction/OK/Back handled");}
 void key(int key){event(key,true,0);event(key,false,0);}void right(){key(22);}void left(){key(21);}void up(){key(19);}void down(){key(20);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 void traverse(){zone=Z_SHELF;shelfIndex=0;for(int i=0;i<shelf.size()+2;i++){int before=shelfIndex;right();check(shelfIndex==Math.min(shelf.size()-1,before+1),"right moves to next Dock app or stops at edge");}for(int i=0;i<shelf.size()+2;i++){int before=shelfIndex;left();check(shelfIndex==Math.max(0,before-1),"left moves to previous Dock app or stops at edge");}}
'''+methods+'''
 public static void main(String[] args){
  for(int count=1;count<=8;count++){
   RemoteNavigationHarness h=new RemoteNavigationHarness(count);h.traverse();
   for(int tab=0;tab<5;tab++){
    h.tab=tab;h.beginSearch();for(int i=0;i<5;i++)h.searchResults.add(new Object());h.updateCards();h.zone=Z_TOP;h.topIndex=5;
    h.down();check(h.zone==Z_CARDS,"search actions descend into results");h.down();check(h.zone==Z_SHELF,"results descend into Dock");h.traverse();
    h.up();check(h.zone==Z_CARDS,"Dock returns to results");h.up();check(h.zone==Z_TOP,"results return to search actions");h.key(4);check(!h.searchMode,"Back exits search through actual closeSearch");h.down();if(h.zone==Z_CARDS)h.down();check(h.zone==Z_SHELF,"return to Dock using real arrows after leaving search");h.traverse();
    h.beginSearch();h.searchResults.clear();h.updateCards();h.traverse();h.up();check(h.zone==Z_TOP,"empty search permits top focus");h.down();check(h.zone==Z_SHELF,"empty search returns to Dock");h.traverse();h.key(4);h.traverse();
   }
   h.down();check(h.zone==Z_DRAWER&&h.drawerOpen,"Dock down opens drawer");h.up();check(h.zone==Z_SHELF,"drawer up returns to Dock");h.traverse();
   h.shelfIndex=0;h.event(22,false,0);check(h.shelfIndex==0,"direction release alone never moves");h.event(22,true,1);check(h.shelfIndex==Math.min(1,count-1),"held arrow repeat moves normally");
  }
  RemoteNavigationHarness h=new RemoteNavigationHarness(6);h.key(23);check(h.launches==1&&!h.dockEditor.editing(),"short OK launches exactly once");h.event(23,true,0);h.handler.advance(500);h.event(23,false,0);check(h.dockEditor.editing(),"long hold enters editor");String first=h.shelf.get(0).key;h.right();check(h.shelfIndex==1&&h.shelf.get(1).key.equals(first),"edit mode reorders independently");h.key(23);check(!h.dockEditor.editing()&&Prefs.saves==1&&h.launches==1,"save consumes release without launching");h.traverse();
  h.rouletteWork.spin=true;int at=h.shelfIndex;h.right();check(h.shelfIndex==at,"running roulette retains existing focus lock");h.key(4);check(!h.rouletteWork.spin,"Back releases roulette lock");h.traverse();
  RemoteNavigationHarness offline=new RemoteNavigationHarness(6);offline.enabled=false;offline.up();check(offline.zone==Z_TOP,"no-catalog home reaches actions");offline.down();check(offline.zone==Z_SHELF,"no-catalog home returns to Dock");offline.traverse();
  RemoteNavigationHarness empty=new RemoteNavigationHarness(0);empty.right();empty.left();check(empty.shelfIndex==0,"empty Dock arrows remain safe");empty.up();check(empty.zone==Z_TOP,"empty Dock reaches top controls");
  RemoteNavigationHarness vpn=new RemoteNavigationHarness(1);vpn.zone=Z_TOP;vpn.topIndex=7;
  vpn.searchMode=true;vpn.key(23);check(vpn.launches==1&&vpn.vpnOpens==0,"VPN short release activates once");
  vpn.event(23,true,0);vpn.handler.advance(499);check(vpn.vpnOpens==0,"VPN shares Dock hold threshold");vpn.handler.advance(1);check(vpn.vpnOpens==1&&!vpn.okDown,"VPN long hold works inside Search and consumes release");vpn.event(23,true,1);vpn.event(23,false,0);check(vpn.launches==1&&vpn.vpnOpens==1,"long press never toggles on release or repeats");
  vpn.event(23,true,0);vpn.right();vpn.handler.advance(500);vpn.event(23,false,0);check(vpn.vpnOpens==1&&vpn.launches==1,"moving off VPN cancels both delayed open and release activation");
  System.out.println("PASS: real key→move→moveLight chain, Dock arrows/counts/edges/repeats, filled and empty search entry/exit across tabs, drawer, OK, long-press edit/save and roulette lock");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-home-remote-') as tmp:
 root=Path(tmp)
 for name,value in {'android/os/Handler.java':handler,'android/view/KeyEvent.java':keys,'io/tvos/HomeDockEditor.java':Path('app/src/io/tvos/HomeDockEditor.java').read_text(),'io/tvos/RemoteNavigationHarness.java':harness}.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RemoteNavigationHarness'],check=True)
