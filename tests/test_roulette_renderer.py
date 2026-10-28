"""Execute the entire strip renderer with recorded commands; no copied drawing implementation."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write as write_strings
from roulette_graphics_fixture import files
harness='''package io.tvos;
import android.graphics.*;import android.os.SystemClock;import java.util.*;
public class RouletteRenderHarness {
 final RouletteController work=new RouletteController();final Recs recs=new Recs();final Scene scene=new Scene();final Shadows shadows=new Shadows();final ActionSurface actions=new ActionSurface(scene,shadows);
 final RouletteRenderer renderer=new RouletteRenderer(work,recs,scene,shadows,actions,(c,b,r,radius)->c.add("image",String.valueOf(b.id),r.left,r.top,r.right,r.bottom,new Paint()));
 final Paint title=new Paint();
 Canvas frame(long time,float scale,float night,float button,float card){SystemClock.now=time;Canvas c=new Canvas();renderer.draw(c,scale,night,title,button,card,226*scale,788*scale);check(c.depth==0,"balanced save/restore");return c;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void near(float actual,float expected,String why){check(Math.abs(actual-expected)<.03f,why+" "+actual+" != "+expected);}
 static List<Canvas.Entry> entries(Canvas c,String kind){List<Canvas.Entry> out=new ArrayList<>();for(Canvas.Entry e:c.entries)if(e.kind.equals(kind))out.add(e);return out;}
 static Canvas.Entry text(Canvas c,String value){for(Canvas.Entry e:entries(c,"text"))if(e.text.equals(value))return e;throw new AssertionError("missing text: "+value);}
 static Canvas.Entry winner(Canvas c,int id){for(Canvas.Entry e:entries(c,"image"))if(e.text.equals(String.valueOf(id))&&Math.abs((e.l+e.r)/2-960)<.02)return e;throw new AssertionError("no central winner");}
 public static void main(String[] args){
  AppLanguage.select("en");RouletteRenderHarness h=new RouletteRenderHarness();h.title.setTextSize(31);h.title.typeface=new Object();
  Canvas c=h.frame(0,1,0,0,0);near(text(c,"🎲  Random Movie").size,40.8f,"start button text size");
  RectF bounds=new RectF();RouletteRenderer.buttonBounds(bounds,1,226,788);check(bounds.left==665&&bounds.top==444&&bounds.right==1255&&bounds.bottom==570,"shared 590x126 bounds");
  c=h.frame(0,1,1,1,0);Canvas.Entry scale=entries(c,"scale").get(0);near(scale.l,1.1f,"focused button scale");
  h.work.loading=true;c=h.frame(0,1,0,0,0);text(c,"Loading movies…");h.work.loading=false;
  AppLanguage.select("ru");c=h.frame(0,1,0,0,0);text(c,"🎲  Случайный фильм");AppLanguage.select("en");
  h.work.spin=true;c=h.frame(0,1,0,0,0);check(c.entries.isEmpty(),"empty active catalog draws no strip");
  for(int i=0;i<100;i++)h.work.picks.add(new Recs.Pick(i));h.work.winner=7;
  int[] expectedSlots={57,54,27,2,0};long[] times={0,400,2500,4500,5000};
  for(int i=0;i<times.length;i++){
   c=h.frame(times[i],1,0,0,0);check(h.work.slots.get(h.work.slots.size()-1)==expectedSlots[i],"baseline prefetch slot at "+times[i]);
   check(!entries(c,"image").isEmpty(),"visible posters throughout spin");
   Canvas.Entry layer=entries(c,"layer").get(0);check(layer.l==40&&layer.r==1880,"strip clipped to margins");
   List<Canvas.Entry> masks=entries(c,"rect");check(masks.size()==1&&masks.get(0).mode!=null,"one DST_IN edge mask");
   LinearGradient edge=(LinearGradient)masks.get(0).shader;check(Arrays.equals(edge.positions,new float[]{0,.1f,.9f,1}),"matching edge fade stops");
  }
  c=h.frame(2500,1,.5f,0,0);check(entries(c,"layer").size()>1,"directional trails while moving");
  h.work.spin=false;h.work.done=true;c=h.frame(5000,1,0,0,1);
  Canvas.Entry win=winner(c,7);near(win.r-win.l,238,"winner width");near(win.b-win.t,357,"winner height");near(win.t,263.25f,"winner baseline");
  text(c,"Movie 7");text(c,"OK – watch");text(c,"Right – spin again");check(entries(c,"glass").size()==2,"two compact glass hints");
  near(win.t-226,788-entries(c,"glass").get(0).b,"winner + title + hints have equal upper/lower gaps");
  for(float scaleValue:new float[]{2f/3,1,2})for(float top:new float[]{118,226})for(int count:new int[]{1,6,7,8}){
   float bottom=HomeLayout.dockBottom(1080*scaleValue,scaleValue)-HomeLayout.dockTileWidth(count,scaleValue)/HomeLayout.DOCK_ASPECT-80*scaleValue;
   float upper=top*scaleValue;Canvas aligned=new Canvas();h.renderer.draw(aligned,scaleValue,0,h.title,0,1,upper,bottom);
   Canvas.Entry poster=entries(aligned,"image").stream().filter(e->Math.abs((e.l+e.r)/2-960*scaleValue)<.03).findFirst().get();
   near(poster.t-upper,bottom-entries(aligned,"glass").get(0).b,"all winner blocks centered at every size/count/header");
   RouletteRenderer.buttonBounds(bounds,scaleValue,upper,bottom);near(bounds.top-upper,bottom-bounds.bottom,"idle roulette button centered in the same band");
  }
  check(entries(c,"round").stream().anyMatch(e->e.style==Paint.Style.STROKE),"winner focused rim");
  c=h.frame(5000,1,0,0,0);check(entries(c,"round").stream().noneMatch(e->e.style==Paint.Style.STROKE),"unfocused winner drops rim");near(winner(c,7).r-win.l,238,"focus does not change winner size");
  h.recs.missing=true;c=h.frame(5000,1,1,0,0);check(entries(c,"image").isEmpty()&&entries(c,"round").size()>0,"missing posters have dark placeholders");
  c=h.frame(5000,.5f,1,0,1);check(entries(c,"shadow").get(0).blur==15,"shadow adapts to screen scale");
  h.work.spin=true;h.work.done=false;h.recs.missing=false;h.frame(2000,1,0,0,0);int allocations=LinearGradient.allocations;
  h.frame(2000,1,1,0,0);check(LinearGradient.allocations==allocations,"same-size edge shader reused across theme frames");
  h.frame(2000,.75f,1,0,0);check(LinearGradient.allocations==allocations+1,"edge gradient rebuilt once on resize");
  check(h.title.getTextSize()==31&&h.title.shader==null,"host title paint remains unchanged");
  System.out.println("PASS: complete roulette renderer, start/loading/RU/EN, baseline motion slots, clips/trails/masks, winner focus/hints, placeholders, theme and resize scratch reuse");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-roulette-renderer-') as tmp:
 root=Path(tmp);write_strings(root);sources=files();sources['io/tvos/RouletteRenderHarness.java']=harness
 for name,value in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RouletteRenderHarness'],check=True)
