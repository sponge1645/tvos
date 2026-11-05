"""Exercise actual key dispatch + both navigation handlers together, with real Dock long-press control."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
from home_navigation_fixture import bridge, router
home=Path('app/src/io/tvos/HomeView.java').read_text()
methods='\n'.join(block(home,sig) for sig in ['    private void focusSound(', '    private boolean vpnFocused(', '    private void openVpnOnHold(', '    boolean onKey(', '    private void move(', '    private void back(', '    private boolean topControlVisible(', '    private boolean topButtonEnabled(', '    private int nextTopControl(', '    private int firstTopControl(', '    private int availableTopButton(', '    private static boolean tabEnabled(', '    private int posterCornerTarget(', '    private static int nearest(RectF[] rects, int count, float x)'])
handler='''package android.os;import java.util.*;public class Handler {long now;final Map<Runnable,Long> pending=new LinkedHashMap<>();public void postDelayed(Runnable r,long ms){pending.put(r,now+ms);}public void removeCallbacks(Runnable r){pending.remove(r);}public void advance(long ms){now+=ms;for(Runnable r:new ArrayList<>(pending.keySet()))if(pending.get(r)<=now){pending.remove(r);r.run();}}}'''
keys='''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160,KEYCODE_BACK=4;final int action,key,repeat;public KeyEvent(int action,int key,int repeat){this.action=action;this.key=key;this.repeat=repeat;}public int getAction(){return action;}public int getKeyCode(){return key;}public int getRepeatCount(){return repeat;}public boolean isCanceled(){return false;}}'''
methods += bridge(home, 'RemoteNavigationHarness')
harness='''package io.tvos;import io.tvos.home.dock.HomeDockEditor;import io.tvos.home.HomeFocusPolicy;
import java.util.*;import android.os.Handler;import android.view.KeyEvent;
class HomeApps {static final String TV_SETTINGS_FAVORITE="io.tvos.virtual.tvsettings";    static List<String> shelfOrder(List<Item> items) {
        List<String> packages = new ArrayList<>();
        for (Item item : items) {
            if ("tv-settings".equals(item.key)) packages.add(TV_SETTINGS_FAVORITE);
            else if (item.pkg != null) packages.add(item.pkg);
        }
        return packages;
    }static class Item {String pkg,key;Item(int i){pkg=key="app"+i;}}}
class Prefs {static int saves;static final int[] order={0,1,2,3,5,4};static int[] topButtonOrder(){return order;}static int[] categoryOrder(){return new int[]{0,1,3,4};}static int firstCategory(){return 0;}static boolean categoryEnabled(int id){return true;}static boolean topButtonEnabled(int id){return true;}static void setShelf(List<String> order){saves++;}}
public class RemoteNavigationHarness {final java.util.function.IntPredicate visibleTop=this::topControlVisible,enabledTop=this::topButtonEnabled;final java.util.function.IntSupplier topFallback=()->picksOn()?this.tab:0;
 boolean dockContinueKey(KeyEvent e){return false;}
 static class RandomPoster {void clear(){}boolean resetAway(int p,int t){return false;}}final RandomPoster randomPoster=new RandomPoster();
 class Header {RectF weatherBounds(){return new RectF(100);}}final Header header=new Header();boolean weatherAvailable;boolean canOpenWeather(){return weatherAvailable;}static class Status {void invalidate(){}}final Status status=new Status();
 static final int Z_WEATHER=6,Z_SHELF=2,Z_DRAWER=3,Z_TOP=4,Z_CARDS=5,WIDGETS=8;
 int zone=Z_SHELF,widgetIndex,topIndex,cardIndex,cardPage,tab,launches,moves,retargets,work;
 boolean running=true,vpnPress;int vpnOpens;final Runnable vpnHold=this::openVpnOnHold;void openVpnApp(){vpnOpens++;}
 boolean okDown,backDown,enabled=true,heroDirty,statusDirty;
 static class DrawerState {boolean open;int index;List<HomeApps.Item> items=new ArrayList<>();}final DrawerState drawer=new DrawerState();
 List<Object> cards=new ArrayList<>();
 static class RectF {final float x;RectF(float x){this.x=x;}float centerX(){return x;}}
 RectF[] cardRects={new RectF(150),new RectF(550),new RectF(950),new RectF(1350),new RectF(1750)},topRects=new RectF[11];
 static class DockState {int index;List<HomeApps.Item> items=new ArrayList<>();RectF[] rects=new RectF[0];}final DockState dock=new DockState();static class Grid {boolean open;}final Grid grid=new Grid();
 static final String[] TAB_KIND={null,"movie",null,"tv",null};
 static class Recs {int total=3;List<Object> catalog(String kind){return Collections.nCopies(total,new Object());}}final Recs recs=new Recs();
 static class Roulette {boolean spin,done;boolean spinning(){return spin;}boolean finished(){return done;}void cancel(){spin=false;}}final Roulette rouletteWork=new Roulette();
 class Click {void move(){moves++;}void app(){moves++;}void back(){moves++;}}final Click click=new Click();static class Spoken {boolean keyClick(){return true;}}final Spoken spoken=new Spoken();
 static class Accessibility {boolean talkBack(){return false;}}final Accessibility accessibility=new Accessibility();
 static class Genres {void cancelHold(){}void arm(){}void released(){}}final Genres genres=new Genres(),tvGenres=new Genres(),watchingMenu=new Genres();
 List<Object> currentCatalog(){return recs.catalog(TAB_KIND[tab]);}
 final Handler handler=new Handler();final HomeDockEditor<HomeApps.Item> dockEditor=new HomeDockEditor<>(handler,new HomeDockEditor.Host<HomeApps.Item>(){
  public boolean eligible(){return okDown&&!grid.open&&zone==Z_SHELF&&!dock.items.isEmpty();}public List<HomeApps.Item> items(){return dock.items;}public int index(){return dock.index;}public void begin(){}
  public void move(int a,int b){Collections.swap(dock.items,a,b);dock.index=b;}
  public void finish(List<HomeApps.Item> before){if(before!=null){HomeApps.Item selected=dock.items.get(dock.index);dock.items=before;dock.index=dock.items.indexOf(selected);}}public void saved(){}public void canceled(){}
 },items->Prefs.setShelf(HomeApps.shelfOrder(items)));
 RemoteNavigationHarness(int n){for(int i=0;i<11;i++)topRects[i]=new RectF(i*100);for(int i=0;i<n;i++)dock.items.add(new HomeApps.Item(i));dock.rects=new RectF[n];for(int i=0;i<n;i++)dock.rects[i]=new RectF(120+i*240);drawer.items.add(new HomeApps.Item(99));updateCards();}
 boolean picksOn(){return enabled;}void wake(){}void moveGrid(int dx,int dy){}void openDrawer(){drawer.open=true;zone=Z_DRAWER;}void closeDrawer(){drawer.open=false;zone=Z_SHELF;}
 List<Integer> widgets(){return Collections.emptyList();}void retarget(boolean snap){retargets++;}void closeSheet(){grid.open=false;}void layoutLight(){}void selectTab(int id){tab=id;cardPage=cardIndex=0;updateCards();}
 void updateCards(){List<Object> all=recs.catalog(null);int start=Math.min(cardPage*5,all.size());cards=new ArrayList<>(all.subList(start,Math.min(start+5,all.size())));cardIndex=Math.min(cardIndex,Math.max(0,cards.size()-1));}void spinRoulette(){rouletteWork.spin=true;}void activate(){launches++;}
 void event(int key,boolean down,int repeats){check(onKey(new KeyEvent(down?0:1,key,repeats)),"direction/OK/Back handled");}
 void key(int key){event(key,true,0);event(key,false,0);}void right(){key(22);}void left(){key(21);}void up(){key(19);}void down(){key(20);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 void traverse(){zone=Z_SHELF;dock.index=0;for(int i=0;i<dock.items.size()+2;i++){int before=dock.index;right();check(dock.index==Math.min(dock.items.size()-1,before+1),"right moves to next Dock app or stops at edge");}for(int i=0;i<dock.items.size()+2;i++){int before=dock.index;left();check(dock.index==Math.max(0,before-1),"left moves to previous Dock app or stops at edge");}}
'''+methods+'''
 public static void main(String[] args){
  for(int count=1;count<=8;count++){
   RemoteNavigationHarness h=new RemoteNavigationHarness(count);h.traverse();
   for(int tab=0;tab<5;tab++){
    h.tab=tab;h.updateCards();h.zone=Z_TOP;h.topIndex=tab;
    h.down();check(h.zone==Z_CARDS,"category descends into cards");h.down();check(h.zone==Z_SHELF,"cards descend into Dock");h.traverse();
    h.up();check(h.zone==Z_CARDS,"Dock returns to cards");h.up();check(h.zone==Z_TOP,"cards return to header");h.key(4);check(h.zone==Z_SHELF,"Back returns to Dock");h.traverse();
    h.cards.clear();h.up();check(h.zone==Z_TOP&&h.topIndex==tab,"empty category retains current tab");h.down();check(h.zone==Z_SHELF,"empty category returns to Dock");h.traverse();

   }
   h.down();check(h.zone==Z_DRAWER&&h.drawer.open,"Dock down opens drawer");h.up();check(h.zone==Z_SHELF,"drawer up returns to Dock");h.traverse();
   h.dock.index=0;h.event(22,false,0);check(h.dock.index==0,"direction release alone never moves");h.event(22,true,1);check(h.dock.index==Math.min(1,count-1),"held arrow repeat moves normally");
  }
  RemoteNavigationHarness h=new RemoteNavigationHarness(6);h.key(23);check(h.launches==1&&!h.dockEditor.editing(),"short OK launches exactly once");h.event(23,true,0);h.handler.advance(500);h.event(23,false,0);check(h.dockEditor.editing(),"long hold enters editor");String first=h.dock.items.get(0).key;h.right();check(h.dock.index==1&&h.dock.items.get(1).key.equals(first),"edit mode reorders independently");h.key(23);check(!h.dockEditor.editing()&&Prefs.saves==1&&h.launches==1,"save consumes release without launching");h.traverse();
  RemoteNavigationHarness offline=new RemoteNavigationHarness(6);offline.enabled=false;offline.up();check(offline.zone==Z_TOP,"no-catalog home reaches actions");offline.down();check(offline.zone==Z_SHELF,"no-catalog home returns to Dock");offline.traverse();
  RemoteNavigationHarness empty=new RemoteNavigationHarness(0);empty.right();empty.left();check(empty.dock.index==0,"empty Dock arrows remain safe");empty.up();check(empty.zone==Z_TOP,"empty Dock reaches top controls");
  RemoteNavigationHarness vpn=new RemoteNavigationHarness(1);vpn.zone=Z_TOP;vpn.topIndex=7;
  vpn.key(23);check(vpn.launches==1&&vpn.vpnOpens==0,"VPN short release activates once");
  vpn.event(23,true,0);vpn.handler.advance(499);check(vpn.vpnOpens==0,"VPN shares Dock hold threshold");vpn.handler.advance(1);check(vpn.vpnOpens==1&&!vpn.okDown,"VPN long hold works on the home screen and consumes release");vpn.event(23,true,1);vpn.event(23,false,0);check(vpn.launches==1&&vpn.vpnOpens==1,"long press never toggles on release or repeats");
  vpn.event(23,true,0);vpn.right();vpn.handler.advance(500);vpn.event(23,false,0);check(vpn.vpnOpens==1&&vpn.launches==1,"moving off VPN cancels both delayed open and release activation");
  RemoteNavigationHarness spatial=new RemoteNavigationHarness(6);spatial.enabled=false;spatial.weatherAvailable=true;spatial.dock.index=0;spatial.up();check(spatial.zone==Z_WEATHER,"first Dock item reaches weather");spatial.dock.index=5;spatial.zone=Z_WEATHER;spatial.down();check(spatial.zone==Z_SHELF&&spatial.dock.index==0,"weather down returns to first Dock item instead of stale selection");spatial.topRects[10]=new RectF(1100);spatial.topRects[9]=new RectF(1700);spatial.dock.index=5;spatial.up();check(spatial.zone==Z_TOP&&spatial.topIndex==9,"last Dock item reaches rightmost visible control even when another center is nearer");spatial.down();check(spatial.zone==Z_SHELF&&spatial.dock.index==5,"top control down picks nearest Dock item");
  RemoteNavigationHarness pages=new RemoteNavigationHarness(3);pages.recs.total=7;pages.updateCards();pages.zone=Z_CARDS;pages.cardIndex=4;pages.right();
  check(pages.cardPage==1&&pages.cardIndex==0&&pages.cards.size()==2,"Home bridge writes page before content refresh and reads new count");pages.right();pages.right();check(pages.cardIndex==1&&pages.cardPage==1,"Home bridge stops on final page edge");
  pages.left();pages.left();check(pages.cardPage==0&&pages.cardIndex==4&&pages.cards.size()==5,"Home bridge restores previous page selection");
  pages.cardPage=1;pages.zone=Z_TOP;pages.topIndex=1;pages.tab=1;pages.right();check(pages.tab==3&&pages.cardPage==0&&pages.cardIndex==0,"Home bridge keeps category refresh reset instead of overwriting it with stale snapshot");
  RemoteNavigationHarness wx=new RemoteNavigationHarness(2);wx.weatherAvailable=true;wx.zone=Z_TOP;wx.topIndex=1;wx.moveLight(0,-1);check(wx.zone==Z_TOP,"Up stays on the single header row");wx.zone=Z_WEATHER;wx.moveLight(0,1);check(wx.zone==Z_CARDS,"Down from weather reaches posters");wx.zone=Z_WEATHER;wx.moveLight(1,0);check(wx.zone==Z_TOP&&wx.topIndex==0,"Right from weather reaches first category");wx.moveLight(-1,0);check(wx.zone==Z_WEATHER,"Left from first category reaches weather");
  System.out.println("PASS: real key→move→moveLight chain, Dock arrows/counts/edges/repeats, filled and empty categories across tabs, drawer, OK, long-press edit/save and roulette lock");
 }
}''' + router('RemoteNavigationHarness')
with tempfile.TemporaryDirectory(prefix='tvos-home-remote-') as tmp:
 root=Path(tmp)
 (root/'HomeFocusPolicy.java').write_text(Path('app/src/io/tvos/home/HomeFocusPolicy.java').read_text())
 for name,value in {'android/os/Handler.java':handler,'android/view/KeyEvent.java':keys,'io/tvos/home/dock/HomeDockEditor.java':Path('app/src/io/tvos/home/dock/HomeDockEditor.java').read_text(),'io/tvos/Spring.java':Path('app/src/io/tvos/Spring.java').read_text(),'io/tvos/RemoteNavigationHarness.java':harness}.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RemoteNavigationHarness'],check=True)
