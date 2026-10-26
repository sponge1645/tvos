"""Execute inline updater presentation and its real ETA/plural/graphics decisions, without live downloads."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write
from home_renderers_fixture import files
sources=files()
sources['android/graphics/Canvas.java']=sources['android/graphics/Canvas.java'].replace('public void translate(float x,float y)', 'public void drawLine(float a,float b,float c,float d,Paint p){add("line","",a,b,c,d,p);}public void rotate(float a){rotate(a,0,0);}public void translate(float x,float y)')
sources['io/tvos/SelfUpdate.java']='package io.tvos;class SelfUpdate {static final int DOWNLOADING=4;}'
sources['io/tvos/SettingsUpdateProgress.java']=Path('app/src/io/tvos/SettingsUpdateProgress.java').read_text()
sources['io/tvos/UpdateProgressHarness.java']='''package io.tvos;
import android.graphics.*;import android.os.SystemClock;import java.util.*;
public class UpdateProgressHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.03,why+" "+a+" != "+b);}
 static List<Canvas.Entry> entries(Canvas c,String kind){List<Canvas.Entry> out=new ArrayList<>();for(Canvas.Entry e:c.entries)if(e.kind.equals(kind))out.add(e);return out;}
 public static void main(String[] args){
  for(String lang:new String[]{"ru","en"}){
   AppLanguage.select(lang);boolean ru=lang.equals("ru");SettingsUpdateProgress p=new SettingsUpdateProgress();check(!p.active(),"idle progress hidden");
   p.observe(4,-1,0);check(p.active()&&p.label(0).equals(ru?"Загрузка обновления…":"Downloading update…"),"unknown size state");
   p.observe(4,10,1000);check(p.label(1000).equals(ru?"Загрузка: 10%":"Downloading: 10%"),"early actual percentage without fabricated estimate");
   p.observe(4,50,100_000);check(p.label(100_000).equals(ru?"Загрузка: примерно 2 минуты до завершения":"Downloading: About 2 minutes remaining"),"estimate from actual percent and elapsed time");
   p.observe(4,90,101_000);check(p.label(101_000).contains(ru?"меньше минуты":"less than a minute"),"short ETA");
   for(float k:new float[]{2f/3,1,2}){SystemClock.now=102_000;Canvas c=new Canvas();p.draw(c,k,false);List<Canvas.Entry> rails=entries(c,"round");near(rails.get(0).l,342*k,"rail left");near(rails.get(0).r,702*k,"rail right");near(rails.get(0).b-rails.get(0).t,10*k,"thin 10px rail");near((rails.get(1).r-rails.get(1).l)/(rails.get(0).r-rails.get(0).l),.9f,"real 90 percent fill");near(entries(c,"text").get(0).size,30*k,"Semibold 30 caption size");}
   Canvas spin=new Canvas();p.drawSpinner(spin,1800,250,1,0xFF777777,false);check(entries(spin,"line").size()==12&&spin.depth==0,"12 petals and balanced transforms");int first=entries(spin,"line").get(0).color;SystemClock.now+=80;Canvas next=new Canvas();p.drawSpinner(next,1800,250,1,0xFF777777,false);check(entries(next,"line").get(0).color!=first,"petals advance at 80 ms");
   Canvas fixed=new Canvas();p.drawSpinner(fixed,1800,250,1,0xFF777777,true);SystemClock.now+=1000;Canvas fixed2=new Canvas();p.drawSpinner(fixed2,1800,250,1,0xFF777777,true);check(entries(fixed,"line").get(0).color==entries(fixed2,"line").get(0).color,"Reduce Motion keeps loader static");
   p.observe(4,100,104_000);check(p.label(104_000).equals(ru?"Проверка обновления…":"Verifying update…"),"full transfer still waits for validation");
   p.observe(5,100,105_000);Canvas ready=new Canvas();p.draw(ready,1,false);p.drawSpinner(ready,0,0,1,0,false);check(!p.active()&&ready.entries.isEmpty(),"ready removes both indicators");
   p.observe(4,5,200_000);check(p.label(200_000).contains("5%"),"new download resets ETA");p.observe(6,-1,201_000);check(!p.active(),"failure removes both indicators");
  }
  SettingsUpdateProgress live=new SettingsUpdateProgress();live.observe(4,15,0);AppLanguage.select("en");check(live.label(0).startsWith("Downloading"),"English label");AppLanguage.select("ru");check(live.label(0).startsWith("Загрузка"),"cached label follows language immediately");
  System.out.println("PASS: complete inline progress/ETA, real RU/EN/plurals, thin bar geometry/scaling, petals, Reduce Motion, verification, ready/error and retry");
 }
}'''
view=Path('app/src/io/tvos/SettingsActivity.java').read_text()
assert 'descriptionPaint.setTypeface(Ui.displaySemibold)' in view and 'descriptionPaint.setTextSize(30 * k)' in view
assert 'screen == UPDATES && updateProgress.active()' in view and 'screen == UPDATES && i == 0 && updateProgress.active()' in view
with tempfile.TemporaryDirectory(prefix='tvos-update-progress-') as tmp:
 root=Path(tmp);write(root)
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.UpdateProgressHarness'],check=True)
