"""Exercise every row presentation mode with actual capsule/list code and recorded text boundaries."""
from pathlib import Path
import subprocess,tempfile
from settings_list_renderer_fixture import graphics
from settings_strings_fixture import write
harness='''package io.tvos;import android.graphics.*;import io.tvos.settings.*;import java.util.*;
public class ListRendererHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b){check(Math.abs(a-b)<.02,a+" != "+b);}
 static Canvas.Entry last(Canvas c,String kind){Canvas.Entry result=null;for(Canvas.Entry e:c.entries)if(e.kind.equals(kind))result=e;return result;}
 static int index(Canvas c,String kind){for(int i=0;i<c.entries.size();i++)if(c.entries.get(i).kind.equals(kind))return i;return -1;}
 public static void main(String[] args){
  SettingsTextRenderer text=new SettingsTextRenderer();Shadows shadows=new Shadows();
  SettingsRowRenderer capsule=new SettingsRowRenderer(shadows::draw);
  SettingsListRenderer list=new SettingsListRenderer(capsule,text,new SettingsListRenderer.Check(){public float width(float h){return .7f*h;}public void draw(Canvas c,float x,float y,float size,int ink){Paint p=new Paint();p.setColor(ink);c.add("check","",x,y,size,0,p);}},(c,x,y,k,ink)->{Paint p=new Paint();p.setColor(ink);c.add("spinner","",x,y,k,0,p);});
  Paint lp=new Paint(),vp=new Paint();SettingsListRenderer.Row row=new SettingsListRenderer.Row();
  for(float k:new float[]{2f/3,1,2})for(boolean light:new boolean[]{false,true})for(float focus:new float[]{0,.5f,1}){
   RectF bounds=new RectF(1000*k,200*k,1830*k,262*k);
   row.set("Label","Value",focus);Canvas c=new Canvas();list.draw(c,bounds,k,240*k,light,lp,vp,row);
   check(last(c,"round")!=null&&last(c,"label").text.equals("Label")&&last(c,"value").text.equals("Value"),"ordinary capsule,label,value");near(last(c,"value").l,1817*k);near(last(c,"label").t,240*k);near(bounds.left,1000*k);near(bounds.top,200*k);near(bounds.right,1830*k);
   check(last(c,"label").color==SettingsListRenderer.blend(light?0xE5162730:0xF2FFFFFF,0xFF1C1C1E,focus)&&last(c,"value").color==SettingsListRenderer.valueInk(light,focus),"theme and focus palette");
   row.disabled=true;row.fitValue=true;row.valueInset=61;c=new Canvas();list.draw(c,bounds,k,240*k,light,lp,vp,row);check(last(c,"label").color==0x66808080&&last(c,"value").color==0x66808080&&last(c,"path")==null&&text.fitted.equals("Label:Value"),"disabled VPN text and fitting without arrow");near(last(c,"value").l,1769*k);
   for(boolean after:new boolean[]{false,true}){row.set("L","V",focus);row.marker=SettingsListRenderer.Marker.CHEVRON;row.markerAfterValue=after;row.valueInset=61;c=new Canvas();list.draw(c,bounds,k,240*k,light,lp,vp,row);check((index(c,"path")>index(c,"value"))==after,"menu/editor decoration order retained");near(last(c,"value").l,1769*k);}
   for(SettingsListRenderer.Marker marker:new SettingsListRenderer.Marker[]{SettingsListRenderer.Marker.SERVICE_CHECK,SettingsListRenderer.Marker.CHOICE_CHECK,SettingsListRenderer.Marker.PICKER_CHECK}){
    row.set("Selected","",focus);row.marker=marker;row.showValue=false;if(marker==SettingsListRenderer.Marker.PICKER_CHECK)row.kind=SettingsListRenderer.Kind.PICKER;c=new Canvas();list.draw(c,bounds,k,240*k,light,lp,vp,row);Canvas.Entry check=last(c,"check");check(check!=null&&last(c,"value")==null,"selected rows hide value");near(check.t,231*k);if(light)check(check.color==SettingsListRenderer.blend(SettingsListRenderer.valueInk(true,0),0xFF1C1C1E,focus),"light theme selection check matches grey value ink at rest and focused transition");
    if(marker==SettingsListRenderer.Marker.SERVICE_CHECK){near(check.l,1787*k);near(check.r,24*k);}else{near(check.l,(1830-30-26*.7f/2)*k);near(check.r,26*k);}
    if(marker==SettingsListRenderer.Marker.PICKER_CHECK)check(text.selected&&last(c,"picker")!=null,"picker selection passed to label fitting");
   }
   row.set("Check","",focus);row.marker=SettingsListRenderer.Marker.SPINNER;c=new Canvas();list.draw(c,bounds,k,240*k,light,lp,vp,row);near(last(c,"spinner").l,1787*k);check(last(c,"spinner").color==(focus>.5f?0xFF777777:light?0xFF758390:0xFF6C7B81)&&index(c,"spinner")<index(c,"value"),"spinner ink and original stacking");
   row.set("Detail","Data",focus);row.kind=SettingsListRenderer.Kind.DETAIL;c=new Canvas();list.draw(c,bounds,k,240*k,light,lp,vp,row);check(last(c,"round")==null&&last(c,"detail")!=null,"About details have no capsule");near(last(c,"detail").l,bounds.right-30*k+20*k);check(last(c,"label").color==(light?0xE5162730:0xF2FFFFFF),"detail ignores inherited focus");
   list.beginShelf(lp);row.set("App","#1",focus);row.kind=SettingsListRenderer.Kind.SHELF;c=new Canvas();list.draw(c,bounds,k,240*k,light,lp,vp,row);check(last(c,"shelf")!=null&&last(c,"value").text.equals("#1"),"Dock rendering boundary");
   row.disabled=row.fitValue=row.markerAfterValue=true;row.showValue=false;row.valueInset=99;row.set("Reset","Plain",0);check(!row.disabled&&!row.fitValue&&!row.markerAfterValue&&row.showValue&&row.valueInset==13&&row.kind==SettingsListRenderer.Kind.NORMAL&&row.marker==SettingsListRenderer.Marker.NONE,"reused row input resets all modes and flags");
  }
  System.out.println("PASS: actual list renderer, every row kind/marker, disabled VPN, detail rows, layering, symbols, fonts boundary, input reuse, themes and 720p/1080p/4K");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);write(root);sources=graphics();sources['io/tvos/ListRendererHarness.java']=harness
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ListRendererHarness'],check=True)
