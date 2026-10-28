"""Execute widget content drawing, text invalidation and measurement/snapshot lifetimes."""
from pathlib import Path
import subprocess,tempfile
from home_widgets_fixture import sources
harness='''package io.tvos;import android.graphics.*;import android.content.Context;
public class WidgetsHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  HomeData data=new HomeData();HomeWidgets widgets=new HomeWidgets(new Context(),data);widgets.resize(1);RectF r=new RectF(0,0,1000,300);AppLanguage.select("en");Canvas c=new Canvas();widgets.weather(c,r,0);
  check(c.contains("Weather")&&c.contains("10°")&&c.contains("H:20°   L:5°")&&c.contains("Sunrise 06:00"),"weather data/copy");
  check(c.entries.stream().anyMatch(e->e.text.equals("11°")&&Math.abs(e.size-31.2f)<.001f),"hourly font size retained");check(c.entries.stream().anyMatch(e->e.text.equals("symbol:sun")&&e.y==132),"current symbol geometry");
  Prefs.city="Test City";Prefs.version++;c=new Canvas();widgets.weather(c,r,1);check(c.contains("Test City"),"region title refreshed");Ui.language="ru";AppLanguage.select("ru");c=new Canvas();widgets.weather(c,r,.5f);check(c.contains("Макс. 20°   Мин. 5°"),"language invalidates text");
  Ui.LEGACY=true;widgets.weather(c,r,0);Bitmap first=c.shown;int count=Bitmap.allocations;for(int i=0;i<100;i++)widgets.weather(c,r,0);check(Bitmap.allocations==count&&c.shown==first,"legacy snapshot reused");data.weatherAt++;widgets.weather(c,r,0);check(Bitmap.allocations==count,"data change repaints existing bitmap");widgets.clear();widgets.weather(c,r,0);check(Bitmap.allocations==count+1&&c.shown!=first,"pause drops snapshot");
  Ui.LEGACY=false;
  data.weather=new HomeData.Weather();data.weather.temp=Double.NaN;Prefs.version++;c=new Canvas();widgets.weather(c,r,0);check(c.contains(AppStrings.get(R.string.home_no_weather_data_right_now)),"missing weather fallback");System.out.println("PASS: production widgets, geometry, data/locale changes, snapshots and missing-data fallback");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-home-widgets-') as tmp:
 root=Path(tmp);items=sources();items['io/tvos/WidgetsHarness.java']=harness
 for name,content in items.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.WidgetsHarness'],check=True)
