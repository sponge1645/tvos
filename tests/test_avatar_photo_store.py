"""Execute real avatar validation/crop/re-encoding and atomic publication with Java2D decoder adapters."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/content/Context.java':'''package android.content;public class Context {final java.io.File dir;public Context(java.io.File d){dir=d;d.mkdirs();}public java.io.File getFilesDir(){return dir;}}''',
'android/graphics/Color.java':'package android.graphics;public class Color {public static int WHITE=0xffffffff;}',
'android/graphics/Paint.java':'package android.graphics;public class Paint {public static int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;public Paint(int flags){}}',
'android/graphics/RectF.java':'package android.graphics;public class RectF {public float left,top,right,bottom;public RectF(float a,float b,float c,float d){left=a;top=b;right=c;bottom=d;}}',
'android/graphics/Bitmap.java':'''package android.graphics;import java.awt.image.*;import java.io.*;import javax.imageio.*;public class Bitmap {public BufferedImage image;public Bitmap(BufferedImage i){image=i;}public enum Config {ARGB_8888}public enum CompressFormat {JPEG}public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap(new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB));}public int getWidth(){return image.getWidth();}public int getHeight(){return image.getHeight();}public void recycle(){}public boolean compress(CompressFormat f,int quality,OutputStream out){try{BufferedImage rgb=new BufferedImage(getWidth(),getHeight(),BufferedImage.TYPE_INT_RGB);rgb.getGraphics().drawImage(image,0,0,null);return ImageIO.write(rgb,"jpg",out);}catch(IOException e){return false;}}}''',
'android/graphics/BitmapFactory.java':'''package android.graphics;import java.io.*;import javax.imageio.*;import java.awt.image.*;public class BitmapFactory {public static int reads;public static boolean huge;public static class Options {public boolean inJustDecodeBounds;public int outWidth,outHeight,inSampleSize;}public static Bitmap decodeByteArray(byte[] d,int off,int len,Options o){reads++;try{BufferedImage image=ImageIO.read(new ByteArrayInputStream(d,off,len));if(image==null)return null;o.outWidth=huge?100000:image.getWidth();o.outHeight=huge?100000:image.getHeight();if(o.inJustDecodeBounds)return null;int w=Math.max(1,image.getWidth()/o.inSampleSize),h=Math.max(1,image.getHeight()/o.inSampleSize);BufferedImage sampled=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);sampled.getGraphics().drawImage(image,0,0,w,h,null);return new Bitmap(sampled);}catch(IOException e){return null;}}}''',
'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {final Bitmap b;public Canvas(Bitmap b){this.b=b;}public void drawColor(int c){java.awt.Graphics2D g=b.image.createGraphics();g.setColor(new java.awt.Color(c,true));g.fillRect(0,0,b.getWidth(),b.getHeight());g.dispose();}public void drawBitmap(Bitmap image,Object source,RectF r,Paint p){java.awt.Graphics2D g=b.image.createGraphics();g.drawImage(image.image,Math.round(r.left),Math.round(r.top),Math.round(r.right),Math.round(r.bottom),0,0,image.getWidth(),image.getHeight(),null);g.dispose();}}''',
'io/tvos/ProfileAvatar.java':'package io.tvos;class ProfileAvatar {static int selections;static void selectCustom(android.content.Context c){selections++;}}',
}
harness='''package io.tvos;import java.io.*;import java.nio.file.*;import java.awt.image.*;import javax.imageio.*;import android.content.Context;import io.tvos.avatar.AvatarUploadServer;
public class Harness {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 static byte[] image(String type)throws Exception{BufferedImage image=new BufferedImage(900,700,BufferedImage.TYPE_INT_RGB);java.awt.Graphics2D g=image.createGraphics();g.setColor(java.awt.Color.RED);g.fillRect(0,0,450,700);g.setColor(java.awt.Color.BLUE);g.fillRect(450,0,450,700);g.dispose();ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,type,out);return out.toByteArray();}
 static void reject(Context c,byte[] bytes)throws Exception{try(AvatarUploadServer.Prepared p=AvatarPhotoStore.prepare(c,bytes)){throw new AssertionError("invalid input accepted");}catch(IOException expected){}}
 public static void main(String[] args)throws Exception{
 Context context=new Context(new File(args[0]));byte[] png=image("png");
 try(AvatarUploadServer.Prepared prepared=AvatarPhotoStore.prepare(context,png)){check(!AvatarPhotoStore.file(context).exists(),"not published before session commit");prepared.commit();}
 BufferedImage avatar=ImageIO.read(AvatarPhotoStore.file(context));check(avatar.getWidth()==512&&avatar.getHeight()==512&&ProfileAvatar.selections==1,"bounded avatar is selected after atomic publish");
 check((avatar.getRGB(0,256)&0xff0000)>0xf00000&&(avatar.getRGB(511,256)&255)>240,"center crop is preserved");
 byte[] before=Files.readAllBytes(AvatarPhotoStore.file(context).toPath());
 try(AvatarUploadServer.Prepared cancelled=AvatarPhotoStore.prepare(context,png)){}check(java.util.Arrays.equals(before,Files.readAllBytes(AvatarPhotoStore.file(context).toPath())),"discard leaves previous avatar intact");
 for(String bad:new String[]{"PK fake ZIP","%PDF-1.7","0000ftypqt  MOV","not a photo"})reject(context,bad.getBytes("UTF-8"));
 reject(context,new byte[]{(byte)255,(byte)216,(byte)255,0,(byte)255,(byte)217});
 int reads=android.graphics.BitmapFactory.reads;reject(context,new byte[2097153]);check(reads==android.graphics.BitmapFactory.reads,"size rejected before image decode");
 android.graphics.BitmapFactory.huge=true;reject(context,png);android.graphics.BitmapFactory.huge=false;
 byte[] jpeg=image("jpg"),secret="metadata-secret".getBytes("UTF-8");ByteArrayOutputStream decorated=new ByteArrayOutputStream();decorated.write(jpeg,0,2);decorated.write(new byte[]{(byte)255,(byte)225,0,(byte)(secret.length+2)});decorated.write(secret);decorated.write(jpeg,2,jpeg.length-2);
 try(AvatarUploadServer.Prepared prepared=AvatarPhotoStore.prepare(context,decorated.toByteArray())){prepared.commit();}
 check(!new String(Files.readAllBytes(AvatarPhotoStore.file(context).toPath()),"ISO-8859-1").contains("metadata-secret"),"upload metadata is not stored");
 File[] files=context.getFilesDir().listFiles();check(files.length==1&&files[0].getName().equals("profile-avatar.jpg"),"no original or abandoned temporary images retained");
 System.out.println("PASS: real PNG/JPEG decode, 512px center crop, metadata removal, atomic/discard behavior, size/dimension bounds and ZIP/PDF/MOV rejection");
 }}'''
sources['io/tvos/Harness.java']=harness
sources['io/tvos/AvatarPhotoStore.java']=Path('app/src/io/tvos/AvatarPhotoStore.java').read_text()
sources['io/tvos/avatar/AvatarUploadServer.java']=Path('app/src/io/tvos/avatar/AvatarUploadServer.java').read_text()
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-Djava.awt.headless=true','-cp',d,'io.tvos.Harness',str(root/'private')],check=True,timeout=15)
