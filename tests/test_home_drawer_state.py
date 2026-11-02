"""Execute drawer refresh and actual View adapters, preserving grouping and focus rules."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
harness='''package io.tvos;import java.util.*;import android.graphics.RectF;
class HomeApps {static class Item {String key;int kind;boolean connected=true;Item(String k,int type){key=k;kind=type;}}}
public class DrawerTest {
 final HomeDrawerState<HomeApps.Item> drawer=new HomeDrawerState<>((a,b)->a.key.equals(b.key)&&a.connected==b.connected,item->item.kind==1||item.kind==2);
 static class Dock {List<Object> items=new ArrayList<>();RectF[] rects={new RectF(),new RectF()};int index;}final Dock dock=new Dock();
 static final int Z_SHELF=1,Z_DRAWER=2;int zone=Z_SHELF,retargets,layouts;boolean drawerDirty,motionDirty;
 void retarget(boolean snap){if(!snap)throw new AssertionError("refresh snaps");retargets++;}void layoutDrawer(){layouts++;drawerDirty=motionDirty=true;}
 static List<HomeApps.Item> items(int n,int leading){List<HomeApps.Item> r=new ArrayList<>();for(int i=0;i<n;i++)r.add(new HomeApps.Item("item"+i,i<leading?1+i%2:0));return r;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+''.join(block(home,s) for s in ['    private void setDrawerItems(', '    private void openDrawer(', '    private void closeDrawer(', '    private static int nearest(RectF[] rects, float x)', '    private static int nearest(RectF[] rects, int count, float x)'])+'''
 public static void main(String[] args){
 for(int n=0;n<=10;n++)for(int leading=0;leading<=n;leading++){
  DrawerTest t=new DrawerTest();List<HomeApps.Item> entries=items(n,leading);t.setDrawerItems(entries);check(t.drawer.items==entries&&t.drawer.inputs==leading&&t.drawer.focus.length==n&&t.drawer.rects.length==n&&t.retargets==(n==0?0:1)&&t.layouts==1&&t.drawerDirty&&t.motionDirty,"grouping/resize and exact refresh effects");
  Spring[] focus=t.drawer.focus;RectF[] rects=t.drawer.rects;t.drawer.index=Math.max(0,n-1);for(int i=0;i<n;i++){rects[i].left=i*100;focus[i].value=.4f;focus[i].velocity=.7f;}
  List<HomeApps.Item> fresh=items(n,leading);check(!t.drawer.replace(fresh)&&t.drawer.items==fresh&&t.drawer.focus==focus&&t.drawer.rects==rects&&t.drawer.index==Math.max(0,n-1),"identical key/connection refresh retains objects and selection");
  t.openDrawer();if(n==0){check(!t.drawer.open&&t.zone==Z_SHELF,"empty drawer cannot open");continue;}
  check(t.drawer.open&&t.drawer.motion.target==1&&t.zone==Z_DRAWER&&t.drawer.index==0,"empty Dock opens at first input");for(int i=0;i<n;i++)check(focus[i].value==(i==0?1:0)&&focus[i].velocity==0,"opening snaps focus and clears old velocities");
  t.dock.items.add(new Object());t.dock.rects[0].left=0;t.dock.rects[1].left=1000;t.dock.index=1;t.openDrawer();check(t.drawer.index==n-1,"drawer selection follows nearest Dock tile");
  t.closeDrawer();check(!t.drawer.open&&t.drawer.motion.target==0&&t.zone==Z_SHELF&&t.dock.index==(n>=7?1:0),"close returns to nearest Dock tile");for(Spring f:focus)check(f.value==0&&f.target==0&&f.velocity==0,"closing immediately clears drawer highlights");
  fresh.get(0).connected=false;List<HomeApps.Item> changed=items(n,leading);check(t.drawer.replace(changed)&&t.drawer.focus==focus&&t.drawer.rects==rects,"connection changes redraw without rebuilding animation");
  changed.set(0,new HomeApps.Item("replacement",0));List<HomeApps.Item> regrouped=items(n,leading);check(t.drawer.replace(regrouped)&&t.drawer.focus==focus,"key changes at same count retain focus/rectangles");
  t.setDrawerItems(items(0,0));check(t.drawer.index==0&&t.drawer.focus.length==0&&t.drawer.inputs==0,"shrink to empty clamps selection");
 }
 HomeDrawerState<HomeApps.Item> d=new HomeDrawerState<>((a,b)->a.key.equals(b.key)&&a.connected==b.connected,item->item.kind==1||item.kind==2);List<HomeApps.Item> mixed=items(4,1);mixed.get(3).kind=2;d.replace(mixed);check(d.inputs==1,"only leading contiguous inputs form the input group");d.open(0);check(d.motion.step(.016f)&&d.motion.value>0,"opening uses the actual spring");d.close();for(int i=0;i<500;i++)d.motion.step(.016f);check(d.motion.value==0,"closing settles at original position");
 System.out.println("PASS: full drawer model/View adapters, 0–10 entries, mixed input groups, refresh identity, connection changes, selection, open/close and springs");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name in ['Spring','HomeDrawerState']:(root/(name+'.java')).write_text(Path('app/src/io/tvos/'+name+'.java').read_text())
 (root/'RectF.java').write_text('package android.graphics;public class RectF {public float left;public float centerX(){return left+50;}}');(root/'DrawerTest.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.DrawerTest'],check=True)
