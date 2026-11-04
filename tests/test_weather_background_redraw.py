"""Repeated weather redraws must not inherit the translucent outline's alpha."""
from pathlib import Path
import subprocess, tempfile
from home_renderers_fixture import files
from settings_strings_fixture import write
f=files()
for name in ("WeatherCardBackground",):
    f["io/tvos/"+name+".java"]=Path("app/src/io/tvos/"+name+".java").read_text()
f["io/tvos/weather/WeatherCardStyle.java"]=Path("app/src/io/tvos/weather/WeatherCardStyle.java").read_text()
f["io/tvos/WeatherRedrawHarness.java"]='''package io.tvos;
import android.graphics.*;import io.tvos.weather.*;
public class WeatherRedrawHarness {
 public static void main(String[] args){
  WeatherCardBackground background=new WeatherCardBackground();WeatherForecast w=new WeatherForecast();
  RectF panel=new RectF(40,40,740,740);
  for(int code:new int[]{0,3,61,73,95})for(int frame=0;frame<10;frame++){
   w.code=code;Canvas c=new Canvas();background.draw(c,panel,w,1762300800000L+frame*60000,frame<5);
   Canvas.Entry sky=c.entries.stream().filter(e->e.kind.equals("rect")).findFirst().get();
   if((sky.color>>>24)!=255||sky.alpha!=255)throw new AssertionError("Weather background lost opacity after redraw "+frame);
   if(c.entries.stream().anyMatch(e->e.kind.equals("circle")))throw new AssertionError("Weather particles returned");
  }
  System.out.println("PASS: weather sky stays opaque over repeated redraws and weather/day changes");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);write(root)
    for name,text in f.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    subprocess.run(["java","com.sun.tools.javac.Main","-d",tmp,*map(str,root.rglob("*.java"))],check=True)
    subprocess.run(["java","-cp",tmp,"io.tvos.WeatherRedrawHarness"],check=True)
