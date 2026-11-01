"""Execute settings typography and tile caching independently of launcher/screen state."""
from pathlib import Path
import subprocess, tempfile
sources={
'android/graphics/Typeface.java':'package android.graphics;public class Typeface {}',
'android/graphics/Paint.java':'''package android.graphics;public class Paint {
 public static final int ANTI_ALIAS_FLAG=1;public enum Align{LEFT,CENTER,RIGHT}public static class FontMetrics{public float ascent,descent;}
 public static int allocations;public float size;public int color;public Align align=Align.LEFT;public Typeface face;public Shader shader;
 public Paint(){allocations++;}public Paint(int flags){this();}public void set(Paint p){size=p.size;color=p.color;align=p.align;face=p.face;shader=p.shader;}
 public void setTextSize(float v){size=v;}public float getTextSize(){return size;}public void setTextAlign(Align a){align=a;}public Align getTextAlign(){return align;}
 public void setTypeface(Typeface t){face=t;}public void setColor(int c){color=c;}public void setShader(Shader s){shader=s;}
 public float measureText(String s){return s.codePointCount(0,s.length())*size/2;}public float measureText(String s,int a,int b){return measureText(s.substring(a,b));}
 public void getFontMetrics(FontMetrics m){m.ascent=-size*.75f;m.descent=size*.25f;}public void getTextPath(String s,int a,int b,float x,float y,Path p){p.revisions++;}
}''',
'android/graphics/Path.java':'package android.graphics;public class Path {public int revisions;public void reset(){}}',
'android/graphics/Matrix.java':'package android.graphics;public class Matrix {public void setScale(float x,float y){}public void postTranslate(float x,float y){}}',
'android/graphics/Shader.java':'package android.graphics;public class Shader {public enum TileMode{CLAMP}public void setLocalMatrix(Matrix m){}}',
'android/graphics/LinearGradient.java':'package android.graphics;public class LinearGradient extends Shader {public LinearGradient(float a,float b,float c,float d,int e,int f,TileMode t){}}',
'android/graphics/RectF.java':'package android.graphics;public class RectF {public float left,top,right,bottom;public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}public float width(){return right-left;}public float height(){return bottom-top;}public float centerX(){return (left+right)/2;}public float centerY(){return (top+bottom)/2;}}',
'android/graphics/Canvas.java':'''package android.graphics;import java.util.*;public class Canvas {
 public List<String> glyphs=new ArrayList<>();public List<Float> xs=new ArrayList<>();public int depth,color,revisions;public Shader shader;public float left,top,right,bottom,radius,tx,ty;
 public void drawText(String s,int a,int b,float x,float y,Paint p){glyphs.add(s.substring(a,b));xs.add(x);if(p.align!=Paint.Align.LEFT)throw new AssertionError("glyph alignment");}
 public void save(){depth++;}public void restore(){depth--;}public void translate(float x,float y){tx=x;ty=y;}public void clipOutPath(Path p){revisions=p.revisions;}
 public void drawRoundRect(RectF r,float x,float y,Paint p){left=r.left;top=r.top;right=r.right;bottom=r.bottom;radius=x;color=p.color;shader=p.shader;}
}''',
'android/text/TextPaint.java':'package android.text;public class TextPaint extends android.graphics.Paint {public TextPaint(int flags){super(flags);}}',
'android/text/Layout.java':'package android.text;public class Layout {public enum Alignment{ALIGN_CENTER}}',
'android/text/StaticLayout.java':'''package android.text;import android.graphics.Canvas;public class StaticLayout {
 public static int builds;public static float size;public static int color,width;final TextPaint paint;
 public StaticLayout(String s,TextPaint p,int w,Layout.Alignment a,float spacing,float add,boolean padding){builds++;paint=p;size=p.size;width=w;if(spacing!=1.18f||add!=0||padding||a!=Layout.Alignment.ALIGN_CENTER)throw new AssertionError("wrapping changed");}
 public void draw(Canvas c){color=paint.color;}
}''',
'external/PresentationTest.java':'''package external;import io.tvos.settings.*;import android.graphics.*;import android.text.*;
public class PresentationTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b){check(Math.abs(a-b)<.001,a+" != "+b);}
 public static void main(String[] args){
  SettingsTextRenderer text=new SettingsTextRenderer();Typeface face=new Typeface();Paint paint=new Paint();paint.setTextSize(20);paint.setColor(123);paint.setTypeface(face);
  for(Paint.Align align:Paint.Align.values()){
   paint.setTextAlign(align);Canvas c=new Canvas();text.drawTracked(c,"A😀Б",100,80,paint,.1f);check(c.glyphs.size()==3&&c.glyphs.get(1).equals("😀"),"Unicode code points remain intact");
   near(c.xs.get(0),align==Paint.Align.LEFT?100:align==Paint.Align.CENTER?83:66);near(c.xs.get(1)-c.xs.get(0),12);check(paint.align==align&&paint.color==123&&paint.face==face,"caller Paint preserved");
  }
  Canvas c=new Canvas();int allocations=Paint.allocations;for(int i=0;i<100;i++)text.drawTracked(c,"abc",0,0,paint,.02f);check(Paint.allocations==allocations,"no Paint allocations in steady text drawing");
  c=new Canvas();text.drawTracked(c,null,0,0,paint,.02f);text.drawTracked(c,"",0,0,paint,.02f);check(c.glyphs.isEmpty(),"empty text no-op");
  text.drawDescription(c,"Info",1,true,face);int builds=StaticLayout.builds;near(c.tx,152);near(c.ty,837);check(StaticLayout.width==740&&StaticLayout.color==0xB33A5761,"description geometry and light color");
  for(int i=0;i<100;i++)text.drawDescription(c,"Info",1,false,face);check(StaticLayout.builds==builds&&StaticLayout.color==0xFF888D90&&c.depth==0,"theme refresh reuses layout and balances canvas");
  text.drawDescription(c,"Info",1.0001f,false,face);check(StaticLayout.builds==++builds&&StaticLayout.width==740,"fractional scale invalidates even when rounded width is unchanged");near(StaticLayout.size,30.003f);
  text.drawDescription(c,"Info",1.0001f,false,new Typeface());check(StaticLayout.builds==++builds,"font change invalidates layout");
  text.clear();text.clear();text.drawDescription(c,"Info",1,true,face);check(StaticLayout.builds==++builds,"lifecycle clear rebuilds once");text.drawDescription(c,null,1,true,face);check(StaticLayout.builds==builds,"absent description no layout");
  SettingsLogoRenderer logo=new SettingsLogoRenderer();
  for(float k:new float[]{2f/3,1,2}){
   c=new Canvas();logo.draw(c,k,true,face);near(c.left,254*k);near(c.top,236*k);near(c.right,790*k);near(c.bottom,772*k);near(c.radius,116*k);check(c.shader==null&&c.color==0xFF8897AC&&c.depth==0,"light cutout tile and balanced canvas");
   int revision=c.revisions;allocations=Paint.allocations;for(int i=0;i<100;i++)logo.draw(c,k,false,face);check(c.revisions==revision&&Paint.allocations==allocations&&c.shader!=null,"theme toggle and steady frames reuse path, gradient and paints");
   logo.draw(c,k,false,new Typeface());check(c.revisions==revision+1,"font change rebuilds logo path at same scale");
  }
  System.out.println("PASS: independent settings renderers, Unicode/alignment, reusable paints/layout/path, theme/scale/font invalidation and tile geometry at 720p/1080p/4K");
 }
}'''}
for name in ['SettingsTextRenderer','SettingsLogoRenderer']:
 source=Path('app/src/io/tvos/settings',name+'.java').read_text()
 assert 'import io.tvos.' not in source
 sources['io/tvos/settings/'+name+'.java']=source
activity=Path('app/src/io/tvos/SettingsActivity.java').read_text()
assert 'textRenderer.clear();' in activity
for name in ['subtitle','rowSub','spinner','warn','logoInk','descriptionLayout','trackedGlyphPaint']:
 import re
 assert not re.search(r'\b'+name+r'\b',re.sub(r'//[^\n]*|/\*.*?\*/', '', activity, flags=re.S)),name
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'external.PresentationTest'],check=True)
