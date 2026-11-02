"""Execute the whole grid state and actual View adapters for open/close, refresh and keys."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
view=Path('app/src/io/tvos/HomeView.java').read_text()
harness='''package io.tvos;import java.util.*;
class HomeApps {static class Item {final int id;Item(int id){this.id=id;}}}
public class GridTest {
 final HomeGridState<HomeApps.Item> grid=new HomeGridState<>(5,3);boolean gridDirty;int spokenCount,retargetCount,moves,labelInvalidations;
 class Spoken {void focusMoved(){spokenCount++;}}final Spoken spoken=new Spoken();
 class Surfaces {void invalidateLabels(){labelInvalidations++;}}final Surfaces appSurfaces=new Surfaces();
 class Click {void move(){moves++;}}final Click click=new Click();void retarget(boolean snap){retargetCount++;}
 static List<HomeApps.Item> items(int n){List<HomeApps.Item> r=new ArrayList<>();for(int i=0;i<n;i++)r.add(new HomeApps.Item(i));return r;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b){check(Math.abs(a-b)<.0001,a+" != "+b);}
'''+''.join(block(view,s) for s in ['    private void setOthers(', '    private void openSheet(', '    private void closeSheet(', '    private void retargetGrid(', '    private void moveGrid('])+'''
 public static void main(String[] args){
 for(int n=0;n<=67;n++)for(int at=0;at<Math.max(1,n);at++)for(int[] direction:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){
  GridTest t=new GridTest();List<HomeApps.Item> source=items(n);t.setOthers(source);check(t.grid.items==source&&t.labelInvalidations==1&&t.spokenCount==1&&t.gridDirty,"refresh bindings");t.openSheet();check(t.grid.open&&t.grid.index==0&&t.grid.sheet.target==1&&t.retargetCount==1,"open resets selection and starts sheet");
  t.grid.index=at;t.retargetGrid(true);int row=at/5;near(t.grid.scroll.value,Math.max(0,row-2));
  int expected=at,dx=direction[0],dy=direction[1];if(n>0){if(dx<0&&at%5>0)expected--;if(dx>0&&at%5<4&&at+1<n)expected++;if(dy<0&&at>=5)expected-=5;if(dy>0&&row<(n-1)/5)expected=Math.min(n-1,at+5);}
  int spoken=t.spokenCount;t.gridDirty=false;t.moveGrid(dx,dy);check(t.grid.index==expected&&t.moves==(expected==at?0:1)&&t.gridDirty==(expected!=at)&&t.spokenCount==spoken+(expected==at?0:1),"arrow movement and exact effects");
  if(n>0){int visible=Math.min(3,(n+4)/5);check(t.grid.scroll.target<=t.grid.index/5&&t.grid.index/5<t.grid.scroll.target+visible,"selection stays inside scroll window");}
  t.retargetGrid(true);for(int i=0;i<n;i++)near(t.grid.focus[i].value,i==expected?1:0);
  t.closeSheet();check(!t.grid.open&&t.grid.sheet.target==0&&t.retargetCount==2,"close targets animation and View focus");t.retargetGrid(false);for(Spring s:t.grid.focus)near(s.target,0);
 }
 GridTest t=new GridTest();t.setOthers(items(40));t.openSheet();t.grid.index=39;t.retargetGrid(true);near(t.grid.scroll.target,5);t.setOthers(items(7));check(t.grid.index==6&&t.grid.focus.length==7,"shrinking list clamps selection");near(t.grid.scroll.target,0);t.setOthers(items(0));check(t.grid.index==0&&t.grid.focus.length==0,"empty grid safe");near(t.grid.scroll.value,0);
 t.setOthers(items(20));t.openSheet();t.grid.index=19;t.retargetGrid(false);near(t.grid.scroll.value,0);near(t.grid.scroll.target,1);check(t.grid.scroll.step(.016f)&&t.grid.scroll.value>0,"scroll animates without snap");t.openSheet();near(t.grid.scroll.value,0);check(t.grid.index==0,"reopen resets scroll and selection");
 System.out.println("PASS: full grid state and real View adapters, all counts/indices/arrows, scroll/focus, shrink/empty/reopen and exact feedback");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name in ['Spring','HomeGridState']:(root/(name+'.java')).write_text(Path('app/src/io/tvos/'+name+'.java').read_text())
 (root/'GridTest.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.GridTest'],check=True)
