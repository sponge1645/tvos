"""Exercise production value-row routing, widget toggles and application labels on JVM."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import os
import subprocess
import tempfile

source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(signature):
    start=source.index(signature);brace=source.index('{',start);depth=1;end=brace+1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
body=''.join(method(s) for s in ['        private boolean canAdjustMenu(', '        private void adjustMenu(', '        private void toggleWeather(', '        private boolean weatherVisible(', '        private void toggleDateTime(', '        private String[] menuLabels(', '        private String[] menuButtonLabels(', '        private String menuButtonLabel('])
header=r'''package io.tvos;
import java.util.*;
import static io.tvos.SettingsContent.*;
public class SettingsHarness {
 static class Ui {static boolean ru;}
 static class Prefs {static boolean weather=true,h24;static String theme="auto",language="en",unit="auto";static boolean weather(){return weather;}static void setWeather(boolean v){weather=v;}static String theme(){return theme;}static void setTheme(String v){theme=v;}static String language(){return language;}static void setLanguage(String v){language=v;}static String temperature(){return unit;}static void setTemperature(String v){unit=v;}static boolean hour24(){return h24;}static void setHour24(boolean v){h24=v;}}
 static class HomeData {static HomeData get(Object c){return new HomeData();}void settingsChanged(){}}
 static class Store {Map<String,Object> m=new HashMap<>();boolean contains(String k){return m.containsKey(k);}boolean getBoolean(String k,boolean d){return m.containsKey(k)?(Boolean)m.get(k):d;}int getInt(String k,int d){return m.containsKey(k)?(Integer)m.get(k):d;}Store edit(){return this;}Store putBoolean(String k,boolean v){m.put(k,v);return this;}Store putInt(String k,int v){m.put(k,v);return this;}void apply(){}}
 List<Integer> buttonEditor=new ArrayList<>(Arrays.asList(0,1,3,5,4,2));
 Store store=new Store();int screen,focus,adjusted,moved;
 static final String[] THEMES={"auto","light","dark"},UNITS={"auto","C","F"};
 Store localPrefs(){return store;}boolean localFlag(String k){return store.getBoolean(k,false);}void setLocalFlag(String k,boolean v){store.putBoolean(k,v);}Object getContext(){return this;}void invalidate(){}
 String tr(String r,String e){return Ui.ru?r:e;}void adjust(int dir){adjusted++;}void cycleDreamSource(int dir){}void cycleDelay(String k,int d,int dir){}void toggleFakeUpdate(){}void toggleAutomaticUpdates(){}boolean vpnTopEnabled(){return false;}void toggleVpnTop(){}void toggleMenuButton(){}void moveMenuButton(int dir){moved+=dir;}
 void arrow(int direction){if(canAdjustMenu())adjustMenu(direction);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''

tests=r'''
 public static void main(String[] args){
  SettingsHarness h=new SettingsHarness();h.screen=GENERAL;
  for(int row:new int[]{0,3,4}){h.focus=row;check(!h.canAdjustMenu(),"General action/submenu must require OK: "+row);}
  for(int row:new int[]{1,2,5,6,7}){h.focus=row;check(h.canAdjustMenu(),"General value accepts arrows: "+row);}
  h.focus=1;h.arrow(1);check(Prefs.theme.equals("light"),"theme advances");h.arrow(-1);check(Prefs.theme.equals("auto"),"theme reverses");
  h.screen=WEATHER;h.focus=0;h.arrow(1);check(!h.weatherVisible(true)&&h.weatherVisible(false),"top weather arrow toggles independently");
  h.focus=1;h.arrow(-1);check(!Prefs.weather(),"both weather panels off disables widget");h.arrow(1);check(Prefs.weather()&&h.weatherVisible(false)&&!h.weatherVisible(true),"bottom weather can turn on from all-off state");
  h.screen=DATE_TIME;h.focus=0;h.arrow(1);check(!h.store.getBoolean("show_date",true),"date widget accepts arrow");h.arrow(-1);check(h.store.getBoolean("show_date",false),"date widget reverses");
  h.screen=APPS_DOWNLOADS;Ui.ru=false;AppLanguage.select(Ui.ru?"ru":"en");for(String label:h.menuLabels())check(label.startsWith("Download "),"English download action: "+label);
  Ui.ru=true;AppLanguage.select(Ui.ru?"ru":"en");for(String label:h.menuLabels())check(label.startsWith("Загрузить "),"Russian download action retained");
  Ui.ru=false;AppLanguage.select(Ui.ru?"ru":"en");
  h.screen=MAIN;check(Arrays.equals(h.menuLabels(),new String[]{"General","Video and Audio","Screen Saver","AirPlay","Remotes and Devices","Accessibility","Apps","Dock","Menu Bar","Widgets","System"}),"main menu naming contract");
  h.screen=GENERAL;check(Arrays.equals(h.menuLabels(),new String[]{"About","Appearance","Language","Region","Date & Time","Temperature","Time Format","Navigation Clicks"}),"General naming contract");
  h.screen=ABOUT;check(Arrays.equals(h.menuLabels(),new String[]{"Name","Model","Serial Number","tvOS","OS","Resolution","Wi-Fi Network Name","IP Address"}),"About naming contract");
  h.screen=SCREENSAVER;check(Arrays.equals(h.menuLabels(),new String[]{"Current Selection","Show Clock","Show Location","Show Weather","Preview"}),"Screen Saver naming contract");
  h.screen=UPDATES;check(Arrays.equals(h.menuLabels(),new String[]{"Update Software","Automatically Update","Fake Update"}),"update actions naming contract");
  h.screen=SYSTEM;check(Arrays.equals(h.menuLabels(),new String[]{"Software Updates","Reset","Default Launcher","Restart tvOS"}),"System launcher appears directly below Reset");
  h.screen=ACCESSIBILITY;check(Arrays.equals(h.menuLabels(),new String[]{"Reduce Transparency","Reduce Motion","TV Settings"}),"accessibility naming contract");
  for(int page:new int[]{WEATHER,DATE_TIME,VPN_SETTINGS}){h.screen=page;check(h.menuLabels()[0].equals("Show in Menu Bar"),"Menu Bar wording shared everywhere");}
  h.screen=WEATHER;check(h.menuLabels()[1].equals("Show in Bottom Menu"),"bottom menu naming contract");
  h.screen=VPN_SETTINGS;check(h.menuLabels()[1].equals("VPN"),"VPN label stays concise");
  Ui.ru=true;AppLanguage.select(Ui.ru?"ru":"en");h.screen=ABOUT;check(h.menuLabels()[2].equals("Серийный номер"),"Russian About retained");
  h.screen=SCREENSAVER;check(h.menuLabels()[2].equals("Показывать место съёмки"),"Russian Screen Saver retained");
  h.screen=HOME_SETTINGS;h.focus=0;h.arrow(1);check(h.adjusted==1,"category/quote value rows stay adjustable");
  h.screen=SCREENSAVER;h.focus=4;check(!h.canAdjustMenu(),"preview requires OK");
  for(int row:new int[]{0,1,2,3}){h.focus=row;check(h.canAdjustMenu(),"source and overlay rows retain arrows");}
  String[] overlayKeys={"aerial_show_clock","aerial_show_location","screensaver_show_weather"};
  for(int i=0;i<overlayKeys.length;i++){
   h.focus=i+1;h.arrow(1);check(h.store.getBoolean(overlayKeys[i],false),"reindexed overlay toggles its own setting");
   h.arrow(-1);check(!h.store.getBoolean(overlayKeys[i],true),"reindexed overlay can toggle back");
  }
  h.screen=MENU_BUTTONS;h.focus=3;h.arrow(1);check(h.moved==1,"Menu Bar arrows reorder instead of toggling");
  h.focus=6;check(!h.canAdjustMenu(),"VPN app configuration is an OK-only action");
  Ui.ru=false;AppLanguage.select(Ui.ru?"ru":"en");check(h.menuLabels()[3].equals("Network")&&h.menuLabels()[6].equals("VPN App"),"Menu Bar labels follow saved rows");
  Ui.ru=true;AppLanguage.select(Ui.ru?"ru":"en");check(h.menuLabels()[3].equals("Сеть"),"network button has Russian label");
  h.screen=MAIN;Ui.ru=true;AppLanguage.select(Ui.ru?"ru":"en");check(h.menuLabels()[7].equals("Dock"),"Dock naming matches both languages");
  System.out.println("PASS: General OK-only actions, value arrows, independent weather/date toggles, bilingual download labels");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-settings-test-') as directory:
    root=Path(directory);(root/'SettingsHarness.java').write_text(header+body+tests)
    (root/'SettingsActions.java').write_text(Path('app/src/io/tvos/SettingsActions.java').read_text())
    (root/'SettingsContent.java').write_text(Path('app/src/io/tvos/SettingsContent.java').read_text())
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    write_strings(root)
    subprocess.run([java,'com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run([java,'-cp',directory,'io.tvos.SettingsHarness'],check=True)
