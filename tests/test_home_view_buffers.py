"""Exercise actual layer invalidation and search-glass positioning with retained buffers."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/HomeView.java').read_text()
harness='''import java.util.*;public class BuffersHarness {
 static final int VISIBLE=0;boolean running=true,searchGlassVisible;final int[] searchPanelPosition=new int[2],searchHomePosition=new int[2];
 static class View {int width=100,height=50,x=150,y=120,invalidations,visibility;final Set<int[]> buffers=Collections.newSetFromMap(new IdentityHashMap<int[],Boolean>());int getWidth(){return width;}int getHeight(){return height;}void getLocationOnScreen(int[] a){buffers.add(a);a[0]=x;a[1]=y;}void invalidate(){invalidations++;}void setVisibility(int n){visibility=n;}}
 final View revealBackdrop=new View(),sky=new View(),heroLayer=new View(),status=new View(),platter=new View(),tiles=new View(),drawerLayer=new View(),sheetLayer=new View(),searchGlassLayer=new View();
 final View[] redrawLayers={revealBackdrop,sky,heroLayer,status,platter,tiles,drawerLayer};final View home=new View();{home.x=20;home.y=30;}
 void getLocationOnScreen(int[] a){home.getLocationOnScreen(a);}
 static class Search {boolean shown=true;boolean showing(){return shown;}}final Search searchInput=new Search();
 static class Box {float left,top,right,bottom;void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}}final Box searchGlassBounds=new Box();
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
''' + block(source,'    private void invalidateAll(')+block(source,'    private void syncSearchGlass(')+'''
 public static void main(String[] args){BuffersHarness v=new BuffersHarness();v.invalidateAll();for(View layer:v.redrawLayers)check(layer.invalidations==1,"every original layer invalidated");check(v.sheetLayer.invalidations==0&&v.searchGlassLayer.invalidations==0,"unrelated layers unchanged");
 View panel=new View();for(int i=0;i<1000;i++)v.syncSearchGlass(panel);check(panel.buffers.size()==1&&v.home.buffers.size()==1&&panel.buffers.contains(v.searchPanelPosition)&&v.home.buffers.contains(v.searchHomePosition),"same position buffers reused");check(v.searchGlassBounds.left==130&&v.searchGlassBounds.top==90&&v.searchGlassBounds.right==230&&v.searchGlassBounds.bottom==140&&v.searchGlassVisible&&v.searchGlassLayer.invalidations==1000,"glass bounds and refresh preserved");
 panel.x=230;panel.width=200;v.syncSearchGlass(panel);check(v.searchGlassBounds.left==210&&v.searchGlassBounds.right==410,"current panel location and size read each time");int count=v.searchGlassLayer.invalidations;v.running=false;v.syncSearchGlass(panel);v.running=true;v.searchInput.shown=false;v.syncSearchGlass(panel);v.searchInput.shown=true;panel.height=0;v.syncSearchGlass(panel);check(v.searchGlassLayer.invalidations==count,"paused/hidden/unmeasured guards preserved");
 System.out.println("PASS: actual seven-layer invalidation, retained search position arrays, live bounds and lifecycle guards");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);(root/'BuffersHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,str(root/'BuffersHarness.java')],check=True)
 subprocess.run(['java','-cp',tmp,'BuffersHarness'],check=True)
