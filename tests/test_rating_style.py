"""Execute the production rating-style cache with counted Android graphics constructors."""
from pathlib import Path
import subprocess,tempfile

view=Path('app/src/io/tvos/HomeView.java').read_text()
home=Path('app/src/io/tvos/PosterRenderer.java').read_text()
assert 'new RatingStyle(capacity)' in home
assert 'ratingText.setTypeface(ratingStyle.bold(lCardMeta.getTypeface()));' in home
assert 'ratingStyle.sheen(i, badge.left, badge.top, badge.bottom,' in home
assert 'blend(0x20FFFFFF, 0x32FFFFFF, night)' in home
pause=home[home.index('    void clear()'):home.index('    boolean focus(')]
assert 'ratingStyle.clear();' in pause

fixtures={
'android/graphics/Shader.java':'''package android.graphics;public class Shader {public enum TileMode {CLAMP,REPEAT}}''',
'android/graphics/LinearGradient.java':'''package android.graphics;public class LinearGradient extends Shader {
 public static int created;public final float x0,y0,x1,y1;public final int c0,c1;public final TileMode mode;
 public LinearGradient(float a,float b,float c,float d,int e,int f,TileMode m){created++;x0=a;y0=b;x1=c;y1=d;c0=e;c1=f;mode=m;}
}''',
'android/graphics/Typeface.java':'''package android.graphics;public class Typeface {
 public static final int BOLD=1;public static int calls;public final Typeface source;public final int style;
 public Typeface(){source=null;style=0;}private Typeface(Typeface base,int s){source=base;style=s;}
 public static Typeface create(Typeface base,int style){calls++;return new Typeface(base,style);}
}''',
'io/tvos/RatingStyle.java':Path('app/src/io/tvos/artwork/RatingStyle.java').read_text(),
'io/tvos/RatingStyleHarness.java':'''package io.tvos;import io.tvos.artwork.RatingStyle;
import android.graphics.*;
public class RatingStyleHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void gradient(LinearGradient g,float x,float a,float b,int color){
  check(g.x0==x&&g.x1==x&&g.y0==a&&g.y1==b,"exact original gradient coordinates");
  check(g.c0==color&&g.c1==0x00FFFFFF&&g.mode==Shader.TileMode.CLAMP,"original colors/alpha/tile mode");
 }
 public static void main(String[] args){
  RatingStyle cache=new RatingStyle(8);Typeface base=new Typeface(),bold=cache.bold(base);
  check(bold.source==base&&bold.style==Typeface.BOLD,"same bold derivation as original");
  LinearGradient[] original=new LinearGradient[8];
  for(int i=0;i<8;i++){
   original[i]=cache.sheen(i,80+i*200,900,936,0x20FFFFFF);
   gradient(original[i],80+i*200,900,936,0x20FFFFFF);
  }
  for(int frame=0;frame<10000;frame++)for(int i=0;i<8;i++){
   check(cache.bold(base)==bold,"unchanged base reuses bold face");
   check(cache.sheen(i,80+i*200,900,936,0x20FFFFFF)==original[i],"interleaved cards reuse their own sheen");
  }
  check(Typeface.calls==1&&LinearGradient.created==8,"steady frames perform no new font/gradient construction");
  LinearGradient night=cache.sheen(2,480,900,936,0x32FFFFFF);
  check(night!=original[2],"night color invalidates its slot");gradient(night,480,900,936,0x32FFFFFF);
  check(cache.sheen(1,280,900,936,0x20FFFFFF)==original[1],"another slot is unaffected");
  LinearGradient dawn=cache.sheen(2,480,900,936,0x29FFFFFF);
  check(dawn!=night,"intermediate transition color invalidates");gradient(dawn,480,900,936,0x29FFFFFF);
  check(cache.sheen(2,480,900,936,0x29FFFFFF)==dawn,"same quantized transition color reuses gradient");
  LinearGradient x=cache.sheen(2,481.25f,900,936,0x29FFFFFF);check(x!=dawn,"fractional horizontal move invalidates");
  LinearGradient top=cache.sheen(2,481.25f,899.5f,936,0x29FFFFFF);check(top!=x,"font ascent/size change invalidates");
  LinearGradient bottom=cache.sheen(2,481.25f,899.5f,940.75f,0x29FFFFFF);check(bottom!=top,"descent/scale change invalidates");
  gradient(bottom,481.25f,899.5f,940.75f,0x29FFFFFF);
  Typeface replacement=new Typeface(),newBold=cache.bold(replacement);
  check(newBold!=bold&&newBold.source==replacement&&Typeface.calls==2,"changed font base invalidates");
  Typeface fallback=cache.bold(null);check(fallback.source==null&&Typeface.calls==3,"system/default face supported");
  check(cache.bold(null)==fallback&&Typeface.calls==3,"default face cached too");
  cache.clear();check(cache.bold(null)!=fallback&&Typeface.calls==4,"pause clears prepared face");
  int count=LinearGradient.created;
  for(int i=0;i<8;i++){
   LinearGradient rebuilt=cache.sheen(i,80+i*200,900,936,0x20FFFFFF);
   check(rebuilt!=original[i],"pause clears every prepared slot");gradient(rebuilt,80+i*200,900,936,0x20FFFFFF);
  }
  check(LinearGradient.created==count+8,"only visible slots rebuilt after pause");
  check(Typeface.calls==4,"geometry changes never rebuild font");
  System.out.println("PASS: real rating cache, 80000 interleaved hits, exact shader parameters, day/night/scale/font invalidation and pause");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='tvos-rating-style-') as tmp:
 root=Path(tmp)
 for name,content in fixtures.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RatingStyleHarness'],check=True)
