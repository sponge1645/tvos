"""Exercise real home label/weather/network methods against the RU/EN XML resources."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write as write_strings
from tmdb_metadata_fixture import block

source=Path('app/src/io/tvos/HomeView.java').read_text()
assert 'Ui.tr(' not in source
assert 'TABS_RU' not in source
fields=next(line for line in source.splitlines() if 'private static final EnumMap<AppLanguage, String[]> tabLabels' in line)
methods=''.join(block(source,s) for s in [
 '    private static String[] tabs()', '    private String networkLabel()',
 '    private String weatherText()'
])
methods+=block(Path('app/src/io/tvos/HomeWidgets.java').read_text(),'    private void refreshDrawerText()')
harness='''package io.tvos;
import java.util.*;
public class HomeStringsHarness {
 static class Ui {static boolean ru;static String language="en";static Locale locale(){return ru?new Locale("ru","RU"):Locale.US;}
  static String time(long at){return at==1?"06:00":"18:00";}}
 static class Prefs {static int version;static String city="";static String city(){return city;}}
 static class NetworkStatus {static final int WIFI=1,ETHERNET=2;int current;int state(){return current;}}
 static class HomeData {
  static final String[] COINS={"btc"};Weather weather=new Weather();long pricesAt;double[] prices={0},changes={0};
  static class Weather {double temp=10,max=20,min=5;int code;boolean isDay=true;long sunrise=1,sunset=2;
   List<Long> hours=new ArrayList<>(Collections.singletonList(9L));List<Double> hourTemp=new ArrayList<>(Collections.singletonList(11.0));}
  static String degrees(double n){return (int)n+"°";}static String hour(long h){return String.format(Locale.US,"%02d:00",h);}
  static String condition(int code,boolean day){return Ui.ru?"Ясно":"Clear";}
  static String stale="";static String staleness(long at,long window){return stale;}
  static String brl(double value){return "price";}static String change(double value){return "change";}
 }
 final HomeData data=new HomeData();final NetworkStatus networkStatus=new NetworkStatus();
 int textPrefs=-1;String textLanguage="";HomeData.Weather textWeather;long textPricesAt=-1,textMinute=-1;
 String weatherTitle,nowTemp,highLow,sunriseText,sunsetText,staleText;
 final String[] hourText=new String[6],hourTempText=new String[6],priceText=new String[1],changeText=new String[1];
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
'''+fields+methods+'''
 public static void main(String[] args){
  Locale.setDefault(Locale.FRANCE);HomeStringsHarness h=new HomeStringsHarness();
  String[] english=null,russian=null;
  for(boolean ru:new boolean[]{false,true,false,true}){
   Ui.ru=ru;AppLanguage.select(Ui.ru?"ru":"en");Ui.language=ru?"ru":"en";Prefs.city="";Prefs.version++;HomeData.stale="";
   h.data.weather=new HomeData.Weather();
   String[] labels=tabs();check(Arrays.equals(labels,ru?new String[]{"Новинки","Фильмы","Детективы","Сериалы","🎲"}:new String[]{"New","Movies","Detectives","Shows","🎲"}),"tab order/copy");
   if(ru){if(russian==null)russian=labels;else check(labels==russian,"Russian array reused after language switch");}
   else{if(english==null)english=labels;else check(labels==english,"English array reused after language switch");}
   for(int i=0;i<10000;i++)check(tabs()==labels,"tabs create no per-frame array");
   h.networkStatus.current=1;check(h.networkLabel().equals(ru?"Wi-Fi, интернет доступен. Открыть настройки сети":"Wi-Fi, internet available. Open network settings"),"Wi-Fi spoken state");
   h.networkStatus.current=2;check(h.networkLabel().equals(ru?"Ethernet, интернет доступен. Открыть настройки сети":"Ethernet, internet available. Open network settings"),"Ethernet spoken state");
   h.networkStatus.current=0;check(h.networkLabel().equals(ru?"Нет интернета. Открыть настройки сети":"No internet. Open network settings"),"offline spoken state");

   h.refreshDrawerText();check(h.weatherTitle.equals(ru?"Погода":"Weather"),"weather heading");
   check(h.highLow.equals(ru?"Макс. 20°   Мин. 5°":"H:20°   L:5°"),"high/low keeps three spaces and exact prefixes");
   check(h.sunriseText.equals(ru?"Восход 06:00":"Sunrise 06:00"),"sunrise prefix keeps space");
   check(h.sunsetText.equals(ru?"Закат 18:00":"Sunset 18:00"),"sunset prefix keeps space");
   check(h.staleText.equals(ru?"изменение за 24 ч":"24h change"),"fresh quote status");
   String prepared=h.highLow;for(int i=0;i<1000;i++){h.refreshDrawerText();check(h.highLow==prepared,"weather template formatted only on source/settings change");}
   check(h.weatherText().equals(ru?"Погода. 10°, Ясно. Максимум 20°, минимум 5°. Ближайшие часы: 09:00 11°. Восход 06:00. Закат 18:00.":"Weather. 10°, Clear. High 20°, low 5°. Next hours: 09:00 11°. Sunrise 06:00. Sunset 18:00."),"weather spoken punctuation and spaces");
   Prefs.city="Tokyo";Prefs.version++;h.refreshDrawerText();check(h.weatherTitle.equals("Tokyo")&&h.weatherText().startsWith("Tokyo."),"user-selected city preserved");
   HomeData.stale="cached status";Prefs.version++;h.refreshDrawerText();check(h.staleText.equals("cached status"),"staleness is not prefixed or retranslated");
   HomeData.Weather fresh=new HomeData.Weather();fresh.max=22;h.data.weather=fresh;h.refreshDrawerText();
   check(h.highLow.equals(ru?"Макс. 22°   Мин. 5°":"H:22°   L:5°"),"new weather snapshot refreshes text");
   Prefs.city="";fresh.temp=Double.NaN;check(h.weatherText().equals(ru?"Погода. Сейчас нет данных о погоде.":"Weather. No weather data right now."),"missing weather fallback");

   check(AppStrings.get(R.string.home_movies_loading_toast,(ru?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).equals(ru?"Загружаем фильмы…":"Loading movies…"),"toast and roulette keep their distinct Russian wording");
   check(AppStrings.get(R.string.home_roulette_loading,(ru?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).equals(ru?"Загрузка фильмов…":"Loading movies…"),"roulette wording");
   check(AppStrings.get(R.string.home_roulette_title,(ru?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).equals(ru?"🎲  Случайный фильм":"🎲  Random Movie"),"dice title keeps double space");
   check(AppStrings.get(R.string.home_dock_edit_hint,(ru?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).equals(ru?"← → – переместить. ОК – сохранить. Назад – отменить.":"← → – move. OK – save. Back – cancel."),"Dock hint localized");
   check(AppStrings.get(R.string.home_spoken_of,(ru?AppLanguage.RUSSIAN:AppLanguage.ENGLISH)).equals(ru?" из ":" of "),"spoken count separators");
  }
  System.out.println("PASS: real home tabs/network/weather methods, cached labels/templates, language switches, missing/late data and significant whitespace");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-home-strings-') as tmp:
 root=Path(tmp);write_strings(root);(root/'HomeStringsHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.HomeStringsHarness'],check=True)
