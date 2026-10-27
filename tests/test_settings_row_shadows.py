"""Run the actual settings row paint path with recorded shadow geometry and blur-mask consistency."""
from pathlib import Path
import re,subprocess,tempfile
from settings_strings_fixture import write
from home_renderers_fixture import files
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
constants=re.search(r'private static final float SETTINGS_SHADOW_BLUR[^;]+;',source).group(0)
assert 'shadow.setMaskFilter(new BlurMaskFilter(SETTINGS_SHADOW_BLUR * k' in source
for signature in ['        private void drawMenu(', '        private void drawChoice(', '        private void drawMenuButtonEditor(', '        private void drawCategoryEditor(', '        private void drawShelfEditor(', '        private void drawPicker(', '        private void drawList(']:
 assert 'paintRow(' in block(source,signature),signature
harness='''package io.tvos;import android.graphics.*;import java.util.*;
public class SettingsShadowHarness {
 '''+constants+'''
 float k=1;boolean light;final Spring[] pop=new Spring[12];{for(int i=0;i<pop.length;i++)pop[i]=new Spring(.28f,.85f,0);}final RectF rowRect=new RectF(),tmp=new RectF();
 final Paint capsule=new Paint(),shadow=new Paint(),white=new Paint();final Shadows rowShadows=new Shadows();boolean lightSettings(){return light;}
 '''+''.join(block(source,sig) for sig in ['        private float drawRowBackground(', '        private static int paintRow(', '        private int secondaryInk(', '        private int valueInk(', '        private static int blend('])+'''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.03,why+" "+a+" != "+b);}
 static List<Canvas.Entry> entries(Canvas c,String kind){List<Canvas.Entry> out=new ArrayList<>();for(Canvas.Entry e:c.entries)if(e.kind.equals(kind))out.add(e);return out;}
 public static void main(String[] args){SettingsShadowHarness h=new SettingsShadowHarness();
  for(float k:new float[]{2f/3,1,2})for(boolean light:new boolean[]{false,true})for(float f:new float[]{0,.5f,1}){
   h.k=k;h.light=light;h.pop[0].snap(f);h.rowRect.set(1000*k,200*k,1824*k,272*k);h.shadow.setMaskFilter(new BlurMaskFilter(SETTINGS_SHADOW_BLUR*k,BlurMaskFilter.Blur.NORMAL));Canvas c=new Canvas();near(h.drawRowBackground(c,0),f,"focus state retained");
   check(entries(c,"round").get(0).color==(light?0xFFD1E3FB:0xFF31444B),"theme-specific unfocused capsule color");
   if(f==0){check(entries(c,"shadow").isEmpty(),"no focus shadow at rest");continue;}
   Canvas.Entry shadow=entries(c,"shadow").get(0),pill=entries(c,"round").get(1);near(shadow.t-pill.t,15*k*f,"shadow points down");near(shadow.l,pill.l,"no horizontal displacement");near(shadow.blur,40*k,"requested blur in both themes/scales");check((shadow.color>>>24)==Math.round(255*.4f*f),"focus-weighted 40 percent opacity");near(pill.r-pill.l,824*k*(1+.012f*f),"focused pill geometry retained");
  }
  for(boolean light:new boolean[]{false,true}){h.light=light;check(h.valueInk(0)==(light?0xFF758390:0xFF999999),"unfocused value palette");check(h.valueInk(1)==0xFF999999,"focused value palette");}
  h.light=false;for(float f:new float[]{0,.25f,.5f,.75f,1})check(h.valueInk(f)==0xFF999999,"dark values stay gray throughout focus transitions");
  h.k=1;h.shadow.setMaskFilter(new BlurMaskFilter(SETTINGS_SHADOW_BLUR,BlurMaskFilter.Blur.NORMAL));h.white.setColor(0xFFF5F5F7);
  for(int count=1;count<=12;count++)for(int active=0;active<count;active++){
   for(int i=0;i<12;i++)h.pop[i].snap(i==active?1:0);Canvas c=new Canvas();Set<Integer> seen=new HashSet<>();
   for(int pass=0;pass<count;pass++){int row=paintRow(pass,0,count,active);check(seen.add(row),"each row drawn once");h.rowRect.set(1000,row*84,1824,row*84+72);h.drawRowBackground(c,row);}
   check(seen.size()==count,"all rows drawn");Canvas.Entry pill=c.entries.get(c.entries.size()-1),shadow=c.entries.get(c.entries.size()-2);check(pill.kind.equals("round")&&pill.color==0xFFF5F5F7&&shadow.kind.equals("shadow"),"active shadow and pill composite after every other button");near(pill.t,active*84-72*.012f/2,"last pill belongs to selected logical row, not last list item");
  }
  for(int first=0;first<20;first++)for(int count=0;count<=14;count++)for(int active=first-1;active<=first+count;active++){
   Set<Integer> seen=new HashSet<>();int last=-1,previous=-1;for(int pass=0;pass<count;pass++){int row=paintRow(pass,first,first+count,active);check(row>=first&&row<first+count&&seen.add(row),"scrolled/overscan range covered once");if(row!=active){check(row>previous,"nonfocused order retained");previous=row;}last=row;}
   if(count>0&&active>=first&&active<first+count)check(last==active,"absolute selected row last in scrolled lists");
  }
  System.out.println("PASS: actual settings row shadow direction, 40px blur/mask consistency, 40% opacity, active-last stacking across menus/scroll buffers, value palette, preserved pill geometry, themes and 720p/1080p/4K");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-settings-shadow-') as tmp:
 root=Path(tmp);write(root);sources=files()
 sources['android/graphics/Shader.java']=sources['android/graphics/Shader.java'].replace('public enum','public void setLocalMatrix(Matrix m){}public enum')
 sources['android/graphics/Matrix.java']=sources['android/graphics/Matrix.java'].replace('public void setScale','public void setSinCos(float a,float b){}public void postScale(float a,float b){}public void setScale')
 sources['io/tvos/SettingsShadowHarness.java']=harness
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SettingsShadowHarness'],check=True)
