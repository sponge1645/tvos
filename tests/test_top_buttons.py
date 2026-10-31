"""Production persistence/editor/layout/navigation for every Menu Bar order and visibility mask."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import subprocess,tempfile

def method(source,sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
prefs=Path('app/src/io/tvos/Prefs.java').read_text();settings=Path('app/src/io/tvos/SettingsActivity.java').read_text();home=Path('app/src/io/tvos/HomeView.java').read_text()
prefs_body='\n'.join(method(prefs,s) for s in ['    private static void put(String key, String value)','    private static void put(String key, boolean value)','    private static void readTopButtons(', '    static int[] topButtonOrder(', '    static boolean topButtonEnabled(', '    static int topButtonCount(', '    static int topButtonPosition(', '    static void setTopButtonEnabled(', '    static void setTopButtonOrder('])
editor='\n'.join(method(settings,s) for s in ['        private String menuButtonLabel(', '        private void rebuildMenuButtonRows(', '        private void openMenuButtonEditor(', '        private void toggleMenuButton(', '        private void moveMenuButton('])
nav='\n'.join(method(home,s) for s in ['    private boolean topButtonEnabled(', '    private boolean topControlVisible(', '    private int nextTopControl(', '    private int availableTopButton(', '    private int posterCornerTarget(', '    private void layoutTopActions('])
header='import java.util.*;\n'+Path('app/src/io/tvos/HomeLayout.java').read_text().replace('package io.tvos;', '')+'''\n
class Ui {static boolean ru;}
class Prefs {
 static int version,topButtonVersion=-1,topButtonMask;static int[] topButtonOrderCache;
 static final int[] DEFAULT_TOP_BUTTON_ORDER={0,1,2,3,5,4};
 static class Store {
  Map<String,Object> values=new HashMap<>();String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
  Store edit(){return this;}Store putString(String k,String v){values.put(k,v);return this;}Store putBoolean(String k,boolean v){values.put(k,v);return this;}void apply(){}
 }
 static Store store=new Store();static Store sp(){return store;}static void fresh(){store.values.clear();version++;topButtonOrderCache=null;}
 static int[] categoryOrder(){return new int[]{0,1,3,4};}
 static boolean homeCategoryEnabled(int id){return categoryEnabled(id);}static boolean categoryEnabled(int id){throw new AssertionError("wrong preference domain");}static int homeCategoryCount(){return categoryCount();}static int categoryCount(){throw new AssertionError("wrong preference domain");}static void setCategoryEnabled(int id,boolean on){throw new AssertionError("wrong preference domain");}static void setCategoryOrder(List<Integer> ids){throw new AssertionError("wrong preference domain");}
'''+prefs_body+'''
}
class Editor {
 SettingsOrderEditor buttonEditor=new SettingsOrderEditor(SettingsOrderEditor.Kind.MENU_BUTTONS);int focus,screen;static final int MENU_BUTTONS=13;
 String tr(String ru,String en){return en;}
'''+editor+'''
 void open(){openMenuButtonEditor();}void toggle(){toggleMenuButton();}void move(int d){moveMenuButton(d);}
}
class Navigation {
 boolean searchMode;int tab=1;float k=1;String clockText="12:34";static final float L_BUTTON_GAP=12;
 static class Ui {static Object displayMedium;}
 static class Paint {
  static final int ANTI_ALIAS_FLAG=1;Paint(int f){}void setTypeface(Object t){}void setTextSize(float s){}
  static class FontMetrics {float descent=5,ascent=-25;}FontMetrics getFontMetrics(){return new FontMetrics();}float measureText(String s){return s.length()*15;}
 }
 static class RectF {float left,right,top,bottom;void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}void setEmpty(){left=right=top=bottom=0;}float width(){return right-left;}}
 RectF[] topRects=new RectF[11];Navigation(){for(int i=0;i<11;i++)topRects[i]=new RectF();}
 boolean picksOn(){return !searchMode;}boolean tabEnabled(int id){return id==1;}
'''+nav+'''
 void layout(){layoutTopActions(60,1780,100);}int next(int i,int d){return nextTopControl(i,d);}int corner(boolean right){return posterCornerTarget(right);}
}
public class TopButtonsHarness {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static List<Integer> snapshot(SettingsOrderEditor editor){List<Integer> out=new ArrayList<>();for(int i=0;i<editor.size();i++)out.add(editor.get(i));return out;}
 static int permutations;
 static void permutation(List<Integer> order,int at){
  if(at<6){for(int i=at;i<6;i++){Collections.swap(order,at,i);permutation(order,at+1);Collections.swap(order,at,i);}return;}
  permutations++;Prefs.setTopButtonOrder(order);
  for(int mask=0;mask<64;mask++){
   for(int id=0;id<6;id++)Prefs.setTopButtonEnabled(id,(mask&(1<<id))!=0);
   List<Integer> visible=new ArrayList<>();for(int id:order)if(Prefs.topButtonEnabled(id))visible.add(5+id);
   check(Prefs.topButtonCount()==visible.size(),"visible count");
   SettingsOrderEditor model=new SettingsOrderEditor(SettingsOrderEditor.Kind.MENU_BUTTONS);model.rebuild(-1);
   List<Integer> grouped=new ArrayList<>();for(int id:order)if(Prefs.topButtonEnabled(id))grouped.add(id);for(int id:order)if(!Prefs.topButtonEnabled(id))grouped.add(id);
   check(snapshot(model).equals(grouped),"editor grouping for every order/mask");
   for(int i=0;i<visible.size();i++)check(Prefs.topButtonPosition(visible.get(i)-5)==i+1,"numbered positions");
   Navigation n=new Navigation();n.layout();
   float prior=-10000;for(int id:visible){Navigation.RectF r=n.topRects[id];check(r.left>prior,"saved order has nonoverlapping rectangles, including clock");prior=r.right;check(id==9?r.width()>60:r.width()==60,"clock keeps its own width");}
   for(int id=5;id<11;id++)if(!visible.contains(id))check(n.topRects[id].width()==0,"hidden rectangles cleared");
   if(!visible.isEmpty()){
    check(n.topRects[visible.get(visible.size()-1)].right==1780,"last visible button remains right aligned");
    check(n.corner(false)==visible.get(0)&&n.corner(true)==visible.get(visible.size()-1),"poster corners follow saved order");
   }
   for(boolean search:new boolean[]{false,true}){
    n.searchMode=search;List<Integer> controls=new ArrayList<>();if(!search)controls.add(1);controls.addAll(visible);
    for(int i=0;i<controls.size();i++){
     int id=controls.get(i);check(n.next(id,-1)==controls.get(Math.max(0,i-1)),"left skips hidden actions in saved order");
     check(n.next(id,1)==controls.get(Math.min(controls.size()-1,i+1)),"right follows order through movable clock and network");
    }
   }
  }
 }
 public static void main(String[] args){
  Prefs.fresh();check(Arrays.equals(Prefs.topButtonOrder(),new int[]{0,1,2,3,5,4}),"prior visual order migrated");
  check(!Prefs.topButtonEnabled(2)&&Prefs.topButtonEnabled(5)&&Prefs.topButtonCount()==5,"VPN off and Network on defaults preserved");
  Prefs.store.putBoolean("top_button_3",false);Prefs.version++;check(!Prefs.topButtonEnabled(3),"legacy visibility preserved");
  Prefs.store.putString("top_button_order","5,5,99,x,4");Prefs.version++;check(Arrays.equals(Prefs.topButtonOrder(),new int[]{5,4,0,1,2,3}),"malformed data repaired without duplicates");
  boolean rejected=false;try{Prefs.setTopButtonOrder(Arrays.asList(0,1));}catch(IllegalArgumentException e){rejected=true;}check(rejected,"partial order rejected");
  rejected=false;try{Prefs.setTopButtonOrder(Arrays.asList(0,1,2,3,4,4));}catch(IllegalArgumentException e){rejected=true;}check(rejected,"duplicate order rejected");
  Prefs.fresh();Editor e=new Editor();e.open();check(snapshot(e.buttonEditor).equals(Arrays.asList(0,1,3,5,4,2)),"enabled first, disabled below");
  e.focus=3;e.move(-1);check(e.buttonEditor.get(2)==5&&e.focus==2,"Network can move left and focus follows");
  e.toggle();check(!Prefs.topButtonEnabled(5)&&e.focus==5,"disabled Network moves below list");
  e.move(-1);check(e.focus==5,"disabled button cannot reorder enabled list");
  e.toggle();check(Prefs.topButtonEnabled(5)&&e.focus==4,"enabled Network appends to active list");
  e.focus=6;e.toggle();e.move(-1);check(e.focus==6,"VPN app configuration row is not reorderable/toggleable");
  List<Integer> saved=snapshot(e.buttonEditor);Prefs.topButtonOrderCache=null;e.open();check(snapshot(e.buttonEditor).equals(saved),"order survives snapshot reload");
  permutation(new ArrayList<>(Arrays.asList(0,1,2,3,4,5)),0);check(permutations==720,"all permutations covered");
  System.out.println("PASS: migration, visibility, editor toggle/reorder/focus, 720 orders x 64 masks, clock width/layout, poster targets, normal/search navigation");
 }
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'TopButtonsHarness.java';p.write_text(header)
 (Path(d)/'SettingsOrderEditor.java').write_text(Path('app/src/io/tvos/SettingsOrderEditor.java').read_text().replace('package io.tvos;',''))
 write_strings(Path(d),package=False)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,Path(d).rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'TopButtonsHarness'],check=True)
