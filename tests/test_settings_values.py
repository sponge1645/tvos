"""Execute the complete production settings values class with controlled platform reads and real XML."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write
sources={
'android/content/Intent.java':'package android.content;public class Intent {}',
'android/content/pm/ResolveInfo.java':'package android.content.pm;public class ResolveInfo {public ActivityInfo activityInfo=new ActivityInfo();public static class ActivityInfo {public ApplicationInfo applicationInfo=new ApplicationInfo();}public static class ApplicationInfo {public CharSequence loadLabel(PackageManager pm){return "Detected TV";}}}',
'io/tvos/HomeApps.java':'package io.tvos;class HomeApps {static boolean detected;static android.content.Intent automaticLiveTv(Object c){return detected?new android.content.Intent():null;}}',
'io/tvos/AppLog.java':'package io.tvos;class AppLog {static void w(String s){}}',
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {public final Map<String,Object> data=new HashMap<>();public int getInt(String k,int d){return ((Number)data.getOrDefault(k,d)).intValue();}public long getLong(String k,long d){return ((Number)data.getOrDefault(k,d)).longValue();}public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}public String getString(String k,String d){return (String)data.getOrDefault(k,d);}}''',
'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public static final String WIFI_SERVICE="wifi";public final SharedPreferences prefs=new SharedPreferences();public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.net.wifi.WifiManager wifi=new android.net.wifi.WifiManager();public SharedPreferences getSharedPreferences(String s,int mode){return prefs;}public android.content.pm.PackageManager getPackageManager(){return pm;}public String getPackageName(){return "io.tvos";}public Context getApplicationContext(){return this;}public Object getSystemService(String name){return wifi;}}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {public android.content.pm.ResolveInfo resolveActivity(android.content.Intent i,int flags){return new android.content.pm.ResolveInfo();}public boolean fail;public static class NameNotFoundException extends Exception {private static final long serialVersionUID=1L;}public static class Info {public String versionName="1.1.0";}public Object getApplicationInfo(String p,int f)throws NameNotFoundException {if(fail)throw new NameNotFoundException();return p;}public CharSequence getApplicationLabel(Object p){return "Label "+p;}public Info getPackageInfo(String p,int f){if(fail)throw new SecurityException();return new Info();}}''',
'android/app/Activity.java':'''package android.app;public class Activity extends android.content.Context {public WindowManager getWindowManager(){return new WindowManager();}public static class WindowManager {public WindowManager getDefaultDisplay(){return this;}public void getRealMetrics(android.util.DisplayMetrics m){m.widthPixels=1920;m.heightPixels=1080;}}}''',
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics {public int widthPixels,heightPixels;}',
'android/os/Build.java':'''package android.os;public class Build {public static String MODEL=" Test TV ",serial="SN126";public static boolean deny;public static String getSerial(){if(deny)throw new SecurityException();return serial;}public static class VERSION {public static final String RELEASE="11";}}''',
'android/net/wifi/WifiInfo.java':'package android.net.wifi;public class WifiInfo {public String ssid="\\\"Home\\\"";public int getRssi(){return -55;}public String getSSID(){return ssid;}}',
'android/net/wifi/WifiManager.java':'package android.net.wifi;public class WifiManager {public WifiInfo info=new WifiInfo();public WifiInfo getConnectionInfo(){return info;}}',
'io/tvos/Config.java':'package io.tvos;final class Config {static String CURRENCY="USD";}',
'io/tvos/Prefs.java':'''package io.tvos;final class Prefs { static boolean collection=true;static boolean homeCollection(){return collection;}static boolean home;static boolean homeContinue(){return home;}static String theme="auto",unit="auto",city="City";static boolean weather=true,random=true,h24;static String recommendationRegion(){return "FR";}static String language(){return AppLanguage.current().code;}static String temperature(){return unit;}static String theme(){return theme;}static String city(){return city;}static boolean automaticRegion(){return false;}static String manualCity(){return city;}static boolean weather(){return weather;}static boolean randomMovie(){return random;}static boolean hour24(){return h24;}static boolean topButtonEnabled(int id){return id!=2;}static int topButtonPosition(int id){return id+1;}static final String[] KEYS={"netflix","youtube","prime","tcl","mi"};static String keyApp(String k){return (k.equals("tcl")||k.equals("mi"))?"":k;}}''',
'io/tvos/settings/SettingsOrderEditor.java':'package io.tvos.settings;public final class SettingsOrderEditor {public int size(){return 2;}public int get(int i){return i==0?3:2;}}',
'io/tvos/LocalNetwork.java':'''package io.tvos;final class LocalNetwork {static Info info;static class Info {boolean ethernet;String address="192.168.0.111";}static Info read(android.content.Context c){return info;}}''',
'io/tvos/ValuesHarness.java':'''package io.tvos;import java.util.*;import io.tvos.settings.SettingsOrderEditor;import android.app.Activity;import static io.tvos.settings.SettingsScreen.*;
public class ValuesHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  Activity a=new Activity();String[] status={""};boolean[] free={false};SettingsValues v=new SettingsValues(a,new SettingsOrderEditor(),()->free[0],top->top,()->status[0],new SettingsValueLoader<>(),new SettingsValueLoader<>());
  for(String lang:new String[]{"en","ru"}){
   AppLanguage.select(lang);boolean ru=lang.equals("ru");
   check(v.menu(MEDIA,3)[0].equals(ru?"Спрашивать каждый раз":"Ask Every Time"),"default service initially asks every time");
   check(v.menu(LANGUAGE,2)[ru?0:1].equals(AppStrings.get(R.string.settings_service_selected)),"language list marks saved choice");check(v.menu(MEDIA,3)[2].equals(AppStrings.get(R.string.settings_tv_unselected)),"empty TV provider shows not selected");
   HomeApps.detected=true;check(v.menu(MEDIA,3)[2].equals("Detected TV"),"existing automatic provider displays resolved app");
   a.prefs.data.put("live_tv_app","automatic");check(v.menu(MEDIA,3)[2].equals("Detected TV"),"wizard automatic provider displays resolved app");
   HomeApps.detected=false;a.prefs.data.remove("live_tv_app");check(v.menu(MEDIA,3)[2].equals(AppStrings.get(R.string.settings_tv_unselected)),"no detected provider stays unselected");
   a.prefs.data.put(MovieService.KEY,"seena");check(v.menu(MEDIA,3)[0].equals("Seena")&&v.menu(MOVIE_SERVICE,5)[3].equals(ru?"Выбрано":"Selected"),"service value and accessible selected state");a.prefs.data.remove(MovieService.KEY);
   check(v.menu(HOME_CARD_STYLE,2)[1].equals(ru?"Выбрано":"Selected"),"recommendations selected by default");Prefs.home=true;check(v.menu(HOME_CARD_STYLE,2)[0].equals(ru?"Выбрано":"Selected"),"continue mode selected");check(v.menu(TOP_PANEL,4)[0].isEmpty(),"Home submenu has an arrow without a value");check(v.menu(HOME_SCREEN,3)[1].equals(AppStrings.get(R.string.home_continue_watching)),"Home container displays card choice");Prefs.home=false;
   String[] general=v.menu(GENERAL,6);check(general.length==6&&general[0].isEmpty()&&!general[3].isEmpty(),"general ordering");
   check(general[3].equals(AppLanguage.current().nativeName)&&general[1].equals(ru?"Авто":"Automatic"),"live language and theme");
   Prefs.unit="C";Prefs.theme="dark";Prefs.h24=true;general=v.menu(GENERAL,6);check(v.menu(WEATHER,3)[2].equals("°C")&&general[5].equals(ru?"24 ч":"24 h"),"units/hour");
   check(v.menu(GENERAL,6)[2].equals(AppStrings.get(R.string.settings_sleep_unset)),"no unsolicited system timeout change before selection");
   for(int choice=0;choice<7;choice++){a.prefs.data.put("sleep_choice",choice);String[] selected=v.menu(SLEEP_TIMER,7);check(selected[choice].equals(AppStrings.get(R.string.settings_service_selected))&&v.menu(GENERAL,6)[2].equals(SettingsContent.menuLabels(SLEEP_TIMER)[choice]),"sleep duration value and accessible selection agree");for(int other=0;other<7;other++)if(other!=choice)check(selected[other].isEmpty(),"only chosen sleep duration selected");}a.prefs.data.remove("sleep_choice");
   Prefs.unit="auto";Prefs.theme="auto";Prefs.h24=false;
   free[0]=true;check(v.menu(SYSTEM,4)[2].equals("tvOS"),"current launcher callback");
   check(v.menu(ACCESSIBILITY,3)[2].isEmpty(),"TV Settings action has no misplaced launcher value");
   free[0]=false;check(v.menu(SYSTEM,4)[2].equals(ru?"Встроенный":"Built-in"),"built-in launcher value follows moved row");free[0]=true;
   a.prefs.data.put("self_update_attempt",0L);int reads=SettingsUpdateDialog.reads;String details=v.updateDetailsText();check(details.equals("tvOS installed\\n"+AppStrings.get(R.string.settings_last_check_never)),"localized update details owned by values");v.updateDetailsText();check(SettingsUpdateDialog.reads==(reads==0?1:reads),"installed version cached across frames/language changes");
   a.prefs.data.put("self_update_attempt",1700000000000L);check(v.updateDetailsText().equals("tvOS installed\\n"+SettingsValues.formatLastUpdateCheck(1700000000000L,AppLanguage.current())),"new check timestamp refreshes details");
   a.prefs.data.put("auto_update",true);check(v.menu(UPDATES,2)[1].equals(SettingsValues.onOff(true)),"auto update value");
   a.prefs.data.put("video_dream_source","na");String[] saver=v.menu(SCREENSAVER,5);check(saver[0].equals("National Geographic")&&saver[4].isEmpty(),"saver source and action row");
   SettingsValues hidden=new SettingsValues(a,new SettingsOrderEditor(),()->false,top->false,()->"",new SettingsValueLoader<>(),new SettingsValueLoader<>());
   check(hidden.menu(WEATHER,3)[0].equals(ru?"Нет":"None"),"weather absent on both surfaces displays None");
   check(v.menu(WEATHER,3)[0].equals(AppStrings.get(R.string.settings_weather_home))&&v.menu(WEATHER,3)[1].equals("City"),"independent weather callback");
   check(v.menu(MENU_BUTTONS,3)[0].equals("#4")&&v.menu(MENU_BUTTONS,3)[1].equals(SettingsValues.onOff(false))&&v.menu(MENU_BUTTONS,3)[2].isEmpty(),"button order and VPN footer");
   status[0]="Searching";check(v.menu(CITY,2)[1].equals("Searching"),"Region exposes pending/error lookup status");check(v.settings()[4].equals("Searching"),"live city status");status[0]="";Prefs.city="";check(v.settings()[4].equals("—"),"empty city fallback");Prefs.city="City";
   check(v.settings().length==5,"stable category rows without quote/coin controls");
   check(SettingsValues.keyLabels().length==7&&SettingsValues.keyLabels()[4].equals(ru?"Кнопка MI":"MI Button"),"MI setting present in both languages");String[] mappings=SettingsValues.keyValues(Arrays.asList("", "youtube"),Arrays.asList("old empty label", "YT"));
   check(mappings[1].equals("YT"),"configured remote retains app label");
   for(int index:new int[]{0,2,3})check(mappings[index].equals(ru?"Не настроено":"Not configured"),"missing and empty remote mappings use one live localized status");
   mappings=SettingsValues.keyValues(Collections.emptyList(),Collections.emptyList());check(mappings[4].equals(AppStrings.get(R.string.settings_not_configured)),"unmapped MI has no default app");
   for(int i=0;i<5;i++)check(mappings[i].equals(ru?"Не настроено":"Not configured"),"empty catalog uses same status for all five app buttons");
   check(mappings[5].isEmpty(),"input setup has no obsolete volume status");
   a.prefs.data.put("device_name","Living room");LocalNetwork.info=new LocalNetwork.Info();LocalNetwork.info.ethernet=true;
   String[] about=v.menu(ABOUT,9);check(about[0].equals("Living room")&&about[1].equals("Test TV")&&about[2].equals("SN126")&&about[3].equals("1.1.0 (1B__BUILD__)")&&about[5].equals("1920 × 1080")&&about[6].equals("Ethernet")&&about[7].equals("192.168.0.111"),"about values and physical network address");check(about[8].equals("—"),"Ethernet has no Wi-Fi signal");
   LocalNetwork.info.ethernet=false;a.wifi.info.ssid="\\\"Home\\\"";check(v.menu(ABOUT,9)[6].equals("Home"),"SSID quotes removed");check(v.menu(ABOUT,9)[8].equals("4/5"),"fifth-dot signal value remains accessible");
   a.pm.fail=true;android.os.Build.deny=true;about=v.menu(ABOUT,9);check(about[3].equals("—")&&about[2].equals(AppStrings.get(R.string.settings_unavailable)),"platform permission failures preserve fallbacks");
   check(v.vpnApp().equals(ru?"Не настроено":"Not configured"),"unset VPN status");
   a.prefs.data.put("vpn_app","missing");check(v.vpnApp().equals(AppStrings.get(R.string.settings_not_found)),"missing VPN label fallback");a.pm.fail=false;android.os.Build.deny=false;a.prefs.data.remove("vpn_app");
  }
  a.prefs.data.remove("video_dream_source");check(v.menu(SCREENSAVER,5)[0].equals("Aerial"),"Aerial is the default source");
  System.out.println("PASS: production settings values, live locale, controls, dynamic rows, device/network data and platform fallbacks");
 }
}'''
}
sources['io/tvos/SettingsValueLoader.java']='package io.tvos;class SettingsValueLoader<T> {T get(String key,T fallback,java.util.function.Supplier<T> read){return read.get();}void clear(){}}'
sources['io/tvos/network/WifiSignal.java']='package io.tvos.network;public class WifiSignal {public static int strengthForRssi(int rssi){return 4;}}'
sources['io/tvos/LiveTvTargets.java']='package io.tvos;class LiveTvTargets {static String packageName(String key){return key;}}'
sources['io/tvos/setup/SetupCountries.java']=Path('app/src/io/tvos/setup/SetupCountries.java').read_text()
for name in ['SettingsValues','SettingsContent']:sources['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
sources['io/tvos/SettingsUpdateDialog.java']='package io.tvos;class SettingsUpdateDialog {static int reads;static String versionName(android.content.Context c){reads++;return "tvOS installed";}}'
sources['io/tvos/version/SoftwareBuild.java']=Path('app/src/io/tvos/version/SoftwareBuild.java').read_text()
import re
sequence = int(re.search(r'SEQUENCE = ([0-9]+);', Path('app/src/io/tvos/version/SoftwareBuild.java').read_text()).group(1))
sources['io/tvos/ValuesHarness.java'] = sources['io/tvos/ValuesHarness.java'].replace('__BUILD__', f'{sequence:03d}')
with tempfile.TemporaryDirectory(prefix='tvos-settings-values-') as tmp:
 root=Path(tmp);write(root)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ValuesHarness'],check=True)
