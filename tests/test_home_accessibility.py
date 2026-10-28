"""Run the complete virtual-node source with the real HomeView snapshot/navigation methods."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write as write_strings
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
source=Path('app/src/io/tvos/HomeAccessibility.java').read_text()
files={
 'android/content/Context.java':'''package android.content;public class Context {public static final String ACCESSIBILITY_SERVICE="access";public android.view.accessibility.AccessibilityManager manager=new android.view.accessibility.AccessibilityManager();public Object getSystemService(String name){return manager;}}''',
 'android/accessibilityservice/AccessibilityServiceInfo.java':'package android.accessibilityservice;public class AccessibilityServiceInfo {public static final int FEEDBACK_SPOKEN=1;}',
 'android/view/accessibility/AccessibilityManager.java':'''package android.view.accessibility;import java.util.*;public class AccessibilityManager {public boolean enabled;public List<Object> services=new ArrayList<>();public boolean isEnabled(){return enabled;}public List<Object> getEnabledAccessibilityServiceList(int feedback){return services;}}''',
 'android/graphics/Rect.java':'package android.graphics;public class Rect {public int left,top,right,bottom;public int width(){return right-left;}public int height(){return bottom-top;}}',
 'android/graphics/RectF.java':'''package android.graphics;public class RectF {public float left,top,right,bottom;public RectF(){}public RectF(float l,float t,float r,float b){set(l,t,r,b);}public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}public void set(RectF r){set(r.left,r.top,r.right,r.bottom);}public void offset(float x,float y){left+=x;right+=x;top+=y;bottom+=y;}public void round(Rect out){out.left=Math.round(left);out.top=Math.round(top);out.right=Math.round(right);out.bottom=Math.round(bottom);}}''',
 'io/tvos/Spoken.java':'''package io.tvos;import java.util.List;import android.graphics.Rect;class Spoken {interface Source {void items(List<Integer> out);String text(int id);boolean bounds(int id,Rect out);int focused();void focus(int id);void click(int id);default boolean clickable(int id){return true;}}}''',
 'io/tvos/HomeApps.java':'package io.tvos;class HomeApps {static class Item {String label;Item(String label){this.label=label;}}}',
 'io/tvos/Recs.java':'package io.tvos;class Recs {static class Pick {String title,meta,app;Pick(String t,String m,String a){title=t;meta=m;app=a;}}static String serviceName(Pick p){return p.app;}}',
 'io/tvos/NetworkStatus.java':'package io.tvos;class NetworkStatus {static final int WIFI=1,ETHERNET=2;int value;int state(){return value;}}',
 'io/tvos/Prefs.java':'''package io.tvos;class Prefs {static String city="";static String city(){return city;}static int[] categories={3,0,4},buttons={5,4,3};static int[] categoryOrder(){return categories;}static int[] topButtonOrder(){return buttons;}static int categoryPosition(int id){for(int i=0;i<categories.length;i++)if(categories[i]==id)return i+1;return 0;}static int categoryCount(){return categories.length;}}''',
 'io/tvos/HomeData.java':'''package io.tvos;import java.util.*;class HomeData {Weather weather=new Weather();static class Weather {double temp=10,max=20,min=5;int code;boolean isDay=true;long sunrise=1,sunset=2;List<Long> hours=Arrays.asList(9L);List<Double> hourTemp=Arrays.asList(11.0);}static String degrees(double n){return (int)n+"°";}static String hour(long n){return "09:00";}static String condition(int c,boolean d){return "Clear";}}''',
 'io/tvos/Ui.java':'package io.tvos;class Ui {static String time(long n){return n==1?"06:00":"18:00";}}',
 'io/tvos/RouletteRenderer.java':'package io.tvos;import android.graphics.RectF;class RouletteRenderer {'+block(Path('app/src/io/tvos/RouletteRenderer.java').read_text(),'    static void buttonBounds(').replace('(MARGIN + CONTENT_R)','(80 + 1840)')+'}',
 'io/tvos/HomeAccessibility.java':source,
 'io/tvos/HomeLayout.java':Path('app/src/io/tvos/HomeLayout.java').read_text(),
 'io/tvos/AccessibilityHarness.java':'''package io.tvos;
import java.util.*;import android.graphics.*;import android.content.Context;
import static io.tvos.HomeAccessibility.*;
public class AccessibilityHarness implements HomeAccessibility.Host {
 boolean sheetOpen,searchMode,drawerOpen,picks=true,weather=true,gridDirty;int tab,zone=Z_SHELF,gridIndex,topIndex,cardIndex,shelfIndex,drawerIndex,widgetIndex;
 List<HomeApps.Item> others=new ArrayList<>(Arrays.asList(new HomeApps.Item("YouTube"),new HomeApps.Item("Settings"))),shelf=new ArrayList<>(Arrays.asList(new HomeApps.Item("Lampa"))),drawerItems=new ArrayList<>(Arrays.asList(new HomeApps.Item("HDMI"))),lightButtons=new ArrayList<>();
 List<Recs.Pick> cards=new ArrayList<>(Arrays.asList(new Recs.Pick("Title","2024, 120 min","Seena")));
 RectF[] topRects=new RectF[11],cardRects={new RectF(80,262,300,704)},tileRects={new RectF(80,814,304,940)},drawerRects={new RectF(80,540,304,666)};RectF weatherCard=new RectF(80,708,1840,1008);
 float k=1,h=1080,contentTop=226,contentBottom=788;static class Layer {float y;float getTranslationY(){return y;}}final Layer heroLayer=new Layer(),status=new Layer(),tiles=new Layer();
 static class Spring {float target;}final Spring scroll=new Spring();static class Work {boolean active;boolean spinning(){return active;}}final Work rouletteWork=new Work();
 final NetworkStatus networkStatus=new NetworkStatus();final HomeData data=new HomeData();final Context context=new Context();
 final HomeAccessibility source=new HomeAccessibility(context,data,this);final boolean[] visible=new boolean[11];
 int moves,wakes,activations,retargets,gridRetargets,selected=-1;static class Click {int count;void move(){count++;}}final Click click=new Click();
 AccessibilityHarness(){for(int i=0;i<11;i++){topRects[i]=new RectF(i*100,46,i*100+68,114);lightButtons.add(new HomeApps.Item("Action"+i));visible[i]=true;}}
 public void read(HomeAccessibility.State s){readAccessibilityState(s);}public void focus(int group,int i){moves++;focusAccessibleItem(group,i);}public void wake(){wakes++;}public void activate(){activations++;}
 boolean picksOn(){return picks;}boolean weatherBottom(){return weather;}boolean topControlVisible(int i){return visible[i]&&(i>=5||!searchMode&&picks);}
 String[] tabs(){return new String[]{"New","Movies","","Shows","Dice"};}
 void retarget(boolean snap){retargets++;}void retargetGrid(boolean snap){gridRetargets++;}void openDrawer(){drawerOpen=true;}void closeDrawer(){drawerOpen=false;}void selectTab(int i){selected=tab=i;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static int node(int group,int index){return HomeAccessibility.id(group,index);}
 List<Integer> ids(){List<Integer> out=new ArrayList<>();source.items(out);return out;}
'''+block(home,'    private void readAccessibilityState(')+block(home,'    private void focusAccessibleItem(')+'''
 public static void main(String[] args){
  AppLanguage.select("en");AccessibilityHarness h=new AccessibilityHarness();
  check(h.ids().equals(Arrays.asList(4004,4001,4005,4011,4010,4009,5001,2001,3001)),"stable IDs and configured reading order");
  h.visible[3]=false;check(!h.ids().contains(4004),"disabled category removed");h.visible[3]=true;
  check(h.source.text(2001).equals("Lampa")&&h.source.text(5001).equals("Title, 2024, 120 min. App: Seena"),"app and recommendation text");
  h.networkStatus.value=1;check(h.source.text(4011).startsWith("Wi-Fi"),"Wi-Fi label");h.networkStatus.value=2;check(h.source.text(4011).startsWith("Ethernet"),"live Ethernet label");
  AppLanguage.select("ru");check(h.source.text(4011).contains("интернет"),"live resource language");AppLanguage.select("en");
  check(h.source.text(4001).equals("New, tab 2 of 3"),"tab position uses configured order");
  Rect r=new Rect();h.heroLayer.y=-560;check(h.source.bounds(4001,r)&&r.top==0&&r.bottom==24,"offscreen top edge remains reachable");
  check(h.source.bounds(3001,r)&&r.top==1056&&r.bottom==1080,"closed drawer at bottom edge");
  h.source.focus(3001);check(h.drawerOpen&&h.zone==Z_DRAWER&&h.drawerIndex==0&&h.click.count==1,"focus opens drawer and moves remote");
  check(h.ids().contains(8001)&&!h.source.clickable(8001),"weather is readable without click action");
  h.source.focus(8001);check(h.zone==WIDGETS&&h.source.focused()==8001,"weather focus");
  check(h.source.text(8001).contains("10°, Clear")&&h.source.text(8001).contains("09:00 11°"),"weather spoken data");
  Prefs.city="Tokyo";check(h.source.text(8001).startsWith("Tokyo."),"live city");h.data.weather.temp=Double.NaN;check(h.source.text(8001).contains("No weather data"),"missing weather");
  h.source.focus(2001);check(!h.drawerOpen&&h.zone==Z_SHELF,"focus closes drawer");
  h.source.focus(4004);check(h.topIndex==3&&h.selected==3&&h.zone==Z_TOP,"category changes through host");
  h.sheetOpen=true;check(h.ids().equals(Arrays.asList(9001,9002)),"sheet isolates virtual tree");check(h.source.bounds(9001,r)&&r.left==40&&r.right==376&&r.top==151,"grid accessibility follows shared 40px layout");check(h.source.text(9002).equals("Settings, 2 of 2"),"grid label and count");
  h.scroll.target=2;check(h.source.bounds(9001,r)&&r.top==0&&r.bottom==24,"scrolled grid edge");
  h.source.focus(9002);check(h.gridIndex==1&&h.gridDirty&&h.gridRetargets==1&&h.source.focused()==9002,"grid focus/scroll host");
  int moves=h.moves;h.source.focus(2001);check(h.moves==moves,"sheet blocks underlying focus");h.source.focus(9003);check(h.moves==moves,"invalid grid index ignored");
  h.sheetOpen=false;h.tab=4;h.heroLayer.y=0;check(h.source.bounds(5001,r)&&r.left==665&&r.top==444&&r.right==1255&&r.bottom==570,"shared roulette button bounds");
  h.rouletteWork.active=true;h.source.focus(3001);check(h.moves==moves,"spinning locks TalkBack focus");h.rouletteWork.active=false;h.source.focus(5001);check(h.zone==Z_CARDS&&h.source.text(5001).equals("Random Movie"),"roulette node");
  h.source.click(5001);check(h.activations==1,"click delegates existing activation");
  h.visible[10]=false;check(!h.source.bounds(4011,r),"disabled top control has no bounds");h.source.focus(4011);check(h.zone==Z_CARDS,"disabled focus ignored");
  for(int id:new int[]{-1,0,999999}){check(h.source.text(id).isEmpty()&&!h.source.bounds(id,r),"invalid IDs safely ignored");h.source.focus(id);}
  check(!h.source.talkBack(),"disabled service");h.context.manager.enabled=true;check(!h.source.talkBack(),"nonspoken accessibility alone is insufficient");h.context.manager.services.add(new Object());check(h.source.talkBack(),"spoken feedback service detection");
  h.sheetOpen=false;h.searchMode=true;check(!h.ids().contains(4001)&&h.ids().contains(5001),"search hides categories but keeps result node");
  h.tab=0;h.cards.clear();h.shelf.clear();h.drawerItems.clear();h.zone=Z_SHELF;check(h.source.focused()==-1&&!h.ids().contains(5001),"empty lists immediately replace prior snapshot");
  h.source.clear();check(h.source.focused()==-1,"snapshot refreshes normally after release");
  System.out.println("PASS: complete accessibility source, live snapshot, reading order/labels/language, bounds/edges, real navigation, sheet/search/roulette guards and empty lists");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='tvos-accessibility-') as tmp:
 root=Path(tmp);write_strings(root)
 for name,value in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.AccessibilityHarness'],check=True)
