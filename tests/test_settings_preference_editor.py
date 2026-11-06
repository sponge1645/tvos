"""Exercise persisted value changes, migration fallback and callback order in the real adapter."""
from pathlib import Path
import subprocess,tempfile
from settings_preferences_fixture import write
from settings_strings_fixture import write as write_strings
harness='''package io.tvos;import android.content.SharedPreferences;import java.util.*;
class Prefs {
 static boolean weather=true,h24,random;static String theme="auto",unit="auto",language="en";
 static boolean weather(){return weather;}static void setWeather(boolean v){weather=v;}static boolean hour24(){return h24;}static void setHour24(boolean v){h24=v;}
 static boolean randomMovie(){return random;}static void setRandomMovie(boolean v){random=v;}
 static String theme(){return theme;}static void setTheme(String v){theme=v;}static String temperature(){return unit;}static void setTemperature(String v){unit=v;}
 static String language(){return language;}static void setLanguage(String v){language=v;}
}
public class PreferenceHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
 SharedPreferences p=new SharedPreferences();List<String> events=new ArrayList<>();SettingsPreferenceEditor e=new SettingsPreferenceEditor(p,()->events.add("changed"),()->events.add("refresh"));
 p.putString("personal","keep");
 for(int old=0;old<4;old++){
  p.data.remove("weather_show_top");p.data.remove("weather_show_bottom");p.putInt("weather_placement",old);Prefs.weather=true;
  check(e.weatherVisible(true)==((old&1)!=0)&&e.weatherVisible(false)==((old&2)!=0),"legacy placement read without writes");int writes=p.writes;events.clear();
  e.toggleWeather(true);int next=old^1;check(p.getInt("weather_placement",-1)==next&&p.getBoolean("weather_show_top",false)==((next&1)!=0)&&p.getBoolean("weather_show_bottom",false)==((next&2)!=0),"toggle writes compatible old/new keys");
  check(Prefs.weather==(next!=0)&&p.writes==writes+1&&events.equals(Arrays.asList("changed","refresh")),"weather persisted before notifications");
 }
 Prefs.weather=false;p.putBoolean("weather_show_top",true);p.putBoolean("weather_show_bottom",true);check(!e.weatherVisible(true)&&!e.weatherVisible(false),"master switch overrides saved positions");e.toggleWeather(false);check(Prefs.weather&&!e.weatherVisible(true)&&e.weatherVisible(false),"enable bottom from master-off");
 p.putInt("weather_placement",1);p.putBoolean("weather_show_top",false);check(!e.weatherVisible(true),"new key overrides legacy placement");
 for(int current=0;current<4;current++)for(int direction:new int[]{-1,1}){
  Prefs.weather=current!=0;p.putBoolean("weather_show_top",(current&1)!=0);p.putBoolean("weather_show_bottom",(current&2)!=0);events.clear();
  e.cycleWeather(direction);int next=Math.floorMod(current+direction,4);
  check(Prefs.weather==(next!=0)&&e.weatherVisible(true)==((next&1)!=0)&&e.weatherVisible(false)==((next&2)!=0)&&p.getInt("weather_placement",-1)==next,"all four weather states cycle in both directions, including None");
  check(events.equals(Arrays.asList("changed","refresh")),"weather cycle notifies after saving");
 }
 events.clear();e.toggleDateTime();check(p.getBoolean("show_date",false)&&events.equals(Arrays.asList("refresh")),"date defaults on and only redraws");e.toggleDateTime();check(!p.getBoolean("show_date",true),"date restored");
 Prefs.theme="auto";e.general(1,-1);check(Prefs.theme.equals("dark"),"theme reverse wrap");e.general(1,1);check(Prefs.theme.equals("auto"),"theme forward wrap");
 Prefs.unit="auto";e.temperature(-1);check(Prefs.unit.equals("F"),"unit reverse wrap");e.legacy(1,1);check(Prefs.unit.equals("auto"),"legacy shares unit cycle");
 for(int d:new int[]{-1,1}){Prefs.theme="invalid";e.general(1,d);check(Prefs.theme.equals(d<0?"light":"auto"),"unknown value fallback preserved");Prefs.theme="invalid";e.legacy(2,d);check(Prefs.theme.equals(d<0?"light":"auto"),"legacy fallback matches General");}
 Prefs.language="en";e.general(2,1);check(Prefs.language.equals("en"),"language submenu is not cycled by arrows");e.general(5,1);check(Prefs.h24,"time format toggle");
 events.clear();e.general(2,1);check(p.getBoolean("navigation_clicks",true),"General no longer changes navigation clicks");
 events.clear();e.legacy(4,1);e.legacy(99,1);check(events.isEmpty(),"city and unknown legacy rows do not mutate or notify");
 p.data.remove("video_dream_source");events.clear();e.cycleDreamSource(1);check(p.getString("video_dream_source","").equals("google")&&events.equals(Arrays.asList("refresh")),"dream source default and redraw");e.cycleDreamSource(1);check(p.getString("video_dream_source","").equals("na"),"dream source cycles");p.putString("video_dream_source","unknown");e.cycleDreamSource(-1);check(p.getString("video_dream_source","").equals("icloud"),"unknown dream source fallback preserved");
 check(p.getString("personal","").equals("keep"),"unrelated settings survive");
 System.out.println("PASS: actual preference adapter, weather migration/master/independent switches, defaults, direction/wrap/unknown values, legacy settings, exact callbacks and key preservation");
 }}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);write_strings(root);write(root)
 start=harness.index('class Prefs {');end=harness.index('public class PreferenceHarness')
 (root/'Prefs.java').write_text('package io.tvos;\n'+harness[start:end])
 (root/'PreferenceHarness.java').write_text(harness[:start]+harness[end:])
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.PreferenceHarness'],check=True)
