"""Compile the local icon compositor; verify grayscale, alpha, proportions and temporary ownership."""
from pathlib import Path
import subprocess, tempfile
files={
'android/content/Context.java':'package android.content;public class Context {public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager {public static class NameNotFoundException extends Exception {}public android.graphics.drawable.Drawable icon=new android.graphics.drawable.Drawable();public android.graphics.drawable.Drawable getApplicationIcon(String p)throws NameNotFoundException {if(p.equals("missing"))throw new NameNotFoundException();return icon;}}',
'android/graphics/drawable/Drawable.java':'package android.graphics.drawable;public class Drawable {public int l,t,r,b;public int getIntrinsicWidth(){return 400;}public int getIntrinsicHeight(){return 200;}public void setBounds(int a,int c,int d,int e){l=a;t=c;r=d;b=e;}public void draw(android.graphics.Canvas c){}}',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap {public static java.util.List<Bitmap> made=new java.util.ArrayList<>();boolean recycled;public enum Config {ARGB_8888}public static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();made.add(b);return b;}public void recycle(){recycled=true;}public boolean isRecycled(){return recycled;}}',
'android/graphics/Shader.java':'package android.graphics;public class Shader {public enum TileMode {CLAMP}}',
'android/graphics/LinearGradient.java':'package android.graphics;public class LinearGradient extends Shader {public LinearGradient(float a,float b,float c,float d,int e,int f,TileMode m){}}',
'android/graphics/ColorMatrix.java':'package android.graphics;public class ColorMatrix {public float[] v;public ColorMatrix(float[] a){v=a;}}',
'android/graphics/ColorMatrixColorFilter.java':'package android.graphics;public class ColorMatrixColorFilter {public ColorMatrix m;public ColorMatrixColorFilter(ColorMatrix a){m=a;}}',
'android/graphics/Paint.java':'package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2,DITHER_FLAG=4;public ColorMatrixColorFilter filter;public Paint(int f){}public void setShader(Shader s){}public void setColorFilter(ColorMatrixColorFilter f){filter=f;}}',
'android/graphics/RectF.java':'package android.graphics;public class RectF {public float l,t,r,b;public RectF(float a,float c,float d,float e){l=a;t=c;r=d;b=e;}}',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas {public static ColorMatrix matrix;public static RectF rect;public Canvas(Bitmap b){}public void drawRect(float a,float b,float c,float d,Paint p){}public void drawBitmap(Bitmap b,Object src,RectF r,Paint p){matrix=p.filter.m;rect=r;}}',
'io/tvos/Backdrop.java':'package io.tvos;class Backdrop {static Backdrop hero(android.graphics.Bitmap b){b.recycle();return new Backdrop();}}',
'io/tvos/AppLog.java':'package io.tvos;class AppLog {static void w(String s){}}',
'io/tvos/DockAppBackdrop.java':Path('app/src/io/tvos/DockAppBackdrop.java').read_text(),
'io/tvos/IconHarness.java':'''package io.tvos;import android.graphics.*;public class IconHarness {static void check(boolean b,String s){if(!b)throw new AssertionError(s);}public static void main(String[] args){android.content.Context c=new android.content.Context();check(DockAppBackdrop.create(c,"installed")!=null,"installed icon produces background");check(c.pm.icon.l==0&&c.pm.icon.r==512&&c.pm.icon.t==128&&c.pm.icon.b==384,"non-square icon keeps proportions");float[] m=Canvas.matrix.v;for(int i=0;i<5;i++)check(m[i]==m[5+i]&&m[i]==m[10+i],"RGB rows are equal grayscale");check(m[3]==0&&m[4]==100&&m[18]==1&&m[19]==0,"luminance lifted with original alpha preserved");check(Canvas.rect.l==780&&Canvas.rect.r==1140,"icon centered at bounded size");check(Bitmap.made.stream().allMatch(Bitmap::isRecycled),"temporary bitmaps released");int count=Bitmap.made.size();check(DockAppBackdrop.create(c,"missing")==null&&Bitmap.made.size()==count,"missing app allocates no bitmap");System.out.println("PASS: local app icon grayscale, alpha, aspect ratio, bounds and bitmap ownership");}}'''
}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.IconHarness'],check=True,timeout=20)
