"""Run actual home layout methods with shared production geometry across Dock counts and screen sizes."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write as write_strings
from home_renderers_fixture import files
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
methods='\n'.join(block(home,sig) for sig in ['    private void layoutShelf(', '    private void layoutDrawer(', '    private void layoutLight(', '    private void layoutTopActions('])
harness='''package io.tvos;
import android.graphics.*;import java.util.*;
public class HomeLayoutHarness {
 static final float MARGIN=HomeLayout.EDGE,CONTENT_R=HomeLayout.RIGHT,SHELF_PAD=HomeLayout.DOCK_PAD,TILE_GAP=HomeLayout.DOCK_GAP;
 static final float ROW_TOP=540,ROW_H=126,CARDS_H=HomeLayout.WEATHER_HEIGHT,L_TABBAR_TOP=HomeLayout.TAB_TOP,L_TABBAR_H=HomeLayout.TAB_HEIGHT,L_BUTTON=HomeLayout.BUTTON,L_BUTTON_GAP=20,L_BUTTON_CY=HomeLayout.HEADER_CY;
 final List<HomeApps.Item> shelf=new ArrayList<>(),drawerItems=new ArrayList<>();
 final RectF platterRect=new RectF(),weatherCard=new RectF();RectF[] tileRects=new RectF[0],drawerRects=new RectF[0],topRects=new RectF[11],cardRects=new RectF[8];
 float drawerExtraHeight;float k=1,contentTop,contentBottom;int w=1920,h=1080,drawerInputs;boolean motionDirty,drawerDirty,heroDirty,statusDirty,largePostersWithoutWidgets,compact;
 Paint lTab=Ui.text(Ui.medium,30,0),lTabOn=Ui.text(Ui.semibold,30,0),lCardMeta=Ui.text(Ui.medium,25,0);String clockText="12:34";
 static class Cache {void clear(){}}final Cache appSurfaces=new Cache();static class Header {void clearFrame(){}}final Header header=new Header();
 static class Prefs {static int[] order={0,1,2,3,5,4};static int mask=63;static int[] topButtonOrder(){return order;}static int[] categoryOrder(){return new int[]{0,1,3,4};}}
 boolean noTopWidgets(){return compact;}boolean tabEnabled(int id){return true;}boolean topButtonEnabled(int id){return (Prefs.mask&(1<<id))!=0;}
 String[] tabs(){return new String[]{"New","Movies","","Shows","Dice"};}
 HomeLayoutHarness(){for(int i=0;i<11;i++)topRects[i]=new RectF();for(int i=0;i<8;i++)cardRects[i]=new RectF();}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void near(float a,float b,String why){check(Math.abs(a-b)<.03f,why+" "+a+" != "+b);}
 void count(int n){shelf.clear();tileRects=new RectF[n];for(int i=0;i<n;i++){shelf.add(new HomeApps.Item("app"+i));tileRects[i]=new RectF();}}
'''+methods+'''
 public static void main(String[] args){
  HomeLayoutHarness t=new HomeLayoutHarness();
  for(int width:new int[]{1280,1920,3840}){t.w=width;t.h=width*9/16;t.k=width/1920f;t.lTab.setTextSize(30*t.k);t.lTabOn.setTextSize(30*t.k);t.lCardMeta.setTextSize(25*t.k);
   for(int n=1;n<=8;n++){
    t.count(n);t.layoutShelf();float outer=(t.platterRect.right-t.platterRect.left)/t.k;
    near(t.platterRect.left,t.w-t.platterRect.right,"Dock remains centered");near(t.h-t.platterRect.bottom,60*t.k,"bottom safe edge");
    check(t.platterRect.left>=40*t.k-.03,"Dock does not exceed safe width");
    for(RectF r:t.tileRects){near(r.width()/r.height(),5f/3,"5:3 at every count and resolution");near(r.top-t.platterRect.top,40*t.k,"top inner padding");near(t.platterRect.bottom-r.bottom,40*t.k,"bottom inner padding");}
    if(n<6)check(outer<1840,"one to five apps keep a narrower Dock");else {near(outer,1840,"six to eight reach max width");near(t.platterRect.left,40*t.k,"max width side margin");}
    if(n==6){near(t.tileRects[0].width()/t.k,253.33333f,"six-app tile width");near(t.tileRects[0].height()/t.k,152,"six-app tile height");near(t.platterRect.top/t.k,788,"taller Dock anchored at bottom");}
    for(boolean compact:new boolean[]{false,true}){t.compact=compact;t.layoutLight();float end=t.cardRects[0].bottom+HomeLayout.POSTER_META_BASELINE*t.k+t.lCardMeta.descent()+1.5f*t.k;
     near(t.cardRects[0].top-t.contentTop,t.contentBottom-end,"centering for every Dock count");check(t.cardRects[0].top>=t.contentTop,"posters stay below upper controls");}
    for(RectF r:t.tileRects){float liftScale=1.08f*1.2f;check(r.centerY()-r.height()*liftScale/2>=HomeLayout.DOCK_LAYER_TOP*t.k,"lifted tile stays inside its layer top");}
   }
   t.count(0);t.layoutShelf();check(Float.isFinite(t.platterRect.left),"empty Dock remains finite");
   t.count(6);t.layoutShelf();t.drawerItems.clear();t.drawerItems.add(new HomeApps.Item("input"));t.drawerRects=new RectF[]{new RectF()};t.drawerInputs=1;t.layoutDrawer();
   near(t.weatherCard.left,40*t.k,"weather left");near(t.w-t.weatherCard.right,40*t.k,"weather right");near(t.h-t.weatherCard.bottom,40*t.k,"weather bottom");near(t.weatherCard.height(),300*t.k,"weather content height retained");
   for(int count=1;count<=10;count++)for(int inputs:new int[]{0,count/2,count}){
    t.drawerItems.clear();t.drawerRects=new RectF[count];t.drawerInputs=inputs;
    for(int i=0;i<count;i++){t.drawerItems.add(new HomeApps.Item("item"));t.drawerRects[i]=new RectF();}
    t.layoutDrawer();near(t.drawerRects[0].left,40*t.k,"row left aligned");near(t.drawerRects[count-1].right,1880*t.k,"row fills common right edge");
    for(int i=0;i<count;i++){RectF r=t.drawerRects[i];near(r.bottom,666*t.k,"row bottom and weather gap preserved");check(r.height()>=126*t.k-.03&&r.height()<=168*t.k+.03,"bounded proportional button height");
     if(i>0)near(r.left-t.drawerRects[i-1].right,(i==inputs?56:18)*t.k,"button and group gaps preserved");}
    near(t.drawerExtraHeight,t.drawerRects[0].height()-126*t.k,"Dock lift matches row height increase");
   }
   for(boolean compact:new boolean[]{false,true}){t.compact=compact;t.layoutLight();near(t.topRects[10].top,50*t.k,"Network top");
    float right=0;for(int i=5;i<11;i++)right=Math.max(right,t.topRects[i].right);near(t.w-right,40*t.k,"rightmost action edge");near(t.topRects[0].left,40*t.k,"category capsule left");near(t.cardRects[0].left,40*t.k,"first poster left");near(t.w-t.cardRects[4].right,40*t.k,"last poster right");
    float metadataBottom=t.cardRects[0].bottom+HomeLayout.POSTER_META_BASELINE*t.k+t.lCardMeta.descent()+1.5f*t.k;
    near(t.cardRects[0].top-t.contentTop,t.platterRect.top-metadataBottom,"poster + caption group has equal upper/lower gaps");
    check(metadataBottom<=t.platterRect.top,"poster metadata has space above Dock");check(metadataBottom<=HomeLayout.CARDS_LAYER_HEIGHT*t.k,"metadata not clipped by poster layer");
    for(int i=5;i<11;i++)near(t.topRects[i].height(),61.2f*t.k,"all action surfaces shrink 10 percent");
    for(int i=1;i<Prefs.order.length;i++){RectF prev=t.topRects[5+Prefs.order[i-1]],next=t.topRects[5+Prefs.order[i]];near(next.left-prev.right,20*t.k,"inter-button gaps unchanged");}
   }
   for(int mask=0;mask<64;mask++){Prefs.mask=mask;t.layoutLight();for(int i=5;i<11;i++)if(t.topButtonEnabled(i-5))near(t.topRects[i].top,50*t.k,"all action combinations keep top edge");}
   Prefs.mask=63;
  }
  float grid=HomeLayout.APP_COLUMNS*HomeLayout.APP_WIDTH+(HomeLayout.APP_COLUMNS-1)*HomeLayout.APP_GAP;near((1920-grid)/2,40,"All Apps shares horizontal edge");
  System.out.println("PASS: actual Dock/drawer/poster/action layout, counts 0–8, 5:3, full width at six, 40px sides / 50px top / 60px Dock bottom, equal poster group gaps, 10% smaller buttons, unchanged button gaps, compact header, 64 masks and 720p/1080p/4K");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-home-layout-') as tmp:
 root=Path(tmp);write_strings(root);sources=files();sources['io/tvos/HomeLayoutHarness.java']=harness
 for name,value in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.HomeLayoutHarness'],check=True)
