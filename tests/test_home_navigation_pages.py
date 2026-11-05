"""Exercise page refreshes and category changes through the complete production router."""
from pathlib import Path
import subprocess
import tempfile

sources = {
 'android/graphics/RectF.java': '''package android.graphics;public class RectF {
 final float x;public RectF(float x){this.x=x;}public float centerX(){return x;}}''',
 'io/tvos/HomeAccessibility.java': '''package io.tvos;class HomeAccessibility {
 static final int Z_SHELF=2,Z_CARDS=5,Z_TOP=4,Z_WEATHER=6;}''',
 'io/tvos/HomeNavigation.java': Path('app/src/io/tvos/HomeNavigation.java').read_text(),
 'io/tvos/NavigationPages.java': '''package io.tvos;import android.graphics.RectF;
public class NavigationPages implements HomeNavigation.Host {
 int total=7,refreshes,resets,lastReset=-1;boolean replaceRandom;
 final HomeNavigation nav=new HomeNavigation(this);final HomeNavigation.State s=nav.state;
 NavigationPages(){s.zone=5;s.picksOn=true;s.tab=1;s.cardCount=5;s.cardIndex=4;s.dockCount=2;
  s.cardRects=new RectF[]{new RectF(100),new RectF(300),new RectF(500),new RectF(700),new RectF(900)};
  s.dockRects=new RectF[]{new RectF(150),new RectF(800)};s.topRects=new RectF[11];
  for(int i=0;i<11;i++)s.topRects[i]=new RectF(i*100);
 }
 public int catalogSize(){return total;}public int firstTopControl(){return 1;}
 public int availableTopButton(int preferred){return 9;}public int posterCornerTarget(boolean right){return right?9:7;}
 public int nextTopControl(int current,int direction){return current+direction;}
 public boolean topControlVisible(int id){return true;}
 public boolean resetRandom(int index,int last){resets++;lastReset=index;return replaceRandom;}
 public void refresh(HomeNavigation.State state){
  refreshes++;state.cardCount=Math.min(5,Math.max(0,total-state.cardPage*5));
  state.cardIndex=Math.min(state.cardIndex,Math.max(0,state.cardCount-1));
 }
 public void selectTab(HomeNavigation.State state,int tab){state.tab=tab;state.cardPage=state.cardIndex=0;refresh(state);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  NavigationPages h=new NavigationPages();
  check(h.nav.move(h.s,1,0)&&h.s.cardPage==1&&h.s.cardIndex==0&&h.s.cardCount==2,"next incomplete page refreshes selection");
  check(h.refreshes==1&&h.lastReset==5&&h.s.moved,"random-tail reset observes refreshed page selection");
  h.nav.move(h.s,1,0);check(h.s.cardIndex==1&&h.refreshes==1,"second item on final page");
  h.nav.move(h.s,1,0);check(!h.s.moved&&h.refreshes==1,"page edge consumes arrow without feedback or refresh");
  h.nav.move(h.s,-1,0);h.nav.move(h.s,-1,0);
  check(h.s.cardPage==0&&h.s.cardIndex==4&&h.s.cardCount==5,"previous page selects its last poster");
  h.replaceRandom=true;h.nav.move(h.s,-1,0);check(h.refreshes==3&&h.s.cardIndex==3,"random replacement refresh preserves selection");
  h.replaceRandom=false;h.total=6;h.s.cardPage=1;h.s.cardCount=1;h.s.cardIndex=0;
  h.nav.move(h.s,-1,0);check(h.s.cardPage==0&&h.s.cardIndex==4&&h.lastReset==4,"previous page reset uses clamped index");
  h.s.zone=4;h.s.topIndex=1;h.s.cardPage=1;h.nav.move(h.s,1,0);
  check(h.s.tab==2&&h.s.topIndex==2&&h.s.cardPage==0&&h.s.cardIndex==0,"category callback feeds reset selection back to router");
  h.s.zone=6;h.s.weatherAvailable=false;h.nav.move(h.s,0,-1);
  check(h.s.zone==4&&h.s.topIndex==1&&h.s.headerDirty,"unavailable weather returns to header");
  h.nav.move(h.s,0,-1);check(!h.s.moved&&!h.s.headerDirty,"reused snapshot clears feedback flags at header boundary");
  h.s.zone=2;check(!h.nav.move(h.s,1,0)&&!h.s.moved,"Dock horizontal movement stays with Dock owner");
  h.s.zone=3;check(!h.nav.move(h.s,0,-1),"drawer stays with drawer owner");
  System.out.println("PASS: real router page boundaries, incomplete pages, refresh/clamp callbacks, random tail, categories and feedback state");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='tvos-nav-pages-') as tmp:
 root=Path(tmp)
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.NavigationPages'],check=True)
