"""Exercise production settings content and view delegation in both supported languages."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import subprocess
import tempfile
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
assert 'screen == UPDATES && focus == 0 ? updateDetailsText()' in method('        private void drawTileDescription(')
assert 'SettingsContent.description(screen, focus, picker.kind, settingsGroup, buttonEditor.size())' in source
harness='''package io.tvos;
import java.util.*;import static io.tvos.settings.SettingsScreen.*;import io.tvos.settings.SettingsScreen;
public class ContentHarness {
 static class Ui {static boolean ru;}
 int screen=MAIN,focus,settingsGroup;final SettingsPicker picker=new SettingsPicker();int dynamicRows=6;final io.tvos.settings.SettingsListWindow listWindow=new io.tvos.settings.SettingsListWindow(10);
 final List<Integer> categoryEditor=new ArrayList<>(),buttonEditor=new ArrayList<>(),dockEditor=new ArrayList<>();
 static class Prefs {static final String[] KEYS=new String[4];static boolean automatic;static boolean automaticRegion(){return automatic;}}String[] settingLabels(){return new String[7];}boolean vpnTopEnabled(){return true;}
 String[] menuButtonLabels(){String[] rows=new String[dynamicRows+1];Arrays.fill(rows,"dynamic");rows[dynamicRows]="VPN";return rows;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''+method('        private String screenTitle()')+method('        private String[] menuLabels()')+method('        private int rowCount()')+method('        private boolean menuChevronVisible(')+method('        private int parentScreen(')+'''
 public static void main(String[] args){
  ContentHarness h=new ContentHarness();
  int[] screens={MAIN,MEDIA,MOVIE_SERVICE,HOME_SCREEN,GENERAL,WIDGETS,WEATHER,DATE_TIME,UPDATES,SYSTEM,RESET,TOP_PANEL,APPS_DOWNLOADS,ACCESSIBILITY,CITY,SCREENSAVER,ABOUT,LANGUAGE,SLEEP_TIMER};
  int[] rows={11,3,5,3,6,2,3,1,2,4,2,4,8,3,2,6,9,2,7};
  check(MAIN==0&&GENERAL==6&&SCREENSAVER==10&&MENU_BUTTONS==13&&CATEGORY_EDIT==20&&PICK_CITY==0&&PICK_VPN==-1,"stable screen/picker IDs");
  for(boolean russian:new boolean[]{false,true}){
   Ui.ru=russian;AppLanguage.select(Ui.ru?"ru":"en");
   for(int i=0;i<screens.length;i++){
    h.screen=screens[i];String[] labels=h.menuLabels();check(labels.length==rows[i]&&h.rowCount()==rows[i],"menu labels and actual focus row count for "+h.screen);
    check(io.tvos.settings.SettingsScreen.fixedRows(h.screen)==(h.screen==MOVIE_SERVICE?-1:rows[i]),"fixed rows owned by screen metadata");
    for(String label:labels)check(label!=null&&!label.isEmpty(),"nonempty label");
    labels[0]="modified";check(!h.menuLabels()[0].equals("modified"),"caller cannot corrupt future labels");
   }
   check(h.parentScreen(HOME_CHOICE)==SYSTEM,"launcher choice transition belongs to System");
   for(int page:new int[]{HOME_VIEW,HOME_CARD_STYLE,DATE_TIME,UPDATES,ACCESSIBILITY}){h.screen=page;for(int row=0;row<h.rowCount();row++)check(!h.menuChevronVisible(row),"value/action rows have no navigation arrows");}
   check(SettingsContent.description(TOP_PANEL,0,0,0,0,AppLanguage.current()).equals(AppStrings.get(R.string.settings_home_screen_help)),"Home parent describes screen settings");
   check(SettingsContent.weatherDescription(false,false,AppLanguage.current()).replace((char)160, (char)32).equals(russian?"Не показывать погоду.":"Do not show weather."),"None weather help matches hidden state");
   check(SettingsContent.weatherDescription(true,false,AppLanguage.current()).equals(AppStrings.get(R.string.settings_weather_home_help)),"Home weather help");
   check(SettingsContent.weatherDescription(false,true,AppLanguage.current()).equals(AppStrings.get(R.string.settings_weather_bottom_help)),"bottom weather help");
   check(SettingsContent.weatherDescription(true,true,AppLanguage.current()).equals(AppStrings.get(R.string.settings_weather_both_help)),"both weather help");
   h.screen=GENERAL;for(int row=0;row<6;row++)check(h.menuChevronVisible(row)==(row!=5),"only General destinations have chevrons");
   h.screen=CITY;check(!h.menuChevronVisible(0)&&h.menuChevronVisible(1),"region switch has no arrow, manual picker retains arrow");
   Prefs.automatic=true;check(!h.menuChevronVisible(1),"automatic region hides manual picker arrow");Prefs.automatic=false;
   h.screen=SYSTEM;check(h.menuChevronVisible(2)&&!h.menuChevronVisible(3),"launcher has submenu arrow, restart is an action");
   h.screen=ACCESSIBILITY;check(!h.menuChevronVisible(2),"TV Settings action has no ghost launcher chevron");
   h.screen=MAIN;check(h.menuLabels()[2].equals(russian?"Заставка":"Screen Saver"),"current language read at call time");
   h.screen=WIDGETS;check(Arrays.equals(h.menuLabels(),russian?new String[]{"Погода","Дата"}:new String[]{"Weather","Date"}),"widget menu contains only weather and date");
   h.screen=ABOUT;check(h.screenTitle().equals(russian?"Об этом устройстве":"About"),"About heading");
   check(h.menuLabels()[2].equals(russian?"Серийный номер":"Serial Number"),"capitalization preserved");
   h.screen=APPS_DOWNLOADS;String[] expected={"AirPlay","Apple Music","TizenTube","INCY","HAPP","Seena","Lampa","KinoPub"};
   for(int i=0;i<8;i++){
    check(h.menuLabels()[i].endsWith(expected[i]),"download app order");
    String description=SettingsContent.description(h.screen,i,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH));
    check(description!=null&&(i==0||description.contains("\\n")),"each download keeps its two-line description");
   }
   check(SettingsContent.description(APPS_DOWNLOADS,8,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH))==null,"no stale description beyond downloads");
   h.screen=PICKER;h.picker.kind=PICK_CITY;check(h.screenTitle().equals(russian?"Город":"City"),"city picker title");
   h.picker.kind=PICK_VPN;check(h.screenTitle().equals(russian?"VPN-служба":"VPN"),"VPN picker title");
   h.picker.kind=1;check(h.screenTitle().equals(russian?"Выбор приложения":"Choose app"),"remote picker title");
   check(SettingsContent.description(GENERAL,4,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).contains(russian?"рекомендац":"recommend"),"country help describes recommendations");
   check(SettingsContent.description(GENERAL,3,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH))==null,"neighbor row has no inherited region description");
   String guidance=SettingsContent.description(SCREENSAVER,4,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)); check(guidance.contains(russian?"Пункт управления":"Control Center") && guidance.contains(russian?"Погода":"Weather") && guidance.contains(russian?"Город":"City") && !guidance.contains(russian?"Регион":"Region"),"screensaver weather guidance points to Control Center and Home Screen, Weather and City");
   check(SettingsContent.description(UPDATES,0,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH))==null,"dynamic update data stays with view");
   for(int count=0;count<=6;count++){
    h.screen=MENU_BUTTONS;h.dynamicRows=count;check(h.menuLabels().length==count+1,"dynamic menu labels delegated to view");
    check(SettingsContent.description(MENU_BUTTONS,count,0,0,count,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).contains("VPN"),"VPN footer follows live row count");
    if(count>0)check(!SettingsContent.description(MENU_BUTTONS,0,0,0,count,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).contains("VPN"),"ordinary button retains reorder guidance");
   }
   h.screen=HOME_SETTINGS;h.settingsGroup=WIDGETS;check(h.screenTitle().equals(russian?"Основные":"General"),"widget subgroup title");
   h.settingsGroup=SYSTEM;check(h.screenTitle().equals(russian?"Разделы для подборки":"Collection Sections"),"category subgroup title");
   check(!SettingsContent.description(HOME_SETTINGS,0,0,SYSTEM,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).equals(SettingsContent.description(HOME_SETTINGS,0,0,GENERAL,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH))),"subgroup-specific guidance");
   check(SettingsContent.description(MAIN,3,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).contains("AirPlay"),"AirPlay dependency description");
   check(SettingsContent.description(MAIN,7,0,0,0,(russian?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).contains("Dock"),"Dock description");
  }
  for(int page:new int[]{MOVIE_SERVICE,CATEGORY_EDIT,MENU_BUTTONS,HOME_SETTINGS,PICKER,SHELF_EDIT,REMOTE_KEYS,999})check(io.tvos.settings.SettingsScreen.fixedRows(page)==-1,"dynamic rows remain adapter-owned");
  h.screen=999;check(h.rowCount()==4,"unknown row-count fallback retained");
  h.screen=PICKER;for(int i=0;i<15;i++)h.picker.labels.add("app");check(h.rowCount()==10,"live picker count retains capacity");h.picker.labels.clear();check(h.rowCount()==0,"empty picker has no rows");
  h.screen=CATEGORY_EDIT;h.categoryEditor.add(1);check(h.rowCount()==1,"live category count remains dynamic");
  h.screen=MENU_BUTTONS;h.buttonEditor.add(1);check(h.rowCount()==2,"live buttons retain VPN footer");
  System.out.println("PASS: settings titles, row order/count, RU/EN descriptions, dynamic menu/VPN rows, live language and stable IDs");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-settings-content-') as tmp:
 root=Path(tmp);(root/'ContentHarness.java').write_text(harness)
 (root/'SettingsContent.java').write_text(Path('app/src/io/tvos/SettingsContent.java').read_text())
 (root/'SettingsListWindow.java').write_text(Path('app/src/io/tvos/settings/SettingsListWindow.java').read_text())
 write_strings(root)
 subprocess.run(['java','com.sun.tools.javac.Main','app/src/io/tvos/SettingsPicker.java','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ContentHarness'],check=True)
