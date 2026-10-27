"""Production profile composition and focused-button rendering with recording graphics adapters."""
from pathlib import Path
import re,subprocess,tempfile
from settings_strings_fixture import write
from tmdb_metadata_fixture import block
from home_renderers_fixture import files
source=Path('app/src/io/tvos/ProfileActivity.java').read_text()
positions='\n'.join(re.search(r'        private final float\[\] '+name+r' = \{[^;]+;',source).group(0) for name in ['xs','ys','radii'])
methods='\n'.join(block(source,sig) for sig in ['        private void avatarRect(', '        private void drawButton(', '        private String buttonLabel('])
harness='''package io.tvos;
import android.graphics.*;import java.util.*;
public class ProfileCompositionHarness {
 final Scene scene=new Scene();final ActionSurface surface=new ActionSurface(scene,new Shadows());
 final Spring[] buttons={new Spring(.26f,.85f,1),new Spring(.26f,.85f,0)},avatarFocus=new Spring[18];final Spring chooser=new Spring(.38f,1,0);
 final RectF[] buttonRects={new RectF(270,650,830,722),new RectF(270,723,830,795)};
 final Paint buttonText=Ui.text(Ui.displayMedium,28,0xFFFFFFFF);int selected;boolean night;
'''+positions+methods+'''
 ProfileCompositionHarness(){for(int i=0;i<18;i++)avatarFocus[i]=new Spring(.26f,.85f,0);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.03,why+" "+a+" != "+b);}
 static List<Canvas.Entry> entries(Canvas c,String kind){List<Canvas.Entry> out=new ArrayList<>();for(Canvas.Entry e:c.entries)if(e.kind.equals(kind))out.add(e);return out;}
 public static void main(String[] args){
  ProfileCompositionHarness h=new ProfileCompositionHarness();RectF r=new RectF();
  for(int selected=0;selected<18;selected++)for(int page=0;page<2;page++){
   h.selected=selected;int center=selected/9==page?selected%9:0;List<RectF> bubbles=new ArrayList<>();
   for(int i=0;i<9;i++){RectF b=new RectF();h.avatarRect(page*9+i,b);bubbles.add(b);check(b.left>=1000&&b.right<=1880&&b.top>=100&&b.bottom<=920,"all bubbles remain in composition/transition bounds");}
   RectF main=bubbles.get(center);near(main.centerX(),1459,"selected avatar stays central");near(main.centerY(),544,"selected avatar center height");near(main.width(),352,"central avatar diameter");
   for(int i=0;i<9;i++)for(int j=i+1;j<9;j++)if(i!=center&&j!=center){RectF a=bubbles.get(i),b=bubbles.get(j);float gap=(float)Math.hypot(a.centerX()-b.centerX(),a.centerY()-b.centerY())-(a.width()+b.width())/2;check(gap>18,"satellites keep clear spacing");}
   h.chooser.snap(1);for(int i=0;i<9;i++){h.avatarRect(page*9+i,r);near(r.centerX(),1248+(i%3)*230,"chooser retains 3 columns");near(r.centerY(),320+(i/3)*230,"chooser retains 3 rows");near(r.width(),164,"chooser radius unchanged");}h.chooser.snap(0);
  }
  // In the reference selection, robot, woman, panda and clapper occupy their visual roles.
  h.selected=3;h.avatarRect(0,r);near(r.centerX(),1229,"robot upper left");near(r.width(),200,"robot size");h.avatarRect(2,r);near(r.centerY(),276,"woman above center");h.avatarRect(6,r);near(r.width(),240,"large lower-left panda");h.avatarRect(8,r);near(r.width(),76,"small upper clapper");
  for(boolean night:new boolean[]{false,true})for(String lang:new String[]{"ru","en"}){
   AppLanguage.select(lang);h.night=night;h.buttons[1].snap(0);Canvas c=new Canvas();h.drawButton(c,1);check(entries(c,"glass").isEmpty()&&entries(c,"shadow").isEmpty()&&entries(c,"round").isEmpty(),"inactive button is text only in both themes/languages");check(entries(c,"text").size()==1&&c.depth==0,"inactive label stays visible");
   h.buttons[1].snap(.5f);c=new Canvas();h.drawButton(c,1);check(entries(c,"layer").size()==1&&entries(c,"layer").get(0).alpha==128,"surface fades with the existing focus spring");near(entries(c,"scale").get(0).l,1.05f,"intermediate focus scale retained");check(c.depth==0,"focus fade restores canvas");
   h.buttons[1].snap(1);c=new Canvas();h.drawButton(c,1);check(entries(c,"layer").isEmpty(),"settled focused button needs no fade layer");check(!entries(c,"round").isEmpty()&&!entries(c,"shadow").isEmpty(),"focused surface and shadow retained");near(entries(c,"scale").get(0).l,1.1f,"full focus scale retained");
  }
  System.out.println("PASS: actual profile composition for every selected avatar/page, spaced satellites, unchanged chooser grid, text-only inactive buttons, both themes/locales and preserved focus rendering");
 }
}'''
assert 'heading.setTextSize(80)' in source and 'body.setTextSize(38)' in source
with tempfile.TemporaryDirectory(prefix='tvos-profile-composition-') as tmp:
 root=Path(tmp);write(root);sources=files();sources['io/tvos/ProfileCompositionHarness.java']=harness
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ProfileCompositionHarness'],check=True)
