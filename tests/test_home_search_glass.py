"""Execute the complete input backing and View adapters, including material and cleanup."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
files={
'android/graphics/RectF.java':'''package android.graphics;public class RectF {public static int allocations;public float left,top,right,bottom;public RectF(){allocations++;}public void set(RectF r){set(r.left,r.top,r.right,r.bottom);}public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}public float height(){return bottom-top;}public boolean isEmpty(){return right<=left||bottom<=top;}public void offset(float x,float y){left+=x;right+=x;top+=y;bottom+=y;}}''',
'android/graphics/Paint.java':'''package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1;public static int allocations;public int color;public Object mask;public Paint(int flags){allocations++;}public void setColor(int c){color=c;}public void setMaskFilter(Object f){mask=f;}}''',
'android/graphics/BlurMaskFilter.java':'''package android.graphics;public class BlurMaskFilter {public enum Blur {NORMAL}public static int allocations;public final float radius;public BlurMaskFilter(float r,Blur b){radius=r;allocations++;}}''',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas {}',
'android/view/View.java':'''package android.view;import java.util.*;public class View {
 public static final int VISIBLE=0,INVISIBLE=4;public int width=100,height=50,x=150,y=120,invalidations,visibility;public float alpha=1,sx=1,sy=1,translation;public final Set<int[]> buffers=Collections.newSetFromMap(new IdentityHashMap<int[],Boolean>());public final Animator animator=new Animator();
 public int getWidth(){return width;}public int getHeight(){return height;}public void getLocationOnScreen(int[] a){buffers.add(a);a[0]=x;a[1]=y;}public void invalidate(){invalidations++;}public void setVisibility(int n){visibility=n;}public void setAlpha(float f){alpha=f;}public void setScaleX(float f){sx=f;}public void setScaleY(float f){sy=f;}public void setTranslationY(float f){translation=f;}public Animator animate(){return animator;}public static class Animator {public int cancels;public void cancel(){cancels++;}}}''',
'io/tvos/ActionSurface.java':'package io.tvos;class ActionSurface {'+block(Path('app/src/io/tvos/ActionSurface.java').read_text(),'    static int blend(')+'}',
'io/tvos/Fakes.java':'''package io.tvos;import android.graphics.*;
class Scene {int draws,color;float left,top,right,bottom,radius;float[] material;void liquid(Canvas c,RectF r,float rad,int ink,float... values){draws++;left=r.left;top=r.top;right=r.right;bottom=r.bottom;radius=rad;color=ink;material=values;}}
class Shadows {int draws,color;Object mask;float top,radius,blur;void draw(Canvas c,RectF r,float rad,float b,Paint p){draws++;color=p.color;mask=p.mask;top=r.top;radius=rad;blur=b;}}
''',
'io/tvos/GlassTest.java':'''package io.tvos;import android.graphics.*;import android.view.View;
public class GlassTest {
 static final int VISIBLE=0;boolean running=true;int tones;final View home=new View(),layer=new View();final Scene scene=new Scene();final Shadows shadows=new Shadows();final HomeSearchGlass searchGlass=new HomeSearchGlass(home,layer,scene,shadows);
 static class Input {boolean shown=true;boolean showing(){return shown;}}final Input searchInput=new Input();static class Background {float n;float night(){return n;}}final Background background=new Background();float k=1;void applyTone(){tones++;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b){check(Math.abs(a-b)<.0001,a+" != "+b);}
'''+block(home,'    private void syncSearchGlass(')+block(home,'    private void drawSearchGlass(')+'''
 public static void main(String[] args){GlassTest t=new GlassTest();t.home.x=20;t.home.y=30;Canvas c=new Canvas();t.drawSearchGlass(c);check(t.scene.draws==0&&t.tones==0,"hidden backing performs no drawing or tone updates");View panel=new View();t.searchGlass.resize(1);int paints=Paint.allocations,rects=RectF.allocations,filters=BlurMaskFilter.allocations;
 for(int i=0;i<1000;i++){t.syncSearchGlass(panel);t.searchGlass.resize(1);t.drawSearchGlass(c);}check(panel.buffers.size()==1&&t.home.buffers.size()==1,"position buffers retained");check(Paint.allocations==paints&&RectF.allocations==rects&&BlurMaskFilter.allocations==filters,"stable scale reuses Paint, bounds and blur filter");check(t.searchGlass.visible()&&t.layer.visibility==VISIBLE&&t.layer.invalidations==1000&&t.tones==1000,"sync visibility and refresh");near(t.scene.left,130);near(t.scene.top,90);near(t.scene.right,230);near(t.scene.bottom,140);near(t.scene.radius,25);near(t.shadows.top,99);near(t.shadows.blur,26);check(t.scene.color==0xffffffff&&t.shadows.color==0x1f000000,"day colors");near(t.scene.material[0],.42f);near(t.scene.material[1],.03f);near(t.scene.material[2],30);near(t.scene.material[3],22);near(t.scene.material[4],.65f);near(t.scene.material[5],1);
 panel.x=230;panel.width=200;t.syncSearchGlass(panel);t.drawSearchGlass(c);near(t.scene.left,210);near(t.scene.right,410);
 int updates=t.layer.invalidations;t.running=false;t.syncSearchGlass(panel);t.running=true;t.searchInput.shown=false;t.syncSearchGlass(panel);t.searchInput.shown=true;panel.height=0;t.syncSearchGlass(panel);check(t.layer.invalidations==updates,"inactive/hidden/unmeasured guards preserved");panel.height=50;
 for(float scale:new float[]{2f/3,1,2}){t.k=scale;t.searchGlass.resize(scale);t.background.n=1;t.drawSearchGlass(c);near(((BlurMaskFilter)t.shadows.mask).radius,26*scale);near(t.shadows.blur,26*scale);near(t.shadows.top,t.scene.top+9*scale);check(t.scene.color==0xff111113&&t.shadows.color==0x52000000,"night colors");near(t.scene.material[0],.12f);near(t.scene.material[1],.08f);near(t.scene.material[2],34*scale);near(t.scene.material[3],28*scale);near(t.scene.material[4],.95f);}
 t.background.n=.5f;t.drawSearchGlass(c);check(t.scene.color==0xff888889&&t.shadows.color==0x39000000,"intermediate colors use existing rounding");near(t.scene.material[0],.27f);near(t.scene.material[1],.055f);
 t.layer.alpha=.2f;t.layer.sx=.8f;t.layer.sy=.9f;t.layer.translation=50;t.searchGlass.hide();check(!t.searchGlass.visible()&&t.layer.visibility==View.INVISIBLE&&t.layer.alpha==1&&t.layer.sx==1&&t.layer.sy==1&&t.layer.translation==0&&t.layer.animator.cancels==1,"dismiss cancels animation and restores all overlay transforms");int draws=t.scene.draws;t.drawSearchGlass(c);check(t.scene.draws==draws,"hidden backing cannot draw stale bounds");t.syncSearchGlass(panel);t.drawSearchGlass(c);check(t.scene.draws==draws+1&&panel.buffers.size()==1,"reopen updates backing and reuses buffers");
 System.out.println("PASS: complete search glass, live coordinates, material/colors, 720p/1080p/4K, retained resources, lifecycle guards and dismissal/reopen");}}
'''}
fakes=files.pop('io/tvos/Fakes.java')
scene,shadows=fakes.split('class Shadows',1)
files['io/tvos/Scene.java']=scene
files['io/tvos/Shadows.java']='package io.tvos;import android.graphics.*;class Shadows'+shadows
files['io/tvos/HomeSearchGlass.java']=Path('app/src/io/tvos/HomeSearchGlass.java').read_text()
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,source in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.GlassTest'],check=True)
