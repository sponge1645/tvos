"""Run production artwork pixel processing and cache lifecycle with deterministic JVM graphics."""
from pathlib import Path
import subprocess
import tempfile
source=Path('app/src/io/tvos/AppArtwork.java').read_text()
def method(signature):
 a=source.index(signature);b=source.index('{',a);depth=1;i=b+1
 while depth:depth+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
methods=[
 '    static synchronized void pruneTiles(', '    static Bitmap shelfLogo(',
 '    private static File logoFile(', '    private static void saveLogo(', '    static float ink(',
 '    private static Bitmap bannerLogo(', '    private static void fillHoles(', '    private static Bitmap trimLogo(',
 '    private static void add(', '    private static void smooth(', '    private static float lum(',
 '    private static float sat(', '    private static void vivid(', '    private static float smoothstep(',
 '    private static float clamp(', '    static synchronized Bitmap appTile(',
 '    static Bitmap ownLogoCached(', '    private static Bitmap ownLogo(', '    private static Bitmap trim(',
]
files={
'android/graphics/Bitmap.java':'''package android.graphics;
import java.io.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;
public class Bitmap {
 public enum Config {ARGB_8888}public enum CompressFormat {PNG}
 public final int w,h;public final int[] pixels;public boolean recycled,mipmap;
 public Bitmap(int w,int h){this.w=w;this.h=h;pixels=new int[w*h];}
 public int getWidth(){return w;}public int getHeight(){return h;}public int getPixel(int x,int y){return pixels[y*w+x];}
 public void setHasMipMap(boolean b){mipmap=b;}public void recycle(){recycled=true;}
 public void getPixels(int[] out,int offset,int stride,int x,int y,int width,int height){for(int row=0;row<height;row++)System.arraycopy(pixels,(y+row)*w+x,out,offset+row*stride,width);}
 public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap(w,h);}
 public static Bitmap createBitmap(int[] data,int w,int h,Config c){Bitmap b=new Bitmap(w,h);System.arraycopy(data,0,b.pixels,0,data.length);return b;}
 public static Bitmap createBitmap(Bitmap src,int x,int y,int w,int h){Bitmap b=new Bitmap(w,h);src.getPixels(b.pixels,0,w,x,y,w,h);return b;}
 public boolean compress(CompressFormat f,int quality,OutputStream out){try{BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);b.setRGB(0,0,w,h,pixels,0,w);return ImageIO.write(b,"png",out);}catch(IOException e){return false;}}
}
''',
'android/graphics/BitmapFactory.java':'''package android.graphics;
import java.io.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;
public class BitmapFactory {
 public static int fileReads,sample;
 public static class Options {public boolean inJustDecodeBounds;public int inSampleSize=1,outWidth,outHeight;}
 public static Bitmap decodeFile(String p){fileReads++;try(InputStream in=new FileInputStream(p)){return decodeStream(in,null,new Options());}catch(IOException e){return null;}}
 public static Bitmap decodeStream(InputStream in,Object unused,Options o){try{BufferedImage image=ImageIO.read(in);if(image==null)return null;
  o.outWidth=image.getWidth();o.outHeight=image.getHeight();if(o.inJustDecodeBounds)return null;sample=o.inSampleSize;
  Bitmap b=new Bitmap(Math.max(1,o.outWidth/sample),Math.max(1,o.outHeight/sample));
  for(int y=0;y<b.h;y++)for(int x=0;x<b.w;x++)b.pixels[y*b.w+x]=image.getRGB(x*sample,y*sample);return b;
 }catch(IOException e){return null;}}
}
''',
'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {
 public final Bitmap target;public Canvas(Bitmap b){target=b;}
 public void drawColor(int color){java.util.Arrays.fill(target.pixels,color);}
 public void drawBitmap(Bitmap b,float x,float y,Object paint){for(int yy=0;yy<b.h;yy++)for(int xx=0;xx<b.w;xx++){
  int dx=(int)x+xx,dy=(int)y+yy;if(dx>=0&&dy>=0&&dx<target.w&&dy<target.h)target.pixels[dy*target.w+dx]=b.pixels[yy*b.w+xx];}}
}
''',
'android/graphics/Color.java':'''package android.graphics;public class Color {
 public static void RGBToHSV(int r,int g,int b,float[] out){java.awt.Color.RGBtoHSB(r,g,b,out);out[0]*=360;}
 public static int HSVToColor(float[] hsv){return java.awt.Color.HSBtoRGB(hsv[0]/360,hsv[1],hsv[2]);}
}
''',
'android/graphics/drawable/Drawable.java':'''package android.graphics.drawable;public abstract class Drawable {public void setBounds(int l,int t,int r,int b){}public abstract void draw(android.graphics.Canvas canvas);}
''',
'io/tvos/AppArtwork.java':'''package io.tvos;
import android.graphics.*;import android.graphics.drawable.Drawable;import java.io.*;import java.util.*;
final class AppArtwork {
 private static final int LOGO_VERSION=2;
 static final Map<String,Bitmap> LOGOS=new HashMap<>(),TILES=new HashMap<>();
 static class AppLog {static void w(String s){}}
 static class PackageManager {
  static class NameNotFoundException extends Exception {}long updated=123;
  static class Info {long lastUpdateTime;Info(long time){lastUpdateTime=time;}}
  Info getPackageInfo(String pkg,int flags)throws NameNotFoundException{return new Info(updated);}
 }
 static class Assets {
  final Map<String,byte[]> data=new HashMap<>();int opens;
  InputStream open(String key)throws IOException{opens++;byte[] bytes=data.get(key);if(bytes==null)throw new FileNotFoundException(key);return new ByteArrayInputStream(bytes);}
  void put(String key,Bitmap b)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);data.put("logos/"+key+".png",out.toByteArray());}
 }
 static class Context {
  final File cache;final Assets assets=new Assets();final PackageManager pm=new PackageManager();
  final Map<String,Drawable> banners=new HashMap<>();int bannerLoads,tileLoads;
  Context(File dir){cache=dir;}Assets getAssets(){return assets;}File getCacheDir(){return cache;}PackageManager getPackageManager(){return pm;}
 }
 private static Drawable bannerDrawable(Context c,String pkg){c.bannerLoads++;return c.banners.get(pkg);}
 private static Bitmap bannerTile(Context c,String pkg){c.tileLoads++;return null;}
 private static Bitmap iconTile(Context c,String pkg){return Bitmap.createBitmap(384,216,Bitmap.Config.ARGB_8888);}
 private static Bitmap logoTile(Bitmap b,int color){return Bitmap.createBitmap(384,216,Bitmap.Config.ARGB_8888);}
