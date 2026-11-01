"""The actual geometry component and small Android measurement boundaries."""
from pathlib import Path

def write(root, android=False):
 sources={
 'io/tvos/home/HomeGeometry.java':Path('app/src/io/tvos/home/HomeGeometry.java').read_text(),
 'io/tvos/home/HomeLayout.java':Path('app/src/io/tvos/home/HomeLayout.java').read_text()}
 if android:
  sources.update({
   'android/graphics/Typeface.java':'package android.graphics;public class Typeface {public float factor=1;}',
   'android/graphics/RectF.java':'''package android.graphics;public class RectF {public float left,top,right,bottom;public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}public void setEmpty(){set(0,0,0,0);}public float width(){return right-left;}public float height(){return bottom-top;}}''',
   'android/graphics/Paint.java':'''package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1;public static int allocations;public float size;public Typeface typeface;public Paint(){allocations++;}public Paint(int f){this();}public void setTypeface(Typeface t){typeface=t;}public void setTextSize(float s){size=s;}public static class FontMetrics {public static int allocations;public float descent,ascent;public FontMetrics(){allocations++;}}public void getFontMetrics(FontMetrics f){f.descent=5;f.ascent=-25;}public float descent(){return 5;}public float measureText(String s){return s.length()*15*(typeface==null?1:typeface.factor);}}'''})
 for name,body in sources.items():
  if name=='io/tvos/home/HomeLayout.java' and (root/'HomeLayout.java').exists():continue
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
