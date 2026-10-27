"""Production native gradient/cache behavior and agreement with the approved SVG recipes."""
from pathlib import Path
import subprocess,tempfile,xml.etree.ElementTree as ET
# Build independent expected coordinates/colors/alpha from the accepted vector files.
checks=[]
for theme in ['light','dark']:
 root=ET.parse('docs/design/settings-'+theme+'.svg').getroot();ns={'s':'http://www.w3.org/2000/svg'}
 defs=list(root.find('s:defs',ns));base=list(defs[0]);glows=root.findall('s:ellipse',ns)
 color=lambda value:'0xFF'+value[1:].upper()
 code='check(((LinearGradient)Canvas.raster.shaders.get(0)).first=='+color(base[0].get('stop-color'))+'&&((LinearGradient)Canvas.raster.shaders.get(0)).last=='+color(base[-1].get('stop-color'))+',"approved '+theme+' base colors");'
 for i,(gradient,ellipse) in enumerate(zip(defs[1:],glows)):
  stops=list(gradient)
  alphas=','.join(str(round(float(stop.get('stop-opacity'))*255)) for stop in stops)
  offsets=','.join(stop.get('offset')+'f' for stop in stops)
  rgb='0x'+stops[0].get('stop-color')[1:].upper()
  code+='verifyGlow('+str(i)+','+rgb+',new int[]{'+alphas+'},new float[]{'+offsets+'},new float[]{'+','.join(ellipse.get(key)+'f' for key in ['cx','cy','rx','ry'])+'});'
 checks.append(code)
