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
assert 'screen == UPDATES ? updateDetailsText()' in method('        private void drawTileDescription(')
assert 'SettingsContent.description(screen, focus, pickFor, settingsGroup, menuButtonRows.size())' in source
harness='''package io.tvos;
import java.util.*;import static io.tvos.SettingsContent.*;
public class ContentHarness {
 static class Ui {static boolean ru;}
 int screen=MAIN,focus,pickFor,settingsGroup;int dynamicRows=6;
 String[] menuButtonLabels(){String[] rows=new String[dynamicRows+1];Arrays.fill(rows,"dynamic");rows[dynamicRows]="VPN";return rows;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''+method('        private String screenTitle()')+method('        private String[] menuLabels()')+'''
 public static void main(String[] args){
  ContentHarness h=new ContentHarness();
  int[] screens={MAIN,GENERAL,WIDGETS,WEATHER,DATE_TIME,UPDATES,SYSTEM,RESET,TOP_PANEL,APPS_DOWNLOADS,ACCESSIBILITY,VPN_SETTINGS,SCREENSAVER,ABOUT};
  int[] rows={10,8,3,2,1,2,3,2,2,7,4,2,5,8};
  check(MAIN==0&&GENERAL==6&&SCREENSAVER==10&&MENU_BUTTONS==13&&CATEGORY_EDIT==20&&PICK_CITY==0&&PICK_VPN==-1,"stable screen/picker IDs");
  for(boolean russian:new boolean[]{false,true}){
   Ui.ru=russian;AppLanguage.select(Ui.ru?"ru":"en");
   for(int i=0;i<screens.length;i++){
    h.screen=screens[i];String[] labels=h.menuLabels();check(labels.length==rows[i],"menu row count for "+h.screen);
    for(String label:labels)check(label!=null&&!label.isEmpty(),"nonempty label");
    labels[0]="modified";check(!h.menuLabels()[0].equals("modified"),"caller cannot corrupt future labels");
   }
   h.screen=MAIN;check(h.menuLabels()[1].equals(russian?"Заставка":"Screen Saver"),"current language read at call time");
   h.screen=ABOUT;check(h.screenTitle().equals(russian?"Об этом устройстве":"About"),"About heading");
   check(h.menuLabels()[2].equals(russian?"Серийный номер":"Serial Number"),"capitalization preserved");
   h.screen=APPS_DOWNLOADS;String[] expected={"AirPlay","Apple Music","TizenTube","INCY","Seena","Lampa","KinoPub"};
   for(int i=0;i<7;i++){
    check(h.menuLabels()[i].endsWith(expected[i]),"download app order");
    String description=SettingsContent.description(h.screen,i,0,0,0,AppLanguage.fromRussian(russian));
    check(description!=null&&description.contains("\\n"),"each download keeps its two-line description");
   }
   check(SettingsContent.description(APPS_DOWNLOADS,7,0,0,0,AppLanguage.fromRussian(russian))==null,"no stale description beyond downloads");
   h.screen=PICKER;h.pickFor=PICK_CITY;check(h.screenTitle().equals(russian?"Регион":"Region"),"city picker title");
   h.pickFor=PICK_VPN;check(h.screenTitle().equals("VPN"),"VPN picker title");
   h.pickFor=1;check(h.screenTitle().equals(russian?"Выбор приложения":"Choose app"),"remote picker title");
   check(SettingsContent.description(GENERAL,3,0,0,0,AppLanguage.fromRussian(russian)).equals(SettingsContent.description(PICKER,0,PICK_CITY,0,0,AppLanguage.fromRussian(russian))),"same weather region explanation");
   check(SettingsContent.description(GENERAL,2,0,0,0,AppLanguage.fromRussian(russian))==null,"neighbor row has no inherited region description");
   check(SettingsContent.description(SCREENSAVER,3,0,0,0,AppLanguage.fromRussian(russian)).contains(russian?"Регион":"Region"),"screensaver weather guidance");
   check(SettingsContent.description(UPDATES,0,0,0,0,AppLanguage.fromRussian(russian))==null,"dynamic update data stays with view");
   for(int count=0;count<=6;count++){
    h.screen=MENU_BUTTONS;h.dynamicRows=count;check(h.menuLabels().length==count+1,"dynamic menu labels delegated to view");
    check(SettingsContent.description(MENU_BUTTONS,count,0,0,count,AppLanguage.fromRussian(russian)).contains("VPN"),"VPN footer follows live row count");
    if(count>0)check(!SettingsContent.description(MENU_BUTTONS,0,0,0,count,AppLanguage.fromRussian(russian)).contains("VPN"),"ordinary button retains reorder guidance");
   }
   h.screen=HOME_SETTINGS;h.settingsGroup=WIDGETS;check(h.screenTitle().equals(russian?"Котировки":"Quotes"),"widget subgroup title");
   h.settingsGroup=SYSTEM;check(h.screenTitle().equals(russian?"Категории":"Categories"),"category subgroup title");
   check(!SettingsContent.description(HOME_SETTINGS,0,0,SYSTEM,0,AppLanguage.fromRussian(russian)).equals(SettingsContent.description(HOME_SETTINGS,0,0,GENERAL,0,AppLanguage.fromRussian(russian))),"subgroup-specific guidance");
   check(SettingsContent.description(MAIN,2,0,0,0,AppLanguage.fromRussian(russian)).contains("AirPlay"),"AirPlay dependency description");
   check(SettingsContent.description(MAIN,6,0,0,0,AppLanguage.fromRussian(russian)).contains("Dock"),"Dock description");
  }
  System.out.println("PASS: settings titles, row order/count, RU/EN descriptions, dynamic menu/VPN rows, live language and stable IDs");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-settings-content-') as tmp:
 root=Path(tmp);(root/'ContentHarness.java').write_text(harness)
 (root/'SettingsContent.java').write_text(Path('app/src/io/tvos/SettingsContent.java').read_text())
 write_strings(root)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ContentHarness'],check=True)
