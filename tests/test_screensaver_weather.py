"""Exercise the shared weather badge: glyph-only rasterization, bounded cache and font-scaled sizing."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics {public float density=2,scaledDensity=2;}',
'android/util/TypedValue.java':'package android.util;public class TypedValue {public static final int COMPLEX_UNIT_SP=2;public static float applyDimension(int u,float n,DisplayMetrics m){return n*m.scaledDensity;}}',
'android/content/res/Resources.java':'package android.content.res;public class Resources {public android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();public android.util.DisplayMetrics getDisplayMetrics(){return metrics;}}',
'android/content/Context.java':'package android.content;public class Context {public android.content.res.Resources resources=new android.content.res.Resources();public android.content.res.Resources getResources(){return resources;}}',
'android/graphics/BlurMaskFilter.java':'package android.graphics;public class BlurMaskFilter {public enum Blur{NORMAL};public float radius;public BlurMaskFilter(float r,Blur b){radius=r;}}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {public enum Config{ARGB_8888};public static int allocations;int w,h;public Bitmap source;public Paint extractPaint;public Bitmap(int w,int h){this.w=w;this.h=h;}public static Bitmap createBitmap(int w,int h,Config c){allocations++;return new Bitmap(w,h);}public Bitmap extractAlpha(Paint p,int[] offset){offset[0]=-30;offset[1]=-30;Bitmap b=createBitmap(w+60,h+60,Config.ARGB_8888);b.source=this;b.extractPaint=p;return b;}public int getWidth(){return w;}public int getHeight(){return h;}}''',
'android/graphics/Paint.java':'''package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;public float size,radius,dy;public int shadow,color;public BlurMaskFilter filter;public Paint(int f){}public void setTypeface(Object t){}public void setTextSize(float n){size=n;}public void setColor(int c){color=c;}public void setMaskFilter(BlurMaskFilter f){filter=f;}public void setShadowLayer(float r,float x,float y,int c){radius=r;dy=y;shadow=c;}public float measureText(String s){return s.length()*size/2;}public float ascent(){return -.8f*size;}public float descent(){return .2f*size;}}''',
'android/graphics/RectF.java':'package android.graphics;public class RectF {public float left,top,right,bottom;public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}}',
'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {public static Canvas raster;public Paint iconPaint,textPaint;public RectF iconRect,shadowRect;public Bitmap icon,shadow;public Paint shade;public int width,height;public float textBaseline;public Canvas(){}public Canvas(Bitmap b){raster=this;width=b.getWidth();height=b.getHeight();}public void drawBitmap(Bitmap b,Object src,RectF dst,Paint p){if(b.source!=null){shadow=b;shadowRect=dst;shade=p;}else{icon=b;iconRect=dst;iconPaint=p;}}public void drawBitmap(Bitmap b,float x,float y,Paint p){}public void drawText(String s,float x,float y,Paint p){textPaint=p;textBaseline=y;}}''',
'io/tvos/Ui.java':'package io.tvos;final class Ui {static Object displayMedium=new Object();static float cap(android.graphics.Paint p){return p.size*.7f;}}',
'io/tvos/Symbols.java':'package io.tvos;final class Symbols {static android.graphics.Bitmap icon=new android.graphics.Bitmap(100,50);static float aspect(android.content.Context c,String n){return n.equals("missing")?0:2;}static android.graphics.Bitmap mask(android.content.Context c,String n,int w,int h){return n.equals("missing")?null:android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);}}',
'io/tvos/WeatherBadgeHarness.java':'''package io.tvos;import android.graphics.*;import android.content.Context;
public class WeatherBadgeHarness {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  Context context=new Context();ScreensaverWeather badge=new ScreensaverWeather(context);badge.setWeather("cloud","+10°");
  Canvas c=Canvas.raster;
  check(c.iconPaint!=null && c.textPaint!=null,"icon and temperature rasterized into transparent bitmap");
  check(c.iconPaint.radius==0 && c.iconPaint.shadow==0,"foreground bitmap has no rectangular shadow layer");
  check(c.shadow!=null && c.shadow.source==c.icon && c.shadow.extractPaint.filter.radius==16,
        "shadow blurs the actual icon alpha at 8dp");
  check(c.shade.color==0xE6000000 && c.shade.shadow==0,"blurred alpha tinted once without bitmap shadow layer");
  check(c.shadowRect.left>=0 && c.shadowRect.top>=0 && c.shadowRect.right<=c.width && c.shadowRect.bottom<=c.height,
        "padding includes extractAlpha bounds, even when larger than requested blur radius");
  check(c.textPaint.size==48 && c.textPaint.shadow==c.shade.color,"same 24sp sizing and shadow for text");
  check(Math.abs((c.iconRect.bottom-c.iconRect.top)-Ui.cap(c.textPaint))<.01f,"icon height matches cap height");
  check(c.iconRect.left>=20 && badge.height()-c.textBaseline>=20,"transparent blur padding on both sides");
  int allocations=Bitmap.allocations;for(int i=0;i<100;i++)badge.setWeather("cloud","+10°");
  check(Bitmap.allocations==allocations,"stable weather does not allocate each frame");
  badge.setWeather("cloud","+11°");check(Bitmap.allocations==allocations+3,"changed temperature rebuilds badge, output-sized mask and blurred alpha once");
  context.resources.metrics.scaledDensity=3;badge.setWeather("cloud","+11°");
  check(Canvas.raster.textPaint.size==72,"font scale follows location label");
  badge.setWeather("missing","+11°");check(Canvas.raster.iconPaint==null && Canvas.raster.textPaint!=null,"missing icon keeps temperature");
  System.out.println("PASS: transparent glyph shadows, 24sp font scaling, icon cap height, padding, cached rendering, missing icon");
 }
}'''
}
sources['io/tvos/ScreensaverWeather.java']=Path('app/src/io/tvos/ScreensaverWeather.java').read_text()
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*[str(root/n) for n in sources]],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.WeatherBadgeHarness'],check=True)
