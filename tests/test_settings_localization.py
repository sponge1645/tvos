"""Exercise production dialog copy and dynamic settings text with real XML translations."""
from pathlib import Path
import re,subprocess,tempfile
from settings_strings_fixture import write,tables
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(signature):
 text=source
 if signature not in text:
  text=Path("app/src/io/tvos/SettingsValues.java").read_text();signature=signature.replace("        private ","    static ").replace("static static ","static ")
 return extract(text,signature)
def extract(source,signature):
 a=source.index(signature);b=source.index('{',a);depth=1;i=b+1
 while depth:depth+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
def popup():
 source=Path('app/src/io/tvos/SettingsUpdateDialog.java').read_text()
 a=source.index('        String[] lines;',source.index('void draw('))
 b=source.index('        float lineY',a)
 c=source.index('        String[] buttons =',b);d=source.index('        float buttonTop',c)
 return 'String[][] popup(){'+source[a:b]+source[c:d]+'return new String[][]{lines,buttons};}'
# Every locale has the same formatting arguments; translators can reorder them.
en,ru=tables()
for key in en:
 assert sorted(re.findall(r'%\d+\$[sd]',en[key]))==sorted(re.findall(r'%\d+\$[sd]',ru[key])),key
header='''package io.tvos;import java.util.*;
public class LocalizedSettingsHarness {
 static class Ui {static boolean ru;static Locale locale(){return ru?new Locale("ru","RU"):Locale.US;}}
 static class JSONObject {Map<String,Long> v=new HashMap<>();boolean has(String k){return v.containsKey(k);}long optLong(String k){return v.get(k);}}
 static class SelfUpdate {static final Status status=new Status();static SelfUpdate get(Object c){return new SelfUpdate();}Status status(){return status;}static class Status{Release release=new Release();String message="";int percent;}static class Release{String versionName="1.2.3";}}
 int updatePopup;Object activity=this;Object getContext(){return this;}String versionName(Object c){return "tvOS 1.1";}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
methods=''.join(method(s) for s in ['        private String[] keyLabels()', '        private String menuButtonLabel(', '        private String categoryLabel(', '        private static String onOff('])
tests='''
 public static void main(String[] args){
  LocalizedSettingsHarness h=new LocalizedSettingsHarness();
  Locale.setDefault(Locale.GERMAN); // app language must override the process locale.
  for(boolean russian:new boolean[]{false,true}){
   Ui.ru=russian;AppLanguage.select(Ui.ru?"ru":"en");
   check(h.onOff(true).equals(russian?"Вкл.":"On"),"enabled value");
   check(h.menuButtonLabel(5).equals(russian?"Сеть":"Network"),"network reorder label");
   check(h.categoryLabel(4).equals(russian?"Случайный фильм":"Random Movie"),"category label");
   h.updatePopup=2;String[][] copy=h.popup();
   check(copy[0].length==4&&copy[1].length==2,"update dialog keeps line/button geometry");
   check(copy[0][0].equals(russian?"Доступно обновление до версии 1.2.3.":"Update 1.2.3 is available."),"version slot");
   check(copy[1][0].equals(russian?"Загрузить и установить":"Download and Install"),"install choice");
   h.updatePopup=6;copy=h.popup();check(copy[0][1].equals(russian?"Версия 1.2.3":"Version 1.2.3"),"downloaded version keeps space");
   check(copy[1][0].equals(russian?"Установить":"Install"),"downloaded button");
   // Download copy now belongs to SettingsUpdateProgress, covered by its whole-class regression.
   h.updatePopup=3;SelfUpdate.status.message="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
   copy=h.popup();check(copy[0][1].length()==44&&copy[0][2].equals("STUVWXYZ"),"error wrapping unchanged");
   h.updatePopup=1;copy=h.popup();check(copy[0][0].endsWith("tvOS 1.1")&&copy[1][0].equals("OK"),"installed version and OK");
  }
  System.out.println("PASS: RU/EN update dialogs, version/error states, menu labels and resource argument parity");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-localized-settings-') as tmp:
 root=Path(tmp);write(root)
 (root/'LocalizedSettingsHarness.java').write_text(header+methods+popup()+tests)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.LocalizedSettingsHarness'],check=True)
