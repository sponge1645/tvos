"""Exercise the actual list compositing boundary and resize-dependent fade geometry."""
from pathlib import Path
import subprocess
import tempfile

files = {
'android/graphics/Shader.java': 'package android.graphics; public class Shader {public enum TileMode {CLAMP}}',
'android/graphics/LinearGradient.java': '''package android.graphics; public class LinearGradient extends Shader {
 public static int allocations;public float top,bottom;public int[] colors;public float[] stops;
 public LinearGradient(float x,float y,float r,float b,int[] c,float[] s,TileMode m){allocations++;top=y;bottom=b;colors=c;stops=s;}}''',
'android/graphics/PorterDuff.java': 'package android.graphics;public class PorterDuff {public enum Mode {DST_IN}}',
'android/graphics/PorterDuffXfermode.java': 'package android.graphics;public class PorterDuffXfermode {public PorterDuff.Mode mode;public PorterDuffXfermode(PorterDuff.Mode m){mode=m;}}',
'android/graphics/Paint.java': 'package android.graphics;public class Paint {public Shader shader;public PorterDuffXfermode mode;public void setShader(Shader s){shader=s;}public void setXfermode(PorterDuffXfermode m){mode=m;}}',
'android/graphics/Canvas.java': '''package android.graphics;public class Canvas {
 public String order="";public int depth;public float left,top,right,bottom;public Paint mask;
 public int saveLayer(float l,float t,float r,float b,Paint p){order+="layer,";left=l;top=t;right=r;bottom=b;return ++depth;}
 public void clipRect(float l,float t,float r,float b){if(l!=left||t!=top||r!=right||b!=bottom)throw new AssertionError();order+="clip,";}
 public void drawRect(float l,float t,float r,float b,Paint p){clipRect(l,t,r,b);order+="mask,";mask=p;}
 public void restoreToCount(int count){depth=count-1;order+="restore,";}}''',
'Harness.java': '''import android.graphics.*;import io.tvos.settings.SettingsListViewport;
public class Harness {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void near(float a,float b){check(Math.abs(a-b)<.0001f);}
 public static void main(String[] args){SettingsListViewport v=new SettingsListViewport();Paint previous=null;
 for(float k:new float[]{2f/3,1,2}){int w=Math.round(1920*k),h=Math.round(1080*k);v.resize(w,h,k);
  Canvas c=new Canvas();v.draw(c, canvas->{check(canvas==c&&c.depth==1);c.order+="content,";});
  check(c.order.equals("layer,clip,content,clip,mask,restore,")&&c.depth==0);
  near(c.left,960*k);near(c.top,148*k);near(c.right,w);near(c.bottom,h);
  check(c.mask.mode.mode==PorterDuff.Mode.DST_IN);LinearGradient g=(LinearGradient)c.mask.shader;
  near(g.top,148*k);near(g.bottom,h);near(g.stops[0],0);near(g.stops[1],38*k/(h-148*k));near(g.stops[2],1-64*k/(h-148*k));near(g.stops[3],1);
  check(java.util.Arrays.equals(g.colors,new int[]{0x00FFFFFF,0xFFFFFFFF,0xFFFFFFFF,0x00FFFFFF}));
  if(previous!=null)check(previous==c.mask);previous=c.mask;int n=LinearGradient.allocations;
  for(int i=0;i<100;i++){Canvas next=new Canvas();v.draw(next,canvas->{});check(next.mask==c.mask&&next.mask.shader==g&&next.depth==0);}
  check(n==LinearGradient.allocations);
 }
 Canvas failed=new Canvas();try{v.draw(failed,c->{throw new IllegalStateException();});throw new AssertionError();}catch(IllegalStateException expected){check(failed.depth==0);}
 System.out.println("PASS: list viewport clipping, composition, fade geometry, resize, reuse and Canvas restoration");}}
'''}
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name, body in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 sources=[str(p) for p in root.rglob('*.java')]
 sources.append('app/src/io/tvos/settings/SettingsListViewport.java')
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*sources],check=True)
 subprocess.run(['java','-cp',tmp,'Harness'],check=True)
