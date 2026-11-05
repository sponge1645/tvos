"""Run the actual settings row paint path with recorded shadow geometry and blur-mask consistency."""
from pathlib import Path
import re,subprocess,tempfile
from settings_strings_fixture import write
from home_renderers_fixture import files
from settings_list_renderer_fixture import add as add_list_renderer
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
renderer=Path('app/src/io/tvos/settings/SettingsRowRenderer.java').read_text()
assert 'import io.tvos.' not in renderer
for signature in ['        private void drawMenu(', '        private void drawChoice(', '        private void drawOrderEditor(', '        private void drawShelfEditor(', '        private void drawPicker(', '        private void drawList(']:
 assert 'paintRow(' in block(source,signature),signature
assert 'drawOrderEditor(c, true)' in block(source, '        private void drawMenuButtonEditor(')
assert 'drawOrderEditor(c, false)' in block(source, '        private void drawCategoryEditor(')
harness='''package io.tvos;import android.graphics.*;import java.util.*;
public class SettingsShadowHarness {
 float k=1;boolean light;final Spring[] pop=new Spring[12];{for(int i=0;i<pop.length;i++)pop[i]=new Spring(.28f,.85f,0);}final RectF rowRect=new RectF(),tmp=new RectF();
 final Shadows rowShadows=new Shadows();final io.tvos.settings.SettingsRowRenderer rowRenderer=new io.tvos.settings.SettingsRowRenderer(rowShadows::draw);boolean lightSettings(){return light;}
 float drawApprovedCapsule(Canvas c,int i){return rowRenderer.draw(c,rowRect,k,light,i>=0&&i<pop.length?pop[i].value:0);}
 static int paintRow(int p,int first,int end,int active){return io.tvos.settings.SettingsListRenderer.paintRow(p,first,end,active);}
 int valueInk(float f){return io.tvos.settings.SettingsListRenderer.valueInk(light,f);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.03,why+" "+a+" != "+b);}
 static List<Canvas.Entry> entries(Canvas c,String kind){List<Canvas.Entry> out=new ArrayList<>();for(Canvas.Entry e:c.entries)if(e.kind.equals(kind))out.add(e);return out;}
 public static void main(String[] args){SettingsShadowHarness h=new SettingsShadowHarness();
  for(float k:new float[]{2f/3,1,2})for(boolean light:new boolean[]{false,true})for(float f:new float[]{0,.5f,1}){
   h.k=k;h.light=light;h.pop[0].snap(f);h.rowRect.set(1000*k,200*k,1824*k,272*k);Canvas c=new Canvas();near(h.drawApprovedCapsule(c,0),f,"focus state retained");near(h.rowRect.left,1000*k,"label bounds never mutated");near(h.rowRect.top,200*k,"logical focus bounds retained");near(h.rowRect.right,1824*k,"logical row width retained");
   check(entries(c,"round").get(0).color==(light?0xFFC9E4F7:0xFF31444B),"theme-specific unfocused capsule color");
   if(f==0){check(entries(c,"shadow").isEmpty(),"no focus shadow at rest");continue;}
   Canvas.Entry shadow=entries(c,"shadow").get(0),pill=entries(c,"round").get(1);near(shadow.t-pill.t,15*k*f,"shadow points down");near(shadow.l,pill.l,"no horizontal displacement");near(shadow.blur,40*k,"requested blur in both themes/scales");check((shadow.color>>>24)==Math.round(255*.4f*f),"focus-weighted 40 percent opacity");near(pill.r-pill.l,(824*.9f+36)*k*(1+.012f*f),"focused pill geometry retained");
  }
  for(float f:new float[]{0,.25f,1}){
   Canvas setup=new Canvas();h.rowRenderer.drawFocused(setup,h.rowRect,1,true,f);
   check(entries(setup,"round").size()==(f==0?0:1),"setup draws no inactive base capsule");
   if(f>0)check(entries(setup,"round").get(0).alpha==Math.round(255*f),"setup capsule alpha follows incoming focus");
  }
  for(boolean light:new boolean[]{false,true}){h.light=light;check(h.valueInk(0)==(light?0xFF758390:0xFF999999),"unfocused value palette");check(h.valueInk(1)==0xFF999999,"focused value palette");}
  h.light=false;for(float f:new float[]{0,.25f,.5f,.75f,1})check(h.valueInk(f)==0xFF999999,"dark values stay gray throughout focus transitions");
  h.k=1;
  for(int count=1;count<=12;count++)for(int active=0;active<count;active++){
   for(int i=0;i<12;i++)h.pop[i].snap(i==active?1:0);Canvas c=new Canvas();Set<Integer> seen=new HashSet<>();
   for(int pass=0;pass<count;pass++){int row=paintRow(pass,0,count,active);check(seen.add(row),"each row drawn once");h.rowRect.set(1000,row*84,1824,row*84+72);h.drawApprovedCapsule(c,row);}
   check(seen.size()==count,"all rows drawn");Canvas.Entry pill=c.entries.get(c.entries.size()-1),shadow=c.entries.get(c.entries.size()-2);check(pill.kind.equals("round")&&pill.color==0xFFF5F5F7&&shadow.kind.equals("shadow"),"active shadow and pill composite after every other button");near(pill.t,active*84-5-82*.012f/2,"last pill belongs to selected logical row, not last list item");
  }
  for(int first=0;first<20;first++)for(int count=0;count<=14;count++)for(int active=first-1;active<=first+count;active++){
   Set<Integer> seen=new HashSet<>();int last=-1,previous=-1;for(int pass=0;pass<count;pass++){int row=paintRow(pass,first,first+count,active);check(row>=first&&row<first+count&&seen.add(row),"scrolled/overscan range covered once");if(row!=active){check(row>previous,"nonfocused order retained");previous=row;}last=row;}
   if(count>0&&active>=first&&active<first+count)check(last==active,"absolute selected row last in scrolled lists");
  }
  for(float k:new float[]{2f/3,1,2}){
   h.k=k;h.rowRect.set(1000*k,200*k,1824*k,272*k);Canvas c=new Canvas();h.rowRenderer.drawChevron(c,h.rowRect,k);
   Canvas.Entry translation=entries(c,"translate").get(0),path=entries(c,"path").get(0);near(translation.l,1811*k,"chevron right inset");near(translation.t,236*k,"chevron centered");near(path.stroke,3.4425f*k,"chevron stroke");check(path.color==0xFFA1A3A1&&c.depth==0,"chevron color and balanced Canvas");
   String revision=path.text;int filters=BlurMaskFilter.allocations;for(int i=0;i<100;i++){Canvas next=new Canvas();h.rowRenderer.draw(next,h.rowRect,k,true,1);h.rowRenderer.drawChevron(next,h.rowRect,k);check(entries(next,"path").get(0).text.equals(revision),"steady frames retain chevron path");}
   check(BlurMaskFilter.allocations==filters,"steady frames retain blur filter");
  }
  System.out.println("PASS: actual settings row shadow direction, 40px blur/mask consistency, 40% opacity, active-last stacking across menus/scroll buffers, value palette, preserved pill geometry, themes and 720p/1080p/4K");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-settings-shadow-') as tmp:
 root=Path(tmp);write(root);sources=add_list_renderer(files())
 sources['android/graphics/Shader.java']=sources['android/graphics/Shader.java'].replace('public enum','public void setLocalMatrix(Matrix m){}public enum')
 sources['android/graphics/Matrix.java']=sources['android/graphics/Matrix.java'].replace('public void setScale','public void setSinCos(float a,float b){}public void postScale(float a,float b){}public void setScale')
 sources['io/tvos/settings/SettingsRowRenderer.java']=renderer
 sources['android/graphics/BlurMaskFilter.java']=sources['android/graphics/BlurMaskFilter.java'].replace('public final float radius;', 'public static int allocations;public final float radius;').replace('this.radius=radius;', 'this.radius=radius;allocations++;')
 sources['android/graphics/Paint.java']=sources['android/graphics/Paint.java'].replace('public enum Cap', 'public enum Join {ROUND}public void setStrokeJoin(Join j){}public enum Cap')
 sources['io/tvos/SettingsShadowHarness.java']=harness
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SettingsShadowHarness'],check=True)
