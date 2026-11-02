"""Execute Dock refresh identity, edit guard, lift lifecycle and spring completion."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
view=Path('app/src/io/tvos/HomeView.java').read_text()
harness='''package io.tvos;import java.util.*;
class HomeApps {static class Item {final String key;Item(String key){this.key=key;}}}
class SystemClock {static long uptimeMillis(){return 1234;}}class R {static class string {static int home_dock_edit_hint=1;}}class AppStrings {static String get(int id){return "hint";}}
public class DockStateTest {
 final HomeDockState<HomeApps.Item> dock=new HomeDockState<>((x,y)->x.key.equals(y.key));int wakes,invalidations,retargets,layouts,platterInvalidations,opens,hints;boolean tilesDirty;
 class Editor {boolean editing;boolean editing(){return editing;}}final Editor dockEditor=new Editor();
 class Surfaces {void invalidateDock(){invalidations++;}}final Surfaces appSurfaces=new Surfaces();class Platter {void invalidate(){platterInvalidations++;}}final Platter platter=new Platter();
 class Click {void open(){opens++;}}final Click click=new Click();class Notices {void hint(String s){hints++;}}final Notices notices=new Notices();
 void wake(){wakes++;}void retarget(boolean snap){if(!snap)throw new AssertionError();retargets++;}void layoutShelf(){layouts++;}
 static List<HomeApps.Item> items(String...keys){List<HomeApps.Item> r=new ArrayList<>();for(String key:keys)r.add(new HomeApps.Item(key));return r;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+block(view,'    private void setShelf(')+block(view,'    private void beginDockEdit(')+'''
 public static void main(String[] args){DockStateTest t=new DockStateTest();t.setShelf(items("a","b","c"));check(t.layouts==1&&t.retargets==1&&t.tilesDirty&&t.platterInvalidations==1,"rebuild effects");t.dock.index=2;Spring[] focus=t.dock.focus,slide=t.dock.slide;Object rects=t.dock.rects;focus[1].snap(.4f);slide[2].value=10;
 List<HomeApps.Item> fresh=items("a","b","c");t.setShelf(fresh);check(t.dock.items==fresh&&t.dock.focus==focus&&t.dock.slide==slide&&t.dock.rects==rects&&t.dock.index==2&&focus[1].value==.4f&&slide[2].value==10&&t.layouts==1,"same keys replace data preserving focus/slide/rect identities");
 t.dockEditor.editing=true;t.tilesDirty=false;t.setShelf(items("x"));check(t.dock.items==fresh&&t.dock.index==2&&!t.tilesDirty&&t.platterInvalidations==2&&t.wakes==3&&t.invalidations==3,"async refresh cannot overwrite drag; original wake/cache invalidation order");t.dockEditor.editing=false;
 t.beginDockEdit();check(t.dock.liftedIndex==2&&t.dock.wiggleAt==1234&&t.dock.lift.target==1&&t.opens==1&&t.hints==1&&t.tilesDirty,"begin visual and feedback bindings");
 check(t.dock.step(.016f,true),"editing continues ticking");for(int i=0;i<500;i++)t.dock.step(.016f,true);check(t.dock.lift.value==1&&t.dock.liftedIndex==2,"held lift remains visible");t.dock.finish(null);boolean moving=true;for(int i=0;i<500&&moving;i++)moving=t.dock.step(.016f,false);check(!moving&&t.dock.lift.value==0&&t.dock.liftedIndex==-1,"drop settles and clears lifted index");
 t.setShelf(items("c","b","a"));check(t.dock.focus!=focus&&t.dock.index==2&&t.layouts==2,"key order changes rebuild springs");t.setShelf(items("x"));check(t.dock.index==0&&t.dock.focus.length==1,"shrink clamps selection");t.setShelf(items());check(t.dock.index==0&&t.dock.focus.length==0&&!t.dock.step(.016f,false),"empty state settles safely");
 for(int n=1;n<=8;n++)for(int from=0;from<n;from++)for(int to=0;to<n;to++){
  HomeDockState<HomeApps.Item> d=new HomeDockState<>((x,y)->x.key.equals(y.key));List<HomeApps.Item> original=new ArrayList<>();for(int i=0;i<n;i++)original.add(new HomeApps.Item("app"+i));d.replace(new ArrayList<>(original));
  for(int i=0;i<n;i++){d.rects[i].left=40+i*270;d.focus[i].value=i*.1f;d.focus[i].velocity=i*.2f;d.slide[i].value=i*3;d.slide[i].velocity=i*2;}
  Spring[] oldFocus=d.focus.clone(),oldSlide=d.slide.clone();d.index=from;d.begin(1234);d.swap(from,to);d.step(.016f,true);d.swap(to,(to+1)%n);
  HomeApps.Item selected=d.items.get(d.index);float[] position=new float[n];for(int i=0;i<n;i++){int b=d.items.indexOf(original.get(i));position[i]=d.rects[b].left+d.slide[b].value;}
  d.finish(original);check(d.items==original&&d.items.get(d.index)==selected,"rollback keeps selected item after multiple moves");
  for(int i=0;i<n;i++){check(d.focus[i]==oldFocus[i]&&d.slide[i]==oldSlide[i],"animation objects follow item across rollback");check(Math.abs(d.rects[i].left+d.slide[i].value-position[i])<.001f,"rollback preserves actual animated positions");check(d.focus[i].velocity==i*.2f,"focus velocity survives moves");}
 }
 System.out.println("PASS: real Dock state/View refresh, key identities, edit guard, lift/wiggle/drop lifecycle and empty/shrink behavior");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name in ['Spring','HomeDockState']:(root/(name+'.java')).write_text(Path('app/src/io/tvos/'+name+'.java').read_text())
 (root/'RectF.java').write_text('package android.graphics;public class RectF {public float left;}');(root/'DockStateTest.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.DockStateTest'],check=True)