assert not Path('app/assets/settings/light.webp').exists() and not Path('app/assets/settings/dark.webp').exists()
sources={
'android/util/Log.java':'package android.util;public class Log {public static int warnings;public static int w(String tag,String text,Throwable error){warnings++;return 0;}}',
'android/graphics/Shader.java':'package android.graphics;public class Shader {public enum TileMode{CLAMP}}',
'android/graphics/LinearGradient.java':'package android.graphics;public class LinearGradient extends Shader {public final int first,last;public LinearGradient(float a,float b,float c,float d,int first,int last,TileMode m){this.first=first;this.last=last;}}',
'android/graphics/RadialGradient.java':'package android.graphics;public class RadialGradient extends Shader {public static boolean fail;public final int[] colors;public final float[] stops;public RadialGradient(float a,float b,float r,int[] colors,float[] stops,TileMode m){if(fail)throw new OutOfMemoryError("test");if(r<=0)throw new AssertionError("radius");this.colors=colors;this.stops=stops;}}',
'android/graphics/Paint.java':'package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2,DITHER_FLAG=4;public final int flags;public Shader shader;public Paint(int f){flags=f;}public void setShader(Shader s){shader=s;}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect {public int right,bottom;public void set(int l,int t,int r,int b){right=r;bottom=b;}}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {public enum Config{ARGB_8888}public static int allocations;public static boolean fail;public final int w,h;public boolean recycled;Bitmap(int w,int h){this.w=w;this.h=h;}public static Bitmap createBitmap(int w,int h,Config c){if(fail)throw new OutOfMemoryError("test");allocations++;return new Bitmap(w,h);}public void recycle(){recycled=true;}}''',
'android/graphics/Canvas.java':'''package android.graphics;import java.util.*;public class Canvas {public static Canvas raster;public final List<Shader> shaders=new ArrayList<>();public final List<float[]> translations=new ArrayList<>(),scales=new ArrayList<>();public final Bitmap target;public Bitmap shown;public Rect destination;public Paint paint;public int depth,color;public Canvas(){target=null;}public Canvas(Bitmap b){target=b;raster=this;}public int save(){return ++depth;}public void restoreToCount(int n){depth=n-1;}public void translate(float x,float y){translations.add(new float[]{x,y});}public void scale(float x,float y){scales.add(new float[]{x,y});}public void drawRect(float l,float t,float r,float b,Paint p){shaders.add(p.shader);}public void drawColor(int c){color=c;}public void drawBitmap(Bitmap b,Rect src,Rect dst,Paint p){if(b.recycled)throw new AssertionError("recycled");shown=b;destination=dst;paint=p;}}''',
'io/tvos/SettingsBackgroundHarness.java':'''package io.tvos;import android.graphics.*;
public class SettingsBackgroundHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.02,why+" "+a+" != "+b);}
 static void verifyGlow(int i,int rgb,int[] alphas,float[] stops,float[] shape){RadialGradient g=(RadialGradient)Canvas.raster.shaders.get(i+1);check(g.colors.length==25,"25 opacity stops");for(int j=0;j<25;j++){check((g.colors[j]&0xFFFFFF)==rgb,"approved glow color");check(Math.abs((g.colors[j]>>>24)-alphas[j])<=1,"approved SVG opacity profile");near(g.stops[j],stops[j],"approved SVG offsets");}float[] t=Canvas.raster.translations.get(i),s=Canvas.raster.scales.get(i);near(t[0],shape[0]/2,"glow center x");near(t[1],shape[1]/2,"glow center y");near(s[0],shape[2]/2,"ellipse radius x");near(s[1],shape[3]/2,"ellipse radius y");}
 public static void main(String[] args){
  SettingsBackground bg=new SettingsBackground();Canvas out=new Canvas();bg.draw(out,1920,1080,true);Bitmap first=out.shown;Rect destination=out.destination;
  check(first.w==960&&first.h==540&&Canvas.raster.shaders.size()==3&&Canvas.raster.depth==0,"one linear + two elliptical gradients, half-size cache, balanced canvas");
  '''+checks[0]+'''
  for(int i=0;i<100;i++)bg.draw(out,1920,1080,true);check(Bitmap.allocations==1&&out.shown==first&&destination==out.destination,"steady frames reuse cache/rect");
  check(out.paint.flags==6&&out.destination.right==1920&&out.destination.bottom==1080,"filtered/dithered full-screen image");
  bg.draw(out,1920,1080,false);check(out.shown!=first&&!first.recycled,"theme replaces reference without recycling in-flight frame");
  '''+checks[1]+'''
  bg.draw(out,3840,2160,false);check(out.shown.w==960&&out.shown.h==540,"4K memory capped");bg.draw(out,1281,721,false);check(out.shown.w==641&&out.shown.h==361,"odd viewport rounds up");
  Bitmap old=out.shown;bg.clear();bg.clear();int count=Bitmap.allocations;bg.draw(out,1281,721,false);check(Bitmap.allocations==count+1&&out.shown!=old&&!old.recycled,"pause drops only reference and resume rebuilds once");
  Bitmap.fail=true;Canvas fallback=new Canvas();bg.draw(fallback,800,600,true);check(fallback.shown==null&&fallback.color==0xFFB9F6FB,"light OOM fallback");count=Bitmap.allocations;int warnings=android.util.Log.warnings;for(int i=0;i<100;i++)bg.draw(fallback,800,600,true);check(count==Bitmap.allocations&&warnings==android.util.Log.warnings,"no per-frame retry/log loop");
  Bitmap.fail=false;bg.clear();bg.draw(out,800,600,true);check(out.shown.w==400&&out.shown.h==300,"clear permits recovery");RadialGradient.fail=true;bg.clear();fallback=new Canvas();bg.draw(fallback,1920,1080,false);check(fallback.shown==null&&fallback.color==0xFF09283A,"partial raster failure discarded");RadialGradient.fail=false;
  count=Bitmap.allocations;bg.draw(new Canvas(),0,600,true);check(count==Bitmap.allocations,"zero size no work");bg.clear();bg.draw(out,1,1,true);check(out.shown.w==1&&out.shown.h==1,"tiny viewport remains valid");
  System.out.println("PASS: native gradient recipes match approved SVGs, opacity/ellipses/colors, single-cache reuse, 4K cap, resize/lifecycle and bounded OOM recovery");
 }
}'''}
sources['io/tvos/SettingsBackground.java']=Path('app/src/io/tvos/SettingsBackground.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-settings-background-') as tmp:
 root=Path(tmp)
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SettingsBackgroundHarness'],check=True)
