"""Exercise production value-row routing, widget toggles and application labels on JVM."""
from pathlib import Path
import os
import re
import subprocess
import tempfile

source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(signature):
    start=source.index(signature);brace=source.index('{',start);depth=1;end=brace+1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
body=''.join(method(s) for s in ['        private boolean canAdjustMenu(', '        private void adjustMenu(', '        private void toggleWeather(', '        private boolean weatherVisible(', '        private void toggleDateTime(', '        private String[] menuLabels('])
constants=sorted(set(re.findall(r'case ([A-Z_]+):',body)))
header=r'''import java.util.*;
public class SettingsHarness {
 static class Ui {static boolean ru;}
 static class Prefs {static boolean weather=true,h24;static String theme="auto",language="en",unit="auto";static boolean weather(){return weather;}static void setWeather(boolean v){weather=v;}static String theme(){return theme;}static void setTheme(String v){theme=v;}static String language(){return language;}static void setLanguage(String v){language=v;}static String temperature(){return unit;}static void setTemperature(String v){unit=v;}static boolean hour24(){return h24;}static void setHour24(boolean v){h24=v;}}
 static class HomeData {static HomeData get(Object c){return new HomeData();}void settingsChanged(){}}
 static class Store {Map<String,Object> m=new HashMap<>();boolean contains(String k){return m.containsKey(k);}boolean getBoolean(String k,boolean d){return m.containsKey(k)?(Boolean)m.get(k):d;}int getInt(String k,int d){return m.containsKey(k)?(Integer)m.get(k):d;}Store edit(){return this;}Store putBoolean(String k,boolean v){m.put(k,v);return this;}Store putInt(String k,int v){m.put(k,v);return this;}void apply(){}}
 Store store=new Store();int screen,focus,adjusted;
 static final String[] THEMES={"auto","light","dark"},LANGUAGES={"ru","en"},UNITS={"auto","C","F"};
 Store localPrefs(){return store;}boolean localFlag(String k){return store.getBoolean(k,false);}void setLocalFlag(String k,boolean v){store.putBoolean(k,v);}Object getContext(){return this;}void invalidate(){}
 String tr(String r,String e){return Ui.ru?r:e;}void adjust(int dir){adjusted++;}void cycleDreamSource(int dir){}void cycleDelay(String k,int d,int dir){}void toggleAutomaticUpdates(){}boolean vpnTopEnabled(){return false;}void toggleVpnTop(){}void toggleMenuButton(){}
 void arrow(int direction){if(canAdjustMenu())adjustMenu(direction);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
header+=' static final int '+','.join(f'{name}={i}' for i,name in enumerate(constants))+';\n'
tests=r'''
 public static void main(String[] args){
  SettingsHarness h=new SettingsHarness();h.screen=GENERAL;
  for(int row:new int[]{0,3,4}){h.focus=row;check(!h.canAdjustMenu(),"General action/submenu must require OK: "+row);}
  for(int row:new int[]{1,2,5,6,7}){h.focus=row;check(h.canAdjustMenu(),"General value accepts arrows: "+row);}
  h.focus=1;h.arrow(1);check(Prefs.theme.equals("light"),"theme advances");h.arrow(-1);check(Prefs.theme.equals("auto"),"theme reverses");
  h.screen=WEATHER;h.focus=0;h.arrow(1);check(!h.weatherVisible(true)&&h.weatherVisible(false),"top weather arrow toggles independently");
  h.focus=1;h.arrow(-1);check(!Prefs.weather(),"both weather panels off disables widget");h.arrow(1);check(Prefs.weather()&&h.weatherVisible(false)&&!h.weatherVisible(true),"bottom weather can turn on from all-off state");
  h.screen=DATE_TIME;h.focus=0;h.arrow(1);check(!h.store.getBoolean("show_date",true),"date widget accepts arrow");h.arrow(-1);check(h.store.getBoolean("show_date",false),"date widget reverses");
  h.screen=APPS_DOWNLOADS;Ui.ru=false;for(String label:h.menuLabels())check(label.startsWith("Download "),"English download action: "+label);
  Ui.ru=true;for(String label:h.menuLabels())check(label.startsWith("Загрузить "),"Russian download action retained");
  h.screen=HOME_SETTINGS;h.focus=0;h.arrow(1);check(h.adjusted==1,"category/quote value rows stay adjustable");
  h.screen=SCREENSAVER;h.focus=5;check(!h.canAdjustMenu(),"preview requires OK");
  System.out.println("PASS: General OK-only actions, value arrows, independent weather/date toggles, bilingual download labels");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-settings-test-') as directory:
    root=Path(directory);(root/'SettingsHarness.java').write_text(header+body+tests)
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java,'com.sun.tools.javac.Main','-d',directory,str(root/'SettingsHarness.java')],check=True)
    subprocess.run([java,'-cp',directory,'SettingsHarness'],check=True)
