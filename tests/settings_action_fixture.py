"""Platform-effect fixture for executing production settings input handlers on the JVM."""
from pathlib import Path
SOURCE=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(signature, source=SOURCE):
 a=source.index(signature);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
HEADER='''package io.tvos;
import java.util.*;import static io.tvos.settings.SettingsScreen.*;import io.tvos.settings.SettingsActions;
public class CLASSNAME {
 static final List<String> events=new ArrayList<>();
 static void note(String s){events.add(s);}
 static final int CITY_ROW=4;
 static final String[] THEMES={"auto","light","dark"},UNITS={"auto","C","F"};
 String screenTitle(){return "Settings";}
 int screen=MAIN,focus,settingsGroup=GENERAL,selectedSetting,buttonCount=6;
 final io.tvos.settings.SettingsListWindow listWindow=new io.tvos.settings.SettingsListWindow(10);
 boolean updateRequested,busyFlag,freeFlag,vpnFlag;final ModeMonitor modeMonitor=new ModeMonitor();class ModeMonitor {long pendingAt;void markPending(){pendingAt=SystemClock.uptimeMillis();}}
 final AppLoader appLoader=new AppLoader(),tvProviderLoader=new AppLoader();class AppLoader {boolean cancelled;void cancel(){cancelled=true;}}
 final ButtonEditor buttonEditor=new ButtonEditor();class ButtonEditor {final List<Integer> rows=new ArrayList<>();int size(){return rows.size();}}
 static class Values {void clearAppCache(){}}final Values valueSource=new Values();
 final SettingsPicker picker=new SettingsPicker();{picker.keys.addAll(Arrays.asList("FR",""));}
 void openCountryPicker(){openPicker(PICK_REGION);}void openTvProviderPicker(){openPicker(PICK_TV);}
 final List<String> appPkgs=new ArrayList<>();final List<String[]> cities=new ArrayList<>();
 static class Ui {static boolean ru;}
 static class Context {}final Context context=new Context();Context getContext(){return context;}
 static class Intent {
  static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_CLEAR_TOP=2;String action;int flags;
  Intent(String s){action=s;}Intent(Object activity,Class<?> type){action=type.getSimpleName();}
  Intent addFlags(int f){flags|=f;return this;}Intent putExtra(String k,boolean b){note("extra:"+k+":"+b);return this;}
 }
 static class SetupActivity {}static class RemoteShortcutSettingsActivity {static void open(Activity activity,Object source,String title){if(source==null||title.isEmpty())throw new AssertionError();note("remote-source-frame");activity.startActivity(new Intent(activity,RemoteShortcutSettingsActivity.class));}}static class HomeActivity {}static class Settings {static final String ACTION_DATE_SETTINGS="date",ACTION_SETTINGS="settings";}
 static class Activity {void startActivity(Intent i){note("start:"+i.action+":"+i.flags);}void finish(){note("finish");}void showPreview(){note("preview");}}
 final Activity activity=new Activity();
 static class Prefs {static boolean homeContinue(){return home;}static boolean home;static void setHomeContinue(boolean v){home=v;}
  static String theme="auto",language="en",unit="auto";static boolean weather=true,random;static boolean weather(){return weather;}static void setWeather(boolean v){weather=v;}static boolean randomMovie(){return random;}static void setRandomMovie(boolean v){random=v;}static boolean h24,auto;static boolean automaticRegion(){return auto;}
  static String theme(){return theme;}static String language(){return language;}static String temperature(){return unit;}static boolean hour24(){return h24;}
  static void setTheme(String v){theme=v;note("theme:"+v);}static void setLanguage(String v){language=v;note("language:"+v);}static void setTemperature(String v){unit=v;note("unit:"+v);}static void setHour24(boolean v){h24=v;note("hour24:"+v);}
  static final String[] KEYS={"netflix","youtube","prime","tcl"};static List<String> shelf;
  static List<String> shelf(){return shelf;}static void setShelf(List<String> s){shelf=s;note("shelf:"+s);}
  static void setRecommendationRegion(String code){note("country:"+code);}static void setLiveTvApp(String target){note("tv:"+target);}
  static void setCity(String a,String b,String c,long d,String e){note("city:"+a+":"+b+":"+c+":"+d+":"+e);}
  static void setKeyApp(String key,String pkg){note("key:"+key+":"+pkg);}
 }
 static class HomeApps {static List<String> shelfPackages(Context c){return Arrays.asList("llc.itdev.incy","watch.kino");}}
 static class Store extends android.content.SharedPreferences {final Map<String,String> strings=new HashMap<>();public String getString(String k,String d){return strings.getOrDefault(k,d);}final Map<String,Boolean> flags=new HashMap<>();public boolean getBoolean(String key,boolean fallback){return flags.getOrDefault(key,fallback);}public Store edit(){return this;}public Store putString(String k,String v){strings.put(k,v);note(k+":"+v);return this;}public Store putBoolean(String k,boolean v){flags.put(k,v);note("flag:"+k+":"+v);return this;}public void apply(){}}
 final Store store=new Store();final SettingsPreferenceEditor preferenceEditor=new SettingsPreferenceEditor(store,()->HomeData.get(getContext()).settingsChanged(),this::invalidate);Store localPrefs(){return store;}
 static class HomeData {static HomeData get(Context c){return new HomeData();}void settingsChanged(){note("changed");}}
 static class HomeMode {
  static final int FREE=1,ORIGINAL=2;
  static void request(Context c,int mode){note("home:"+mode);}static void ensureRemoteService(Context c,int value){note("remote-service");}
 }
 static class SystemClock {static long uptimeMillis(){return 123;}}
 final Dialog updateDialog=new Dialog();class Dialog {void check(){updateRequested=true;note("update:false");note("on-update");}}
 static class AppDownloads {static void start(Activity a,int row){note("download:"+row);}}
 static class Spring {void snap(float v){note("snap");}}
 final Spring[] pop={new Spring(),new Spring()};
 static class Spoken {void focusMoved(){note("spoken");}}final Spoken spoken=new Spoken();
 CLASSNAME(){for(int i=0;i<20;i++){appPkgs.add("app"+i);cities.add(new String[]{"city","Name","1","2","3","Zone"});}rebuildMenuButtonRows(-1);events.clear();Prefs.shelf=null;Prefs.theme="auto";Prefs.language="en";Prefs.unit="auto";Prefs.h24=false;Prefs.auto=false;}
 int settingIndex(){return selectedSetting;}boolean busy(){return busyFlag;}boolean freeActive(){return freeFlag;}boolean vpnTopEnabled(){return vpnFlag;}
 static class RemoteInputMethod {static void choose(Activity a){note("input-method");}}void invalidate(){note("invalidate");}void askCity(){note("ask-city");}
 void toggleCategory(){note("category");}void toggleShelfApp(){note("shelf-toggle");}void adjust(int d){note("adjust:"+d);}
 void launchAirPlay(){note("airplay");}void loadApps(Runnable ready){note("load-apps");ready.run();}void openTvSettings(){note("tv-settings");}
 void openPicker(int p){picker.kind=p;screen=PICKER;focus=0;note("picker:"+p);}
 void openShelfEditor(){screen=SHELF_EDIT;focus=0;note("shelf-editor");}
 void setLocalFlag(String key,boolean value){store.flags.put(key,value);note("flag:"+key+":"+value);invalidate();}
 boolean localFlag(String key){return store.getBoolean(key,false);}void cycleDreamSource(int d){note("dream-source:"+d);}void moveMenuButton(int d){note("move-button:"+d);}void onSelfUpdate(){note("on-update");}
 void toggleAutomaticUpdates(){note("auto-update");}void toggleMenuButton(){note("menu-toggle");}
 void toggleAutomaticRegion(){Prefs.auto=!Prefs.auto;note("region-toggle");}void toggleDateTime(){note("date-toggle");}void toggleWeather(boolean top){note("weather:"+top);}
 void pause(){note("pause");}void openCategoryEditor(){screen=CATEGORY_EDIT;focus=0;note("category-editor");}
 void openMenuButtonEditor(){screen=MENU_BUTTONS;focus=0;note("button-editor");}
 final Dialogs deviceDialogs=new Dialogs();static class Dialogs {void askDeviceName(){note("name");}void confirmClearCache(){note("confirm-cache");}void confirmResetTvos(){note("confirm-reset");}}
 void rebuildMenuButtonRows(int selected){buttonEditor.rows.clear();for(int i=0;i<buttonCount;i++)buttonEditor.rows.add(i);note("rebuild-buttons");}
'''
def view(name, activation=None, back=None):
 return HEADER.replace('CLASSNAME',name)+method('        private void adjustMenu(int dir)')+(activation or method('        private void activate()'))+(back or method('        private void backFromScreen()'))+'\n}\n'