'''+''.join(method(s) for s in methods)+'''
 static Bitmap trimmed(Bitmap b){return trim(b);}static Bitmap extracted(Context c,String pkg,boolean tint,boolean holes){return bannerLogo(c,pkg,tint,holes);}
 static void holes(int[] p,int w,int h){fillHoles(p,w,h);}
}
''',
'io/tvos/ArtworkHarness.java':'''package io.tvos;
import android.graphics.*;import android.graphics.drawable.Drawable;import java.io.*;import java.util.*;
public class ArtworkHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static Bitmap solid(int w,int h,int color){Bitmap b=new Bitmap(w,h);Arrays.fill(b.pixels,color);return b;}
 static Drawable banner(int background,int foreground,boolean gradient){return new Drawable(){public void draw(Canvas canvas){
  Bitmap b=canvas.target;for(int y=0;y<b.h;y++)for(int x=0;x<b.w;x++){
   int bg=gradient?(0xff000000|((20+y*40/b.h)<<16)|((35+x*40/b.w)<<8)|90):background;
   b.pixels[y*b.w+x]=x>b.w/3&&x<2*b.w/3&&y>b.h/3&&y<2*b.h/3?foreground:bg;
  }
 }};}
 static void ring(int[] p,int w,int h,int color){for(int y=4;y<h-4;y++)for(int x=4;x<w-4;x++)if(y==4||y==h-5||x==4||x==w-5)p[y*w+x]=color;}
 public static void main(String[] args)throws Exception{
  AppArtwork.Context c=new AppArtwork.Context(new File(args[0]));
  Bitmap transparent=new Bitmap(7,5);check(AppArtwork.trimmed(transparent)==transparent,"empty bitmap stays valid");
  Bitmap padded=new Bitmap(8,7);padded.pixels[2*8+3]=0x0affffff;padded.pixels[3*8+4]=0xffffffff;
  Bitmap trimmed=AppArtwork.trimmed(padded);check(trimmed.w==1&&trimmed.h==1&&trimmed.getPixel(0,0)==-1,"alpha threshold and exact trim bounds");
  check(!padded.recycled,"trimming never recycles a shared source");
  check(AppArtwork.ink(transparent)==.08f&&AppArtwork.ink(solid(7,5,-1))==1f,"ink floor and opaque density");
  int[] colored=new int[40*40];ring(colored,40,40,0xffff0000);AppArtwork.holes(colored,40,40);
  check(colored[20*40+20]==0,"large enclosed regions remain holes");
  colored=new int[40*40];for(int y=15;y<22;y++)for(int x=15;x<22;x++)if(x==15||x==21||y==15||y==21)colored[y*40+x]=0xffff0000;
  AppArtwork.holes(colored,40,40);check(colored[18*40+18]==-1&&colored[0]==0,"small saturated hole fills, exterior stays transparent");
  int[] letters=new int[40*40];for(int y=15;y<22;y++)for(int x=15;x<22;x++)if(x==15||x==21||y==15||y==21)letters[y*40+x]=-1;
  AppArtwork.holes(letters,40,40);check(letters[18*40+18]==0,"white letter counters stay open");
  c.assets.put("bundled",solid(1280,640,-1));Bitmap bundled=AppArtwork.ownLogoCached(c,"bundled");
  check(bundled.w==640&&bundled.h==320&&BitmapFactory.sample==2,"bounded power-of-two decode preserves prior sampling");
  int opens=c.assets.opens;check(AppArtwork.ownLogoCached(c,"bundled")==bundled&&c.assets.opens==opens,"bundled image decoded once");
  check(AppArtwork.ownLogoCached(c,"missing")==null,"missing asset fallback");opens=c.assets.opens;
  check(AppArtwork.ownLogoCached(c,"missing")==null&&c.assets.opens==opens,"missing bundled asset cached");
  c.banners.put("plain",banner(0xff202020,0xffcc3333,false));
  Bitmap extracted=AppArtwork.extracted(c,"plain",false,false);
  check(extracted!=null&&extracted.w<768&&extracted.h<432,"foreground extracted and trimmed");
  check(extracted.getPixel(0,0)==0&&(extracted.getPixel(extracted.w/2,extracted.h/2)>>>24)>128,"transparent padding with opaque logo center");
  c.banners.put("gradient",banner(0,0xff33dd99,true));check(AppArtwork.extracted(c,"gradient",false,false)!=null,"gradient background extraction");
  c.banners.put("white",banner(0xffeeeeee,0xff111111,false));Bitmap white=AppArtwork.extracted(c,"white",false,false);
  check((white.getPixel(white.w/2,white.h/2)&0xffffff)==0xffffff,"dark lettering becomes white");
  c.banners.put("tint",banner(0xff184a99,-1,false));Bitmap tinted=AppArtwork.extracted(c,"tint",true,false);
  int center=tinted.getPixel(tinted.w/2,tinted.h/2);check((center&255)>((center>>16)&255),"white logo takes vivid blue background tint");
  int loads=c.bannerLoads;
  check(AppArtwork.shelfLogo(c,"deferred","plain","",false)==null&&c.bannerLoads==loads,"fast shelf load skips slow extraction");
  Bitmap shelf=AppArtwork.shelfLogo(c,"deferred","plain","",true);
  check(shelf!=null&&shelf.mipmap&&c.bannerLoads==loads+1,"later worker extraction succeeds after deferred lookup");
  check(AppArtwork.shelfLogo(c,"deferred","plain","",true)==shelf&&c.bannerLoads==loads+1,"extracted logo reused in memory");
  AppArtwork.LOGOS.clear();int reads=BitmapFactory.fileReads;
  Bitmap disk=AppArtwork.shelfLogo(c,"deferred","plain","",false);
  check(disk!=null&&BitmapFactory.fileReads==reads+1&&c.bannerLoads==loads+1,"disk logo restores without extraction");
  c.pm.updated=456;AppArtwork.LOGOS.clear();check(AppArtwork.shelfLogo(c,"deferred","plain","",false)==null,"updated package does not reuse old disk version");
  AppArtwork.shelfLogo(c,"deferred","plain","",true);
  check(!new File(c.cache,"logos/deferred-123-v2.png").exists()&&new File(c.cache,"logos/deferred-456-v2.png").exists(),"saving removes old file versions");
  loads=c.bannerLoads;check(AppArtwork.shelfLogo(c,"no-banner","absent","",true)==null,"failed extraction returns fallback");
  check(new File(c.cache,"logos/no-banner-456-v2.png.none").exists(),"failed extraction saved as negative disk marker");
  AppArtwork.LOGOS.clear();check(AppArtwork.shelfLogo(c,"no-banner","absent","",true)==null&&c.bannerLoads==loads+1,"negative marker avoids repeated background work");
  Bitmap tile=AppArtwork.appTile(c,null,"example.app",0);check(tile.mipmap&&AppArtwork.appTile(c,null,"example.app",0)==tile&&c.tileLoads==1,"fallback tile prepared once with mipmaps");
  AppArtwork.TILES.put("gone.app",tile);AppArtwork.TILES.put("symbol",tile);AppArtwork.pruneTiles(new HashSet<>(Arrays.asList("example.app")));
  check(!AppArtwork.TILES.containsKey("gone.app")&&AppArtwork.TILES.containsKey("example.app")&&AppArtwork.TILES.containsKey("symbol"),"existing tile prune rule preserved");
  System.out.println("PASS: artwork trim/ink/holes, gradient extraction, tint, bounded decoding, memory/disk/negative caches, deferred loading, versioned files, tile reuse");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='tvos-artwork-') as tmp:
 root=Path(tmp)
 for name,value in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-Djava.awt.headless=true','-cp',tmp,'io.tvos.ArtworkHarness',str(root/'cache')],check=True)
