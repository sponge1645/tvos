"""Production category persistence, Dock-style editor and ordered home navigation on JVM."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import subprocess
import tempfile

def method(source, signature):
    start=source.index(signature);brace=source.index('{',start);depth=1;end=brace+1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]

prefs=Path('app/src/io/tvos/Prefs.java').read_text()
settings=Path('app/src/io/tvos/SettingsActivity.java').read_text()
home=Path('app/src/io/tvos/HomeView.java').read_text()
prefs_body=''.join(method(prefs,s) for s in [
 '    private static void put(String key, String value)', '    private static void put(String key, boolean value)',
  '    static boolean randomMovie(', '    static void setRandomMovie(',
 '    private static void readCategories(', '    static int[] categoryOrder(', '    static boolean categoryEnabled(',
 '    static int firstCategory(', '    static int lastCategory(', '    static int categoryCount(', '    static int categoryPosition(',
 '    static void setCategoryEnabled(', '    static void setCategoryOrder('])
editor_body=''.join(method(settings,s) for s in [
 '        private String categoryLabel(', '        private void rebuildCategoryRows(', '        private void openCategoryEditor(',
 '        private void toggleCategory(', '        private void moveCategory('])
nav_body=''.join(method(home,s) for s in [
 '    private boolean topControlVisible(', '    private int nextTopControl(', '    private static boolean tabEnabled(',
 '    private void syncVisibleTabs(', '    private int availableTopButton('])
harness='''import java.util.*;
class Ui {static boolean ru;}
class Prefs {
 static int[] topButtonOrder(){return new int[]{0,1,2,3,5,4};}
 static boolean topButtonEnabled(int id){throw new AssertionError("wrong preference domain");}static int topButtonCount(){throw new AssertionError("wrong preference domain");}static void setTopButtonEnabled(int id,boolean on){throw new AssertionError("wrong preference domain");}static void setTopButtonOrder(List<Integer> ids){throw new AssertionError("wrong preference domain");}
 static int version;private static int categoryVersion=-1,categoryMask;private static int[] categoryOrderCache;
 static class Store {
  final Map<String,Object> values=new HashMap<>();
  String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
  Store edit(){return this;}Store putString(String k,String v){values.put(k,v);return this;}
  Store putBoolean(String k,boolean v){values.put(k,v);return this;}void apply(){}
 }
 static final Store store=new Store();static Store sp(){return store;}
 static void fresh(){store.values.clear();version++;categoryOrderCache=null;}
 static void reload(){categoryOrderCache=null;}
''' +prefs_body+'''
}
class CategoryEditor {
 final SettingsOrderEditor categoryEditor=new SettingsOrderEditor(SettingsOrderEditor.Kind.CATEGORIES);int focus,screen;static final int CATEGORY_EDIT=20;
 String tr(String ru,String en){return en;}
''' +editor_body+'''
 void open(){openCategoryEditor();}void toggle(){toggleCategory();}void move(int d){moveCategory(d);}
}
class HomeNavigation {
 boolean searchMode;int actions=63,tab,topIndex,zone,cancels;
 static final int Z_TOP=1,Z_CARDS=2,Z_SHELF=3;
 void selectTab(int id){tab=id;}void cancelRouletteWork(){cancels++;}
 void sync(){syncVisibleTabs();}
 boolean picksOn(){return searchMode||Prefs.categoryCount()>0;}
 boolean topButtonEnabled(int index){return (actions&(1<<index))!=0;}
''' +nav_body+'''
 int next(int current,int direction){return nextTopControl(current,direction);}
}
public class CategoriesHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void invalid(List<Integer> ids){boolean rejected=false;try{Prefs.setCategoryOrder(ids);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid category order rejected");}
 static List<Integer> snapshot(SettingsOrderEditor editor){List<Integer> out=new ArrayList<>();for(int i=0;i<editor.size();i++)out.add(editor.get(i));return out;}
 static int permutations;
 static void permutation(List<Integer> order,int at){
  if(at<order.size()){for(int i=at;i<order.size();i++){Collections.swap(order,at,i);permutation(order,at+1);Collections.swap(order,at,i);}return;}
  permutations++;
  Prefs.setCategoryOrder(order);
  for(int mask=0;mask<32;mask++){
   for(int id:new int[]{0,1,3,2,4})Prefs.setCategoryEnabled(id,(mask&(1<<id))!=0);
   List<Integer> visible=new ArrayList<>();for(int id:order)if((mask&(1<<id))!=0)visible.add(id);
   check(Prefs.categoryCount()==visible.size(),"enabled count independent of category IDs");
   SettingsOrderEditor model=new SettingsOrderEditor(SettingsOrderEditor.Kind.CATEGORIES);model.rebuild(-1);
   List<Integer> grouped=new ArrayList<>();for(int id:order)if(Prefs.categoryEnabled(id))grouped.add(id);for(int id:order)if(!Prefs.categoryEnabled(id))grouped.add(id);
   check(snapshot(model).equals(grouped),"editor grouping for every order/mask");
   check(Prefs.firstCategory()==(visible.isEmpty()?-1:visible.get(0)),"first enabled follows configured order");
   check(Prefs.lastCategory()==(visible.isEmpty()?-1:visible.get(visible.size()-1)),"last enabled follows configured order");
   for(int i=0;i<visible.size();i++)check(Prefs.categoryPosition(visible.get(i))==i+1,"visible position ignores disabled entries");
   for(int actions:new int[]{0,1,8,16,21,31,32,63})for(boolean search:new boolean[]{false,true}){
    HomeNavigation nav=new HomeNavigation();nav.actions=actions;nav.searchMode=search;
    List<Integer> controls=new ArrayList<>();if(!search)controls.addAll(visible);
    for(int id:Prefs.topButtonOrder())if((actions&(1<<id))!=0)controls.add(5+id);
    for(int i=0;i<controls.size();i++){
     int current=controls.get(i);
     check(nav.next(current,-1)==controls.get(Math.max(0,i-1)),"left follows visual order, skips hidden categories/actions");
     check(nav.next(current,1)==controls.get(Math.min(controls.size()-1,i+1)),"right follows visual order, including Clock and roulette");
    }
   }
  }
 }
 public static void main(String[] args){
  Prefs.fresh();check(Arrays.equals(Prefs.categoryOrder(),new int[]{0,1,3,2,4}),"default order preserved");
  check(Prefs.categoryEnabled(2)&&Prefs.categoryEnabled(4)&&Prefs.categoryCount()==5,"legacy default visibility preserved");
  Prefs.store.putBoolean("category_2",false);Prefs.version++;check(Prefs.categoryEnabled(2),"retired category toggle never hides new Watching");
  Prefs.setRandomMovie(false);check(Prefs.categoryEnabled(2)&&!Prefs.categoryEnabled(4),"watching independent of roulette");
  Prefs.store.putString("category_order","4,2,4,99,x,1,-2");Prefs.version++;
  check(Arrays.equals(Prefs.categoryOrder(),new int[]{4,2,1,0,3}),"malformed order repaired without duplicates or losing categories");
  invalid(Arrays.asList(0,1,2,3));invalid(Arrays.asList(0,1,3,3));invalid(Arrays.asList(0,1));invalid(Arrays.asList(0,1,2,3,3));invalid(Arrays.asList(0,1,2,3,9));
  Prefs.fresh();CategoryEditor e=new CategoryEditor();e.open();
  check(snapshot(e.categoryEditor).equals(Arrays.asList(0,1,3,2,4)),"enabled categories first, disabled below like Dock");
  e.focus=1;e.move(-1);check(e.categoryEditor.get(0)==1&&e.focus==0,"reorder follows selected category");
  e.move(-1);check(e.focus==0,"left edge does not wrap");
  e.toggle();check(!Prefs.categoryEnabled(1)&&e.categoryEditor.get(4)==1&&e.focus==4,"disable moves category below enabled entries and keeps focus");
  e.move(-1);check(e.focus==4,"disabled categories cannot displace enabled entries");
  e.toggle();check(Prefs.categoryEnabled(1)&&e.categoryEditor.get(4)==1&&e.focus==4,"enable appends to active categories like Dock");
  List<Integer> saved=snapshot(e.categoryEditor);Prefs.reload();e.open();
  check(snapshot(e.categoryEditor).equals(saved),"order survives a fresh preferences snapshot");
  while(Prefs.categoryCount()>0){e.focus=0;e.toggle();}
  check(Prefs.firstCategory()==-1&&Prefs.lastCategory()==-1,"all categories can be disabled");
  e.focus=2;int restored=e.categoryEditor.get(2);e.toggle();
  check(Prefs.categoryCount()==1&&Prefs.firstCategory()==restored&&e.focus==0,"one category can be restored after all-off");
  Prefs.setCategoryOrder(Arrays.asList(3,1,4,0,2));
  for(int id:new int[]{0,1,3,2,4})Prefs.setCategoryEnabled(id,id==1||id==3);
  HomeNavigation nav=new HomeNavigation();nav.tab=0;nav.topIndex=0;nav.zone=HomeNavigation.Z_TOP;nav.sync();
  check(nav.tab==3&&nav.topIndex==3,"disabling active category chooses first visible in configured order");
  for(int id:new int[]{0,1,3,2,4})Prefs.setCategoryEnabled(id,false);
  nav.zone=HomeNavigation.Z_CARDS;nav.sync();
  check(nav.zone==HomeNavigation.Z_SHELF&&nav.cancels>0,"all-off cancels hidden roulette and exits cards focus");
  nav.actions=16;nav.zone=HomeNavigation.Z_TOP;nav.topIndex=0;nav.sync();
  check(nav.topIndex==9,"all-off can still reach enabled clock action");
  nav.actions=0;nav.zone=HomeNavigation.Z_TOP;nav.sync();
  check(nav.zone==HomeNavigation.Z_SHELF,"no visible top controls returns focus to Dock");
  permutation(new ArrayList<>(Arrays.asList(0,1,3,2,4)),0);
  check(permutations==120,"all category permutations covered");
  System.out.println("PASS: category migration, persistence, editor reorder/toggle/focus, all-off, 120 orders x 32 visibility masks, home/search navigation");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-categories-') as directory:
    root=Path(directory);p=root/'CategoriesHarness.java';p.write_text(harness)
    (root/'SettingsOrderEditor.java').write_text(Path('app/src/io/tvos/SettingsOrderEditor.java').read_text().replace('package io.tvos;',''))
    write_strings(root,package=False)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run(['java','-cp',directory,'CategoriesHarness'],check=True)
