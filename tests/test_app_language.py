"""Exercise the shared language catalog and real Prefs/Ui integration without Android."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block

ui=Path('app/src/io/tvos/Ui.java').read_text()
prefs=Path('app/src/io/tvos/Prefs.java').read_text()
data=Path('app/src/io/tvos/HomeData.java').read_text()
recs=Path('app/src/io/tvos/Recs.java').read_text()
fields=ui[ui.index('    static volatile String language ='):ui.index('    static Typeface')]
ui_methods=''.join(block(ui,s) for s in [
 '    static void applyLanguage()', '    static String tr(String unused, String english)',
 '    static String tr(String unused, String english, String unusedSpanish)',
 '    static Locale locale()', '    static String clock(', '    static String time(',
 '    static String date(', '    static String once('
])
harness='''package io.tvos;
import java.util.*;import java.text.*;import java.util.function.Supplier;
public class LanguageHarness {
 static class Config {static String LANGUAGE="ru-RU";}
 static class Store {String language;String getString(String key,String fallback){return language==null?fallback:language;}}
 static class Prefs {
  static Object app;static int version;static boolean h24;static Store store=new Store();
  static Store sp(){return store;}static void put(String key,String value){store.language=value;version++;}
  static boolean hour24(){return h24;}
'''+block(prefs,'    static String language()')+block(prefs,'    static void setLanguage(')+'''
 }
 static class Recs {static int changes;static String observed;static void languageChanged(){changes++;observed=AppLanguage.current().code;}}
 static class HomeData {static final HomeData instance=new HomeData();static List<String> codes=new ArrayList<>();
  static HomeData get(Object context){return instance;}void localizeCity(String code){codes.add(code);}}
 static class Ui {
'''+fields+'''
 private static final HashMap<String,String> FORMATTED=new HashMap<>();
'''+ui_methods+'}'+block(data,'    static String hour(')+block(recs,'    private static String lang()')+'''
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  Locale.setDefault(Locale.FRANCE);TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
  check(AppLanguage.count()==2,"only the two approved languages are selectable");
  for(String code:new String[]{"ru","RU"," ru-RU ","ru_RU","russian"})check(AppLanguage.from(code)==AppLanguage.RUSSIAN,"legacy Russian normalization "+code);
  for(String code:new String[]{"en","en-US"," es ","de","auto","unknown",""})check(AppLanguage.from(code)==AppLanguage.ENGLISH,"unsupported/English fallback "+code);
  check(AppLanguage.from(null)==AppLanguage.ENGLISH,"null language fallback");
  check(AppLanguage.RUSSIAN.nativeName.equals("Русский")&&AppLanguage.ENGLISH.nativeName.equals("English"),"names live in catalog");
  check(AppLanguage.RUSSIAN.locale.equals(new Locale("ru","RU"))&&AppLanguage.ENGLISH.locale.equals(Locale.US),"explicit app locales");
  check(AppLanguage.RUSSIAN.apiTag.equals("ru-RU")&&AppLanguage.ENGLISH.apiTag.equals("en-US"),"API tags derive from same locales");
  check(AppLanguage.step("ru",1)==AppLanguage.ENGLISH&&AppLanguage.step("en",1)==AppLanguage.RUSSIAN,"forward picker order");
  check(AppLanguage.step("ru",-1)==AppLanguage.ENGLISH&&AppLanguage.step("en",-1)==AppLanguage.RUSSIAN,"reverse picker wrap");
  check(AppLanguage.step("ru",Integer.MIN_VALUE)==AppLanguage.RUSSIAN&&AppLanguage.step("en",Integer.MAX_VALUE)==AppLanguage.RUSSIAN,"large directions wrap safely");
  check(Arrays.equals(AppLanguage.searchOrder("ru"),new String[]{"ru","en"})&&Arrays.equals(AppLanguage.searchOrder("en"),new String[]{"en","ru"}),"city lookup preference/fallback order");
  String[] detached=AppLanguage.searchOrder("ru");detached[0]="bad";check(AppLanguage.searchOrder("ru")[0].equals("ru"),"callers cannot mutate catalog search order");

  check(Prefs.language().equals("ru"),"boot reads configured language before Context exists");
  Ui.applyLanguage();check(AppLanguage.current()==AppLanguage.RUSSIAN&&Ui.ru&&!Ui.en&&!Ui.es&&!Ui.de,"Ui initialization synchronizes registry and compatibility flags");
  Prefs.app=new Object();Prefs.store.language="en";Ui.applyLanguage();
  check(AppLanguage.current()==AppLanguage.ENGLISH&&Ui.locale().equals(Locale.US),"persisted choice overrides config/system language");
  check(lang().equals("en-US"),"TMDB uses same selection");
  Prefs.setLanguage("en-US");check(Recs.changes==0&&HomeData.codes.isEmpty(),"equivalent normalized selection does not invalidate catalog/city");
  Prefs.setLanguage(" RU ");check(Recs.changes==1&&Recs.observed.equals("ru")&&HomeData.codes.equals(Arrays.asList("ru")),"effective language change reaches catalog/city after registry update");
  check(Ui.language.equals("ru")&&Ui.ru&&!Ui.en&&lang().equals("ru-RU"),"compatibility state and API tag remain synchronized");
  check(Ui.tr("ignored","Settings").equals("Настройки")&&Ui.tr("ignored","Settings","ignored").equals("Настройки"),"remaining Java-copy bridge uses shared selection");
  Prefs.setLanguage("ru-RU");check(Recs.changes==1,"equivalent Russian tag does not refetch");
  Prefs.setLanguage("es");check(Prefs.language().equals("en")&&AppLanguage.current()==AppLanguage.ENGLISH&&Recs.changes==2,"unsupported saved choice keeps old English fallback");
  check(HomeData.codes.equals(Arrays.asList("ru","en")),"city receives canonical language codes");
  check(Ui.tr("ignored","Settings").equals("Settings"),"legacy English bridge");

  long at=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).parse("2026-10-24 07:18:10").getTime();
  Prefs.h24=false;check(Ui.clock(at).equals("7:18 AM")&&Ui.time(at).equals("7:18 AM"),"English 12-hour formats preserved");
  check(Ui.date(at).equals("Saturday, October 24")&&hour(at).equals("7 AM"),"English full date and weather hour patterns preserved");
  int[] calls={0};String en=Ui.once("language-test",1,()->{calls[0]++;return Ui.locale().toLanguageTag();});
  Prefs.setLanguage("ru");Prefs.h24=true;
  check(Ui.clock(at).equals("07:18")&&Ui.time(at).equals("07:18"),"24-hour formats preserved");
  check(Ui.date(at).equals("суббота, 24 октября")&&hour(at).equals("07:18"),"Russian date/hour preserve language independently of French OS");
  String ru=Ui.once("language-test",1,()->{calls[0]++;return Ui.locale().toLanguageTag();});
  check(!en.equals(ru)&&calls[0]==2,"formatted cache is separated by canonical language");
  Prefs.setLanguage("en");check(Ui.once("language-test",1,()->"must not run")==en,"returning to a language reuses its cached text");
  check(Locale.getDefault().equals(Locale.FRANCE),"system locale unchanged");
  System.out.println("PASS: shared catalog, persistence/boot, picker order, API/city routing, legacy compatibility, date/hour formats and locale caches");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-app-language-') as tmp:
 root=Path(tmp);(root/'LanguageHarness.java').write_text(harness)
 write_strings(root)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.LanguageHarness'],check=True)
