"""Exercise production dialog copy and dynamic settings text with real XML translations."""
from pathlib import Path
import re,subprocess,tempfile
from settings_strings_fixture import write,tables
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(signature):
 a=source.index(signature);b=source.index('{',a);depth=1;i=b+1
 while depth:depth+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
def popup():
 a=source.index('            String[] lines;',source.index('private void drawUpdatePopup('))
 b=source.index('            float lineY',a)
 c=source.index('            String[] buttons =',b);d=source.index('            float buttonTop',c)
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
 int updatePopup;Object getContext(){return this;}String versionName(Object c){return "tvOS 1.1";}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''
methods=''.join(method(s) for s in ['        private static String metric(', '        private static String ago(', '        private String[] keyLabels()', '        private String menuButtonLabel(', '        private String categoryLabel(', '        private static String onOff('])
tests='''
 public static void main(String[] args){
  LocalizedSettingsHarness h=new LocalizedSettingsHarness();
  Locale.setDefault(Locale.GERMAN); // app language must override the process locale.
  for(boolean russian:new boolean[]{false,true}){
   Ui.ru=russian;AppLanguage.select(Ui.ru?"ru":"en");
   check(h.onOff(true).equals(russian?"Вкл.":"On"),"enabled value");
   check(h.menuButtonLabel(5).equals(russian?"Сеть":"Network"),"network reorder label");
   check(h.categoryLabel(4).equals(russian?"Случайный фильм":"Random Movie"),"category label");
   JSONObject m=new JSONObject();m.v.put("homeMs",12L);m.v.put("memAvailableMB",2048L);m.v.put("homePssMB",32L);
   check(metric(m,"homeMs").equals(russian?"12 мс":"12 ms"),"metric suffix keeps space");
   check(metric(m,"memAvailableMB").equals(russian?"2,00 ГБ":"2.00 GB"),"metric follows app number format");
   check(metric(m,"homePssMB").equals(russian?"32 МБ":"32 MB"),"memory suffix");
   check(metric(null,"homeMs").equals("—"),"missing metric fallback");
   h.updatePopup=2;String[][] copy=h.popup();
   check(copy[0].length==5&&copy[1].length==2,"update dialog keeps line/button geometry");
   check(copy[0][0].equals(russian?"Доступно обновление до версии 1.2.3.":"Update 1.2.3 is available."),"version slot");
   check(copy[1][0].equals(russian?"Загрузить и установить":"Download and Install"),"install choice");
   h.updatePopup=6;copy=h.popup();check(copy[0][1].equals(russian?"Версия 1.2.3":"Version 1.2.3"),"downloaded version keeps space");
   check(copy[1][0].equals(russian?"Установить":"Install"),"downloaded button");
   SelfUpdate.status.percent=47;h.updatePopup=5;check(h.popup()[0][1].equals("47%"),"progress is unchanged");
   SelfUpdate.status.percent=-1;check(h.popup()[0][1].equals(""),"unknown progress stays blank");
   h.updatePopup=3;SelfUpdate.status.message="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
   copy=h.popup();check(copy[0][1].length()==44&&copy[0][2].equals("STUVWXYZ"),"error wrapping unchanged");
   h.updatePopup=1;copy=h.popup();check(copy[0][1].equals("1.1")&&copy[1][0].equals("OK"),"installed version and OK");
   for(int days:new int[]{2,5,11,21,22,25,111,112,114}){
    String old=days+" days ago";String expected=russian?Russian.text(old):old;
    check(ago(System.currentTimeMillis()-days*86400000L-60000).equals(expected),"plural day age: "+days);
   }
   check(ago(System.currentTimeMillis()-180000).equals(russian?"3 мин назад":"3 min ago"),"minute age");
   check(ago(System.currentTimeMillis()-3600000*3L-60000).equals(russian?"3 ч назад":"3 h ago"),"hour age");
  }
  System.out.println("PASS: RU/EN update dialogs, version/progress/error states, metrics, menu labels and plural ages");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-localized-settings-') as tmp:
 root=Path(tmp);write(root)
 (root/'LocalizedSettingsHarness.java').write_text(header+methods+popup()+tests)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.LocalizedSettingsHarness'],check=True)
