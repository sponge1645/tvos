"""Execute weather appearance grouping and forecast-city solar boundaries."""
from pathlib import Path
import subprocess,tempfile
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name in ['WeatherForecast','WeatherCardStyle']:
  (root/(name+'.java')).write_text(Path('app/src/io/tvos/weather',name+'.java').read_text())
 (root/'Harness.java').write_text('''import io.tvos.weather.*;import java.time.*;
 public class Harness {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static long t(String s){return Instant.parse(s).toEpochMilli();}
 public static void main(String[] args){
 int[][] groups={{0,1},{2,3},{45,48},{51,53,55,56,57,61,63,65,66,67,80,81,82},{71,73,75,77,85,86},{95,96,99},{-1,100}};
 for(int k=0;k<groups.length;k++)for(int code:groups[k])check(WeatherCardStyle.kind(code)==k);
 WeatherForecast w=new WeatherForecast();w.timezone="Asia/Tokyo";
 check(WeatherCardStyle.day(w,t("2026-11-04T01:00:00Z")));check(!WeatherCardStyle.day(w,t("2026-11-04T13:00:00Z")));
 w.sunrise=t("2026-11-03T21:30:00Z");w.sunset=t("2026-11-04T07:30:00Z");
 check(!WeatherCardStyle.day(w,w.sunrise-1));check(WeatherCardStyle.day(w,w.sunrise));check(!WeatherCardStyle.day(w,w.sunset));
 check(WeatherCardStyle.twilight(w,w.sunset-10_000));check(!WeatherCardStyle.twilight(w,w.sunset+3_600_000));
 check(WeatherCardStyle.day(w,t("2026-11-05T01:00:00Z"))); // stale solar dates use city clock
 w.timezone="Europe/London";w.sunrise=0;w.sunset=0;
 check(WeatherCardStyle.day(w,t("2026-07-01T05:30:00Z")));check(!WeatherCardStyle.day(w,t("2026-12-01T05:30:00Z")));
 for(int k=0;k<6;k++)check(WeatherCardStyle.top(k,true,false)!=WeatherCardStyle.top(k,false,false));
 check(WeatherCardStyle.top(6,true,false)==WeatherCardStyle.top(6,false,false));
 System.out.println("PASS: WMO weather scenes, city timezone/DST, sunrise/sunset boundaries, twilight and stale/missing solar fallback");
 }}''')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'Harness'],check=True)
