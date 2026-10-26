"""Execute the production settings background cache, size/theme lifetime and OOM fallback."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/graphics/Shader.java':'package android.graphics;public class Shader {public enum TileMode{CLAMP}public final int first,last;Shader(int f,int l){first=f;last=l;}}',
'android/graphics/LinearGradient.java':'package android.graphics;public class LinearGradient extends Shader {public LinearGradient(float a,float b,float c,float d,int e,int f,TileMode m){super(e,f);}}',
'android/graphics/RadialGradient.java':'package android.graphics;public class RadialGradient extends Shader {public RadialGradient(float a,float b,float r,int[] colors,float[] stops,TileMode m){super(colors[0],colors[colors.length-1]);if(r<=0)throw new AssertionError("radius");}}',
'android/graphics/Paint.java':'package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2,DITHER_FLAG=4;public final int flags;public Shader shader;public Paint(int f){flags=f;}public void setShader(Shader s){shader=s;}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect {public int right,bottom;public void set(int l,int t,int r,int b){right=r;bottom=b;}}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {public enum Config{ARGB_8888}public static int allocations,recycles;public static boolean fail;public final int w,h;public boolean recycled;Bitmap(int w,int h){this.w=w;this.h=h;}public static Bitmap createBitmap(int w,int h,Config c){if(fail)throw new OutOfMemoryError("test");allocations++;return new Bitmap(w,h);}public void recycle(){if(recycled)throw new AssertionError("double recycle");recycled=true;recycles++;}}''',
'android/graphics/Canvas.java':'''package android.graphics;import java.util.*;public class Canvas {public static Canvas raster;public final List<Integer> colors=new ArrayList<>();public final Bitmap target;public Bitmap shown;public Rect destination;public Paint paint;public float sx,sy;public int rects;public Canvas(){target=null;}public Canvas(Bitmap b){target=b;raster=this;}public void scale(float x,float y){sx=x;sy=y;}public void drawRect(float l,float t,float r,float b,Paint p){rects++;colors.add(p.shader.first);}public void drawBitmap(Bitmap b,Rect src,Rect dst,Paint p){if(b.recycled)throw new AssertionError("drawing recycled bitmap");shown=b;destination=dst;paint=p;}}''',
'io/tvos/SettingsBackgroundHarness.java':'''package io.tvos;import android.graphics.*;
public class SettingsBackgroundHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  SettingsBackground bg=new SettingsBackground();Canvas out=new Canvas();bg.draw(out,1920,1080,true);Bitmap first=out.shown;Rect destination=out.destination;
  check(first.w==960&&first.h==540&&Canvas.raster.rects==4,"half-resolution raster contains four light layers");
  check(Canvas.raster.colors.get(0)==0xFFB6EAF9&&Canvas.raster.colors.get(3)==0xA6B2E9FF,"light palette retained");
  check(out.paint.flags==6&&destination.right==1920&&destination.bottom==1080,"filtered/dithered full-size composition");
  for(int i=0;i<100;i++)bg.draw(out,1920,1080,true);check(Bitmap.allocations==1&&out.shown==first&&out.destination==destination,"unchanged frames reuse bitmap and destination rect");
  bg.draw(out,1920,1080,false);check(first.recycled&&Bitmap.allocations==2&&Canvas.raster.rects==4,"theme invalidates previous bitmap");
  check(Canvas.raster.colors.get(0)==0xFF16323F&&Canvas.raster.colors.get(3)==0x0010161B,"dark palette retained");
  Bitmap dark=out.shown;bg.draw(out,1281,721,false);check(dark.recycled&&out.shown.w==641&&out.shown.h==361,"odd resize uses ceiling half-size");
  check(Math.abs(Canvas.raster.sx-641f/1281)<.00001f,"raster scale maps original gradient coordinates");
  Bitmap resized=out.shown;bg.clear();bg.clear();check(resized.recycled&&Bitmap.recycles==3,"pause and repeated clear release cache once");
  bg.draw(out,1281,721,false);check(Bitmap.allocations==4,"resume recreates released cache");
  Bitmap.fail=true;Canvas fallback=new Canvas();bg.draw(fallback,800,600,true);check(fallback.shown==null&&fallback.rects==4&&fallback.colors.get(0)==0xFFB6EAF9,"OOM draws original gradient directly");
  Bitmap.fail=false;bg.draw(out,800,600,true);check(out.shown.w==400&&Bitmap.allocations==5,"later draw recovers cache after OOM");
  int allocations=Bitmap.allocations;Canvas zero=new Canvas();bg.draw(zero,0,600,true);check(Bitmap.allocations==allocations&&zero.rects==0,"zero-size view does no graphics work");
  bg.clear();System.out.println("PASS: settings background palette, cache reuse, theme/size changes, release, OOM fallback and recovery");
 }
}'''
}
sources['io/tvos/SettingsBackground.java']=Path('app/src/io/tvos/SettingsBackground.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-settings-background-') as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SettingsBackgroundHarness'],check=True)
