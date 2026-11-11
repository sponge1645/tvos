"""Picker model and production view entry points: selection, labels and scrolling stay aligned."""
from pathlib import Path
import subprocess
import tempfile
from tmdb_metadata_fixture import block
source = Path('app/src/io/tvos/SettingsActivity.java').read_text()
files = {
 'io/tvos/SettingsPicker.java': Path('app/src/io/tvos/SettingsPicker.java').read_text(),
 'io/tvos/settings/SettingsListWindow.java': Path('app/src/io/tvos/settings/SettingsListWindow.java').read_text(),
 'io/tvos/setup/SetupCountries.java': '''package io.tvos.setup;import java.util.Locale;
public class SetupCountries {public static String[] codes(Locale l){String[] out=new String[30];for(int i=0;i<30;i++)out[i]="C"+i;return out;}
 public static String name(String code,Locale l){return l.getLanguage()+":"+code;}}''',
 'io/tvos/PickerHarness.java': '''package io.tvos;import java.util.*;import io.tvos.settings.SettingsListWindow;
public class PickerHarness {
 static final int PICK_REGION=-4,PICK_CITY=-1,PICK_VPN=-2,PICK_TV=-3,PICKER=9;
 int screen,focus;final SettingsPicker picker=new SettingsPicker();
 final SettingsListWindow listWindow=new SettingsListWindow(10);
 static class Spring {float value;void snap(float v){value=v;}}final Spring listScroll=new Spring();
 final List<String> appPkgs=new ArrayList<>(),appLabels=new ArrayList<>();final List<String[]> cities=new ArrayList<>();
 static class Prefs {static final String[] KEYS={"key0","key1"};static String region="C24",pkg="p22";
  static String recommendationRegion(){return region;}static String keyApp(String key){return pkg;}}
 static class AppLanguage {static Locale locale=Locale.ENGLISH;static AppLanguage current(){return new AppLanguage();}}
 static class Store {String pkg="p12";String getString(String key,String fallback){return pkg;}}final Store store=new Store();Store localPrefs(){return store;}
 static class R {static class string {static final int settings_not_configured=1,settings_not_selected=2;}}
 static class AppStrings {static String get(int id){return id==2?"Not selected":"Not configured";}}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
''' + ''.join(block(source, sig) for sig in ['        private void openCountryPicker(', '        private void openPicker(int which)', '        private void showPicker(']) + '''
 public static void main(String[] args){
  PickerHarness h=new PickerHarness();h.openCountryPicker();
  check(h.picker.kind==PICK_REGION&&h.picker.current==24&&h.picker.keys.size()==30,"country selected by stable code");
  check(h.screen==PICKER&&h.listWindow.absoluteRow(h.focus)==24&&h.listScroll.value==h.listWindow.offset(),"saved country opens visible and scroll starts at same offset");
  check(h.picker.labels.get(24).equals("en:C24"),"country labels use current locale");
  AppLanguage.locale=new Locale("ru");Prefs.region="missing";h.openCountryPicker();
  check(h.picker.current==-1&&h.focus==0&&h.listWindow.offset()==0&&h.picker.labels.get(0).equals("ru:C0"),"missing country resets viewport and localized labels");
  for(int i=0;i<30;i++){h.appPkgs.add("p"+i);h.appLabels.add("App"+i);}
  h.openPicker(2);check(h.picker.current==22&&h.listWindow.absoluteRow(h.focus)==22&&h.picker.kind==2,"remote picker retains app and button identity");
  check(h.picker.labels.get(0).equals("Not configured")&&h.appLabels.get(0).equals("App0")&&h.picker.keys.isEmpty(),"display override preserves source app list and removes country keys");
  h.openPicker(PICK_VPN);check(h.picker.current==0&&h.picker.keys.equals(Arrays.asList("","su.happ.proxyutility","llc.itdev.incy"))&&h.picker.labels.equals(Arrays.asList("Not selected","HAPP","INCY")),"VPN is a fixed allowlist independent of installed apps");
  for(int i=0;i<3;i++){h.store.pkg=h.picker.keys.get(i);h.openPicker(PICK_VPN);check(h.picker.current==i&&h.listWindow.absoluteRow(h.focus)==i,"VPN saved package and checkmark remain aligned");}
  h.cities.add(new String[]{"City","Name","1","2","3","Zone"});h.openPicker(PICK_CITY);
  check(h.picker.current==-1&&h.focus==0&&h.listWindow.offset()==0&&h.picker.labels.equals(Arrays.asList("City")),"city opens with no stale saved marker or app labels");
  h.picker.tvProviders(PICK_TV,Arrays.asList(new String[]{"a","TV A"},new String[]{"b","TV B"}),"b","Automatic");h.showPicker();
  check(h.picker.current==2&&h.picker.keys.get(h.listWindow.absoluteRow(h.focus)).equals("b"),"TV labels, keys and checkmark share row index");
  h.picker.tvProviders(PICK_TV,Collections.emptyList(),"","Auto");h.showPicker();
  check(h.picker.current==0&&h.picker.keys.equals(Arrays.asList("automatic"))&&h.picker.labels.equals(Arrays.asList("Auto")),"automatic TV fallback has no stale provider entries");
  h.picker.tvProviders(PICK_TV,Collections.emptyList(),"removed","Auto");check(h.picker.current==-1,"removed TV app does not get a false checkmark");
  h.appPkgs.clear();h.appLabels.clear();h.openPicker(1);check(h.picker.labels.isEmpty()&&h.picker.current==-1&&h.focus==0,"empty app result remains safe");
  System.out.println("PASS: actual picker entry points, country locale/code, remote/VPN/city/TV selections, viewport and stale-state isolation");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='tvos-settings-picker-') as tmp:
 root=Path(tmp)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.PickerHarness'],check=True)
