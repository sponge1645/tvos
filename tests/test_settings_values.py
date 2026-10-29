"""Execute the complete production settings values class with controlled platform reads and real XML."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write
sources={
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {public final Map<String,Object> data=new HashMap<>();public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}public String getString(String k,String d){return (String)data.getOrDefault(k,d);}}''',
'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public static final String WIFI_SERVICE="wifi";public final SharedPreferences prefs=new SharedPreferences();public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.net.wifi.WifiManager wifi=new android.net.wifi.WifiManager();public SharedPreferences getSharedPreferences(String s,int mode){return prefs;}public android.content.pm.PackageManager getPackageManager(){return pm;}public String getPackageName(){return "io.tvos";}public Context getApplicationContext(){return this;}public Object getSystemService(String name){return wifi;}}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {public boolean fail;public static class NameNotFoundException extends Exception {private static final long serialVersionUID=1L;}public static class Info {public String versionName="1.1.0";}public Object getApplicationInfo(String p,int f)throws NameNotFoundException {if(fail)throw new NameNotFoundException();return p;}public CharSequence getApplicationLabel(Object p){return "Label "+p;}public Info getPackageInfo(String p,int f){if(fail)throw new SecurityException();return new Info();}}''',
'android/app/Activity.java':'''package android.app;public class Activity extends android.content.Context {public WindowManager getWindowManager(){return new WindowManager();}public static class WindowManager {public WindowManager getDefaultDisplay(){return this;}public void getRealMetrics(android.util.DisplayMetrics m){m.widthPixels=1920;m.heightPixels=1080;}}}''',
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics {public int widthPixels,heightPixels;}',
'android/os/Build.java':'''package android.os;public class Build {public static String MODEL=" Test TV ",serial="SN123";public static boolean deny;public static String getSerial(){if(deny)throw new SecurityException();return serial;}public static class VERSION {public static final String RELEASE="11";}}''',
'android/net/wifi/WifiInfo.java':'package android.net.wifi;public class WifiInfo {public String ssid="\\\"Home\\\"";public String getSSID(){return ssid;}}',
'android/net/wifi/WifiManager.java':'package android.net.wifi;public class WifiManager {public WifiInfo info=new WifiInfo();public WifiInfo getConnectionInfo(){return info;}}',
'io/tvos/Config.java':'package io.tvos;final class Config {static String CURRENCY="USD";}',
'io/tvos/Prefs.java':'''package io.tvos;final class Prefs {static String theme="auto",unit="auto",city="City";static boolean weather=true,random=true,h24;static String language(){return AppLanguage.current().code;}static String temperature(){return unit;}static String theme(){return theme;}static String city(){return city;}static boolean automaticRegion(){return false;}static String manualCity(){return city;}static boolean weather(){return weather;}static boolean randomMovie(){return random;}static boolean hour24(){return h24;}static boolean topButtonEnabled(int id){return id!=2;}static int topButtonPosition(int id){return id+1;}static final String[] KEYS={"netflix","youtube","prime","tcl"};static String keyApp(String k){return k.equals("tcl")?"":k;}}''',
'io/tvos/SettingsOrderEditor.java':'package io.tvos;final class SettingsOrderEditor {int size(){return 2;}int get(int i){return i==0?3:2;}}',
'io/tvos/LocalNetwork.java':'''package io.tvos;final class LocalNetwork {static Info info;static class Info {boolean ethernet;String address="192.168.0.111";}static Info read(android.content.Context c){return info;}}''',
'io/tvos/ValuesHarness.java':'''package io.tvos;import java.util.*;import android.app.Activity;import static io.tvos.SettingsContent.*;
public class ValuesHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  Activity a=new Activity();String[] status={""};boolean[] free={false};SettingsValues v=new SettingsValues(a,new SettingsOrderEditor(),()->free[0],top->top,()->status[0]);
  for(String lang:new String[]{"en","ru"}){
   AppLanguage.select(lang);boolean ru=lang.equals("ru");
   check(v.menu(MEDIA,1)[0].equals(ru?"Спрашивать каждый раз":"Ask Every Time"),"default service initially asks every time");
   a.prefs.data.put(MovieService.KEY,"seena");check(v.menu(MEDIA,1)[0].equals("Seena")&&v.menu(MOVIE_SERVICE,5)[3].equals(ru?"Выбрано":"Selected"),"service value and accessible selected state");a.prefs.data.remove(MovieService.KEY);
   String[] general=v.menu(GENERAL,8);check(general.length==8&&general[0].isEmpty()&&general[3].equals("City"),"general ordering");
   check(general[2].equals(AppLanguage.current().nativeName)&&general[1].equals(ru?"Авто":"Automatic"),"live language and theme");
   Prefs.unit="C";Prefs.theme="dark";Prefs.h24=true;general=v.menu(GENERAL,8);check(general[5].equals("°C")&&general[6].equals(ru?"24 ч":"24 h"),"units/hour");
   Prefs.unit="auto";Prefs.theme="auto";Prefs.h24=false;
   free[0]=true;check(v.menu(SYSTEM,4)[2].equals("tvOS"),"current launcher callback");
   check(v.menu(ACCESSIBILITY,3)[2].isEmpty(),"TV Settings action has no misplaced launcher value");
   free[0]=false;check(v.menu(SYSTEM,4)[2].equals(ru?"Встроенный":"Built-in"),"built-in launcher value follows moved row");free[0]=true;
   a.prefs.data.put("auto_update",true);check(v.menu(UPDATES,2)[1].equals(SettingsValues.onOff(true)),"auto update value");
   a.prefs.data.put("video_dream_source","na");String[] saver=v.menu(SCREENSAVER,5);check(saver[0].equals("National Geographic")&&saver[4].isEmpty(),"saver source and action row");
   check(v.menu(WEATHER,2)[0].equals(SettingsValues.onOff(true))&&v.menu(WEATHER,2)[1].equals(SettingsValues.onOff(false)),"independent weather callback");
   check(v.menu(MENU_BUTTONS,3)[0].equals("#4")&&v.menu(MENU_BUTTONS,3)[1].equals(SettingsValues.onOff(false))&&v.menu(MENU_BUTTONS,3)[2].isEmpty(),"button order and VPN footer");
   status[0]="Searching";check(v.settings()[4].equals("Searching"),"live city status");status[0]="";Prefs.city="";check(v.settings()[4].equals("—"),"empty city fallback");Prefs.city="City";
   check(v.settings().length==6&&SettingsValues.randomRow()==5,"stable category rows without quote/coin controls");
   String[] mappings=SettingsValues.keyValues(Arrays.asList("", "youtube"),Arrays.asList("old empty label", "YT"));
   check(mappings[1].equals("YT"),"configured remote retains app label");
   for(int index:new int[]{0,2,3})check(mappings[index].equals(ru?"Не настроено":"Not configured"),"missing and empty remote mappings use one live localized status");
   mappings=SettingsValues.keyValues(Collections.emptyList(),Collections.emptyList());
   for(String mapping:mappings)check(mapping.equals(ru?"Не настроено":"Not configured"),"empty catalog uses same status for all four buttons");
   a.prefs.data.put("device_name","Living room");LocalNetwork.info=new LocalNetwork.Info();LocalNetwork.info.ethernet=true;
   String[] about=v.menu(ABOUT,8);check(about[0].equals("Living room")&&about[1].equals("Test TV")&&about[2].equals("SN123")&&about[3].equals("1.1.0")&&about[5].equals("1920 × 1080")&&about[6].equals("Ethernet")&&about[7].equals("192.168.0.111"),"about values and physical network address");
   LocalNetwork.info.ethernet=false;a.wifi.info.ssid="\\\"Home\\\"";check(v.menu(ABOUT,8)[6].equals("Home"),"SSID quotes removed");
   a.pm.fail=true;android.os.Build.deny=true;about=v.menu(ABOUT,8);check(about[3].equals("—")&&about[2].equals(AppStrings.get(R.string.settings_unavailable)),"platform permission failures preserve fallbacks");
   check(v.vpnApp().equals(ru?"Не настроено":"Not configured"),"unset VPN status");
   a.prefs.data.put("vpn_app","missing");check(v.vpnApp().equals(AppStrings.get(R.string.settings_not_found)),"missing VPN label fallback");a.pm.fail=false;android.os.Build.deny=false;a.prefs.data.remove("vpn_app");
  }
  System.out.println("PASS: production settings values, live locale, controls, dynamic rows, device/network data and platform fallbacks");
 }
}'''
}
for name in ['SettingsValues','SettingsContent']:sources['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-settings-values-') as tmp:
 root=Path(tmp);write(root)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ValuesHarness'],check=True)
