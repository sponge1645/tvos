"""Run the real Dock renderer through spring motion, aliases and cache invalidation."""
from pathlib import Path
import subprocess, tempfile
from dock_graphics_fixture import GRAPHICS

source=Path('app/src/io/tvos/HomeAppsRenderer.java').read_text()
a=source.index('    private void drawLogo(');b=source.index('{',a);depth=1;end=b+1
while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
wrapper=source[a:end].replace('frame.tileRects','tileRects')
harness='''package io.tvos;import android.graphics.*;
public class DockLogoHarness {
 static final float FOCUS_GROW=.2f;private final DockLogoRenderer dockLogos=new DockLogoRenderer();private RectF[] tileRects=new RectF[0];
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void close(float a,float b,String why){check(Math.abs(a-b)<.0003f,why+": "+a+" != "+b);}
 static HomeApps.Item item(String key,int w,int h){HomeApps.Item i=new HomeApps.Item();i.key=i.pkg=key;i.logo=new Bitmap(w,h);i.logoDark=new Bitmap(w+2,h+2);i.logoInk=.3f;i.logoScale=1;return i;}
'''+wrapper+'''
 public static void main(String[] args)throws Exception{
  DockLogoHarness h=new DockLogoHarness();Canvas c=new Canvas();RectF box=new RectF(84.13f,710.27f,308.13f,836.27f);
  for(String key:new String[]{"com.android.vending","com.yandex.devices.appstore","com.seena.app","youtube","tv-settings","watch.kino","watch.kino.mod"})
   for(int[] dim:new int[][]{{71,43},{82,66},{117,39}}){HomeApps.Item i=item(key,dim[0],dim[1]);
    for(int mode=0;mode<=2;mode++){
     float before=0;for(float delta:new float[]{-.00002f,.00002f}){h.drawLogo(c,i,box,(.998f+delta)*(1+FOCUS_GROW),mode);
      close((c.l+c.r)/2,box.centerX(),"center across old threshold");close((c.t+c.b)/2,box.centerY(),"vertical center");
      if(delta<0)before=c.l;else check(Math.abs(c.l-before)<.01f,"no final-frame snap");}
     Spring spring=new Spring(.34f,.74f,0);spring.target=1;
     for(int frame=0;frame<300;frame++){spring.step(1f/60);h.drawLogo(c,i,box,1+FOCUS_GROW*spring.value,mode);
      close((c.l+c.r)/2,box.centerX(),"center through spring");close((c.t+c.b)/2,box.centerY(),"vertical center through spring");
      if(frame==8||frame==23)spring.target=1-spring.target;}
     check((mode==1)==(c.filter!=null),"tint mode preserved");check(c.paint.filter==null,"tint cleared after draw");
    }
   }
  h.tileRects=new RectF[]{new RectF(0,0,224,126)};
  HomeApps.Item base=item("watch.kino",540,252),mod=item("watch.kino.mod",540,252);mod.logo=base.logo;
  Canvas baseCanvas=new Canvas(),modCanvas=new Canvas();h.drawLogo(baseCanvas,base,box,1.2f,0);h.drawLogo(modCanvas,mod,box,1.2f,0);
  close(baseCanvas.l,modCanvas.l,"KinoPub alias left");close(baseCanvas.r,modCanvas.r,"KinoPub alias right");
  close(baseCanvas.t,modCanvas.t,"KinoPub alias top");close(baseCanvas.b,modCanvas.b,"KinoPub alias bottom");
  int scales=Bitmap.scales;for(int frame=0;frame<100;frame++)h.drawLogo(c,base,box,1+frame*.002f,0);
  check(Bitmap.scales==scales,"focus animation reuses prepared bitmap");
  Bitmap source=base.logo;h.dockLogos.clear();h.drawLogo(c,base,box,1.2f,0);check(Bitmap.scales>scales,"clear invalidates cache");
  check(!source.recycled,"never recycle app-owned source");
  scales=Bitmap.scales;base.logoScale=1.05f;h.drawLogo(c,base,box,1.2f,0);check(Bitmap.scales>scales,"scale invalidates cache");
  scales=Bitmap.scales;base.logoInk=.5f;h.drawLogo(c,base,box,1.2f,0);check(Bitmap.scales>scales,"ink invalidates cache");
  scales=Bitmap.scales;h.tileRects[0].bottom=150;h.drawLogo(c,base,box,1.2f,0);check(Bitmap.scales>scales,"height invalidates cache");
  for(int frame=0;frame<40;frame++){base.logo=new Bitmap(540,252);h.drawLogo(c,base,box,1.2f,0);}
  java.lang.reflect.Field field=DockLogoRenderer.class.getDeclaredField("normal");field.setAccessible(true);
  check(((java.util.Map<?,?>)field.get(h.dockLogos)).size()==1,"source replacement does not grow per-app cache");
  h.drawLogo(c,base,box,1.2f,2);check(c.image!=base.logo,"dark variant prepared separately");
  System.out.println("PASS: real Dock renderer, stable motion, aliases, tint, no focus resampling, cache invalidation/bounds, source ownership");
 }
}
'''
files=dict(GRAPHICS)
files['io/tvos/HomeApps.java']='package io.tvos;final class HomeApps {static class Item {String key,pkg;android.graphics.Bitmap logo,logoDark;float logoInk,logoScale;}}'
files['io/tvos/DockLogoHarness.java']=harness
for name in ('Spring','AppBranding','DockLogoRenderer'):files[f'io/tvos/{name}.java']=Path(f'app/src/io/tvos/{name}.java').read_text()
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory)
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.DockLogoHarness'],check=True)
