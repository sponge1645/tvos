"""Platform-effect fixture for executing production settings input handlers on the JVM."""
from pathlib import Path
SOURCE=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(signature, source=SOURCE):
 a=source.index(signature);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
HEADER='''package io.tvos;
import java.util.*;import static io.tvos.SettingsContent.*;
public class CLASSNAME {
 static final List<String> events=new ArrayList<>();
 static void note(String s){events.add(s);}
 static final int CITY_ROW=4;
 static final String[] THEMES={"auto","light","dark"},UNITS={"auto","C","F"};
 int screen=MAIN,focus,pickFor,settingsGroup=GENERAL,pickScroll,returnFocus,selectedSetting,buttonCount=6;
 boolean updateRequested,busyFlag,freeFlag,vpnFlag;long pendingAt;
 final List<Integer> menuButtonRows=new ArrayList<>();
 final List<String> appPkgs=new ArrayList<>();final List<String[]> cities=new ArrayList<>();
 static class Ui {static boolean ru;}
 static class Context {}final Context context=new Context();Context getContext(){return context;}
 static class Intent {
  static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_CLEAR_TOP=2;String action;int flags;
  Intent(String s){action=s;}Intent(Object activity,Class<?> type){action=type.getSimpleName();}
  Intent addFlags(int f){flags|=f;return this;}Intent putExtra(String k,boolean b){note("extra:"+k+":"+b);return this;}
 }
 static class HomeActivity {}static class Settings {static final String ACTION_DATE_SETTINGS="date",ACTION_SETTINGS="settings";}
 static class Activity {void startActivity(Intent i){note("start:"+i.action+":"+i.flags);}void finish(){note("finish");}void showPreview(){note("preview");}}
 final Activity activity=new Activity();
 static class Prefs {
  static String theme="auto",language="en",unit="auto";static boolean h24;
  static String theme(){return theme;}static String language(){return language;}static String temperature(){return unit;}static boolean hour24(){return h24;}
  static void setTheme(String v){theme=v;note("theme:"+v);}static void setLanguage(String v){language=v;note("language:"+v);}static void setTemperature(String v){unit=v;note("unit:"+v);}static void setHour24(boolean v){h24=v;note("hour24:"+v);}
  static final String[] KEYS={"netflix","youtube","prime","tcl"};static List<String> shelf;
  static List<String> shelf(){return shelf;}static void setShelf(List<String> s){shelf=s;note("shelf:"+s);}
  static void setCity(String a,String b,String c,long d,String e){note("city:"+a+":"+b+":"+c+":"+d+":"+e);}
  static void setKeyApp(String key,String pkg){note("key:"+key+":"+pkg);}
 }
 static class HomeApps {static List<String> shelfPackages(Context c){return Arrays.asList("llc.itdev.incy","watch.kino");}}
 static class Store {final Map<String,Boolean> flags=new HashMap<>();boolean getBoolean(String key,boolean fallback){return flags.getOrDefault(key,fallback);}Store edit(){return this;}Store putString(String k,String v){note(k+":"+v);return this;}void apply(){}}
 final Store store=new Store();Store localPrefs(){return store;}
 static class HomeData {static HomeData get(Context c){return new HomeData();}void settingsChanged(){note("changed");}}
 static class HomeMode {
  static final int FREE=1,ORIGINAL=2;
  static void request(Context c,int mode){note("home:"+mode);}static void ensureRemoteService(Context c,int value){note("remote-service");}
 }
 static class SystemClock {static long uptimeMillis(){return 123;}}
 static class SelfUpdate {static SelfUpdate get(Context c){return new SelfUpdate();}void check(boolean b){note("update:"+b);}}
 static class AppDownloads {static void start(Activity a,int row){note("download:"+row);}}
 static class Spring {void snap(float v){note("snap");}}
 final Spring[] pop={new Spring(),new Spring()};
 static class Spoken {void focusMoved(){note("spoken");}}final Spoken spoken=new Spoken();
 CLASSNAME(){for(int i=0;i<20;i++){appPkgs.add("app"+i);cities.add(new String[]{"city","Name","1","2","3","Zone"});}rebuildMenuButtonRows(-1);events.clear();Prefs.shelf=null;Prefs.theme="auto";Prefs.language="en";Prefs.unit="auto";Prefs.h24=false;}
 int settingIndex(){return selectedSetting;}boolean busy(){return busyFlag;}boolean freeActive(){return freeFlag;}boolean vpnTopEnabled(){return vpnFlag;}
 void invalidate(){note("invalidate");}void askDeviceName(){note("name");}void askCity(){note("ask-city");}
 void toggleCategory(){note("category");}void toggleShelfApp(){note("shelf-toggle");}void adjust(int d){note("adjust:"+d);}
 void launchAirPlay(){note("airplay");}void loadApps(){note("load-apps");}
 void openPicker(int p){pickFor=p;screen=PICKER;focus=0;note("picker:"+p);}
 void openShelfEditor(){screen=SHELF_EDIT;focus=0;note("shelf-editor");}
 void setLocalFlag(String key,boolean value){store.flags.put(key,value);note("flag:"+key+":"+value);invalidate();}
 boolean localFlag(String key){return store.getBoolean(key,false);}void cycleDreamSource(int d){note("dream-source:"+d);}void moveMenuButton(int d){note("move-button:"+d);}void onSelfUpdate(){note("on-update");}
 void toggleAutomaticUpdates(){note("auto-update");}void toggleMenuButton(){note("menu-toggle");}
 void toggleVpnTop(){note("vpn-toggle");}void toggleDateTime(){note("date-toggle");}void toggleWeather(boolean top){note("weather:"+top);}
 void pause(){note("pause");}void openCategoryEditor(){screen=CATEGORY_EDIT;focus=0;note("category-editor");}
 void openMenuButtonEditor(){screen=MENU_BUTTONS;focus=0;note("button-editor");}
 void confirmClearCache(){note("confirm-cache");}void confirmResetTvos(){note("confirm-reset");}
 void rebuildMenuButtonRows(int selected){menuButtonRows.clear();for(int i=0;i<buttonCount;i++)menuButtonRows.add(i);note("rebuild-buttons");}
 String snapshot(){return screen+":"+focus+":"+pickFor+":"+settingsGroup+":"+returnFocus+":"+pendingAt+":"+updateRequested+":"+events;}
'''
def view(name, activation=None, back=None):
 return HEADER.replace('CLASSNAME',name)+method('        private void adjustMenu(int dir)')+(activation or method('        private void activate()'))+(back or method('        private void backFromScreen()'))+'\n}\n'
