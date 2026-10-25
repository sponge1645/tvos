"""Exercise the actual Dock logo renderer across the former pixel-snap threshold and spring settling."""
from pathlib import Path
import subprocess
import tempfile

source = Path('app/src/io/tvos/HomeView.java').read_text()
start = source.index('    private void drawLogo(')
brace = source.index('{', start)
depth, end = 1, brace + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
body = source[start:end]
harness = '''package io.tvos;
public class DockLogoHarness {
 static final float FOCUS_GROW=.2f;
 static class Bitmap {int w,h;Bitmap(int w,int h){this.w=w;this.h=h;}int getWidth(){return w;}int getHeight(){return h;}}
 static class RectF {
  float left,top,right,bottom;
  void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
  float centerX(){return (left+right)/2;}float centerY(){return (top+bottom)/2;}
 }
 static class HomeApps {static class Item {String key,pkg;Bitmap logo,logoDark;}}
 static class Paint {void setColorFilter(Object value){}}
 static class Canvas {
  float l,t,r,b;void drawBitmap(Bitmap image,Object src,RectF dst,Paint p){l=dst.left;t=dst.top;r=dst.right;b=dst.bottom;}
 }
 RectF tmp=new RectF();Paint bitmapPaint=new Paint();Object whiteLogo=new Object();
 Bitmap crispLogo(HomeApps.Item it,boolean dark){return dark?it.logoDark:it.logo;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void close(float a,float b,String why){check(Math.abs(a-b)<.0002f,why+": "+a+" != "+b);}
''' + body + '''
 public static void main(String[] args){
  DockLogoHarness h=new DockLogoHarness();Canvas c=new Canvas();RectF box=new RectF();
  box.set(84.13f,710.27f,308.13f,836.27f);
  for(String key:new String[]{"com.android.vending","com.yandex.devices.appstore","com.seena.app","youtube","tv-settings"}){
   for(int[] dim:new int[][]{{71,43},{82,66},{117,39}}){
    HomeApps.Item item=new HomeApps.Item();item.key=item.pkg=key;item.logo=new Bitmap(dim[0],dim[1]);
    item.logoDark=new Bitmap(dim[0]+2,dim[1]+2);
    for(int mode=0;mode<=2;mode++){
     float before=0;
     for(float delta:new float[]{-.00002f,.00002f}){
      h.drawLogo(c,item,box,(.998f+delta)*(1+FOCUS_GROW),mode);
      close((c.l+c.r)/2,box.centerX(),"logo stays horizontally centered around threshold");
      close((c.t+c.b)/2,box.centerY(),"logo stays vertically centered around threshold");
      if(delta<0)before=c.l;else check(Math.abs(c.l-before)<.01f,"no final-frame pixel snap");
     }
     Spring spring=new Spring(.34f,.74f,0);spring.target=1;
     for(int frame=0;frame<300;frame++){
      spring.step(1f/60);float s=1+FOCUS_GROW*spring.value;
      h.drawLogo(c,item,box,s,mode);
      close((c.l+c.r)/2,box.centerX(),"center stable through overshoot and settling");
      close((c.t+c.b)/2,box.centerY(),"vertical center stable through overshoot and settling");
      if(frame==8 || frame==23)spring.target=1-spring.target;
     }
     spring.target=0;
     for(int frame=0;frame<180;frame++){
      spring.step(1f/60);h.drawLogo(c,item,box,1+FOCUS_GROW*spring.value,mode);
      close((c.l+c.r)/2,box.centerX(),"unfocus keeps center stable");
      close((c.t+c.b)/2,box.centerY(),"unfocus keeps vertical center stable");
     }
    }
   }
  }
  System.out.println("PASS: Dock logo center, snap threshold, odd/even sizes, brand scales, tint variants, spring reversals/settling");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-dock-motion-') as directory:
    root=Path(directory)
    (root/'DockLogoHarness.java').write_text(harness)
    (root/'Spring.java').write_text(Path('app/src/io/tvos/Spring.java').read_text())
    subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,str(root/'DockLogoHarness.java'),str(root/'Spring.java')],check=True)
    subprocess.run(['java','-cp',directory,'io.tvos.DockLogoHarness'],check=True)
