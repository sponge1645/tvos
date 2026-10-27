"""Execute the production theme wallpaper cache, bounded decode, crop, lifecycle and failure path."""
from pathlib import Path
import subprocess,tempfile,struct
# Validate actual packaged lossless assets without requiring a host image library.
for name,height in [('light',940),('dark',941)]:
 data=Path('app/assets/settings/'+name+'.webp').read_bytes()
 assert data[:4]==b'RIFF' and data[8:12]==b'WEBP'
 offset=12;found=False
 while offset+8<=len(data):
  tag=data[offset:offset+4];size=struct.unpack_from('<I',data,offset+4)[0]
  if tag==b'VP8L':
   assert data[offset+8]==0x2f
   bits=struct.unpack_from('<I',data,offset+9)[0]
   assert (bits&0x3fff)+1==1672 and ((bits>>14)&0x3fff)+1==height
   found=True;break
  offset+=8+size+(size&1)
 assert found,'Wallpaper must retain lossless WebP encoding'
sources={
'android/content/Context.java':'package android.content;public class Context {public final android.content.res.AssetManager assets=new android.content.res.AssetManager();public Context getApplicationContext(){return this;}public android.content.res.AssetManager getAssets(){return assets;}}',
'android/content/res/AssetManager.java':'''package android.content.res;import java.io.*;public class AssetManager {public int opens,closes;public boolean fail;public String last;public InputStream open(String name)throws IOException {opens++;last=name;if(fail)throw new IOException("missing");return new Image(name,this);}public static class Image extends ByteArrayInputStream {public final String name;final AssetManager owner;public Image(String name,AssetManager owner){super(new byte[0]);this.name=name;this.owner=owner;}@Override public void close(){owner.closes++;}}}''',
'android/util/Log.java':'package android.util;public class Log {public static int warnings;public static int w(String tag,String text,Throwable error){warnings++;return 0;}}',
'android/graphics/Paint.java':'package android.graphics;public class Paint {public static final int FILTER_BITMAP_FLAG=2,DITHER_FLAG=4;public final int flags;public Paint(int f){flags=f;}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect {public int left,top,right,bottom;public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public int width(){return right-left;}public int height(){return bottom-top;}}',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {public enum Config{ARGB_8888}public final int w,h;public boolean recycled;public String asset;public Bitmap(int w,int h,String asset){this.w=w;this.h=h;this.asset=asset;}public int getWidth(){return w;}public int getHeight(){return h;}public void recycle(){recycled=true;}}''',
'android/graphics/BitmapFactory.java':'''package android.graphics;import java.io.*;import android.content.res.AssetManager;public class BitmapFactory {public static int allocations,bounds,lastSample;public static boolean fail,invalid,missingDecode;public static class Options {public boolean inJustDecodeBounds;public int outWidth,outHeight,inSampleSize;public Bitmap.Config inPreferredConfig;}public static Bitmap decodeStream(InputStream input,Object rect,Options options){String asset=((AssetManager.Image)input).name;if(options.inJustDecodeBounds){bounds++;options.outWidth=invalid?0:1672;options.outHeight=asset.contains("light")?940:941;return null;}if(fail)throw new OutOfMemoryError("test");if(missingDecode)return null;lastSample=options.inSampleSize;if(options.inPreferredConfig!=Bitmap.Config.ARGB_8888)throw new AssertionError("config");allocations++;return new Bitmap((1672+lastSample-1)/lastSample,(options.outHeight+lastSample-1)/lastSample,asset);}}''',
'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {public Bitmap shown;public Rect source,destination;public Paint paint;public int color;public void drawColor(int color){this.color=color;}public void drawBitmap(Bitmap bitmap,Rect src,Rect dst,Paint paint){if(bitmap.recycled)throw new AssertionError("recycled bitmap");shown=bitmap;source=src;destination=dst;this.paint=paint;}}''',
'io/tvos/SettingsBackgroundHarness.java':'''package io.tvos;import android.graphics.*;import android.content.Context;
public class SettingsBackgroundHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void crop(Canvas c,int width,int height){check(c.source.left>=0&&c.source.top>=0&&c.source.right<=c.shown.w&&c.source.bottom<=c.shown.h,"crop stays in image");float a=(float)c.source.width()/c.source.height(),b=(float)width/height;check(Math.abs(a-b)<.015,"source crop matches viewport aspect");check(c.destination.right==width&&c.destination.bottom==height,"fills entire viewport");}
 public static void main(String[] args){
  Context context=new Context();SettingsBackground bg=new SettingsBackground(context);Canvas out=new Canvas();bg.draw(out,1920,1080,true);Bitmap first=out.shown;Rect destination=out.destination,source=out.source;
  check(first.asset.equals("settings/light.webp")&&first.w==836&&first.h==470,"provided light wallpaper is sampled at decode time");
  check(out.paint.flags==6,"filtered and dithered composition");crop(out,1920,1080);
  for(int i=0;i<100;i++)bg.draw(out,1920,1080,true);check(BitmapFactory.allocations==1&&context.assets.opens==2&&context.assets.closes==2&&out.shown==first&&out.destination==destination&&out.source==source,"steady frames reuse bitmap/rects without reopening assets");
  bg.draw(out,1920,1080,false);check(out.shown.asset.equals("settings/dark.webp")&&out.shown!=first&&!first.recycled,"theme switches image without recycling submitted frame");crop(out,1920,1080);
  bg.draw(out,3840,2160,false);check(out.shown.w<=960&&out.shown.h<=540,"4K decode stays bounded");crop(out,3840,2160);
  bg.draw(out,1281,721,false);check(out.shown.w<=641&&out.shown.h<=361,"small/odd viewport uses smaller decode");crop(out,1281,721);
  bg.draw(out,2560,1080,true);crop(out,2560,1080);check(out.source.height()<out.shown.h,"ultrawide crops top/bottom without stretching");
  bg.draw(out,1080,1920,true);crop(out,1080,1920);check(out.source.width()<out.shown.w,"tall view crops sides without stretching");
  Bitmap old=out.shown;bg.clear();bg.clear();int count=BitmapFactory.allocations;bg.draw(out,1080,1920,true);check(BitmapFactory.allocations==count+1&&out.shown!=old&&!old.recycled,"pause clears reference; resume reloads one bitmap");
  BitmapFactory.fail=true;Canvas fallback=new Canvas();bg.draw(fallback,800,600,true);check(fallback.shown==null&&fallback.color==0xFFB2F0F4,"OOM uses light theme fallback");int attempts=context.assets.opens,warnings=android.util.Log.warnings;for(int i=0;i<100;i++)bg.draw(fallback,800,600,true);check(context.assets.opens==attempts&&android.util.Log.warnings==warnings,"failed decode does not repeat every frame");
  BitmapFactory.fail=false;bg.clear();bg.draw(out,800,600,true);check(out.shown.w<=400&&out.shown.h<=300,"clear permits recovery");
  context.assets.fail=true;bg.clear();Canvas missing=new Canvas();bg.draw(missing,1920,1080,false);check(missing.color==0xFF092632,"missing dark asset uses dark fallback");context.assets.fail=false;
  BitmapFactory.invalid=true;bg.clear();bg.draw(new Canvas(),1920,1080,true);BitmapFactory.invalid=false;BitmapFactory.missingDecode=true;bg.clear();bg.draw(new Canvas(),1920,1080,true);BitmapFactory.missingDecode=false;
  attempts=context.assets.opens;bg.draw(new Canvas(),0,1080,true);check(context.assets.opens==attempts,"zero size does no work");
  bg.clear();bg.draw(out,1,1,true);check(out.shown.w==1&&out.shown.h==1,"tiny viewport terminates with minimum decode");
  check(context.assets.closes==context.assets.opens-1,"all successfully opened streams close, including failure paths");
  System.out.println("PASS: actual light/dark asset selection, bounded decode, cached frame reuse, center crop, 720p/1080p/4K/tall/ultrawide, lifecycle and throttled OOM/missing/corrupt fallback");
 }
}'''
}
sources['io/tvos/SettingsBackground.java']=Path('app/src/io/tvos/SettingsBackground.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-settings-background-') as tmp:
 root=Path(tmp)
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SettingsBackgroundHarness'],check=True)
