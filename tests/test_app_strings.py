"""Execute actual app-language lookup/cache with Android stubs and real XML translations."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import sources
fixtures=sources()
resource_values=fixtures.pop('io/tvos/AppStrings.java').replace('final class AppStrings','public final class XmlTexts').replace('static String get','public static String get').replace('static String quantity','public static String quantity')
fixtures.update({
'io/tvos/XmlTexts.java':resource_values,
'io/tvos/AppStrings.java':Path('app/src/io/tvos/AppStrings.java').read_text(),
'android/util/SparseArray.java':'''package android.util;public class SparseArray<T>{private final java.util.Map<Integer,T> map=new java.util.HashMap<>();public T get(int id){return map.get(id);}public void put(int id,T value){map.put(id,value);}}''',
'android/content/res/Configuration.java':'''package android.content.res;public class Configuration {public java.util.Locale locale;public Configuration(java.util.Locale l){locale=l;}public Configuration(Configuration other){locale=other.locale;}public void setLocale(java.util.Locale l){locale=l;}}''',
'android/content/res/Resources.java':'''package android.content.res;public class Resources {
 public static int reads;private final Configuration config;public Resources(Configuration c){config=c;}public Configuration getConfiguration(){return config;}
 public String getString(int id){reads++;return io.tvos.XmlTexts.get(id,config.locale.getLanguage().equals("ru"));}
 public String getQuantityString(int id,int n){reads++;return io.tvos.XmlTexts.quantity(id,config.locale.getLanguage().equals("ru"),n);}
}''',
'android/content/Context.java':'''package android.content;import android.content.res.*;public class Context {
 public static int overrides;private final Resources resources;private final Context app;
 public Context(Resources r,Context a){resources=r;app=a;}public Context getApplicationContext(){return app==null?this:app;}
 public Resources getResources(){return resources;}public Context createConfigurationContext(Configuration c){
  if(app!=null)throw new AssertionError("retained Activity instead of application context");overrides++;return new Context(new Resources(c),null);}
}''',
'io/tvos/StringsHarness.java':'''package io.tvos;
import android.content.*;import android.content.res.*;import java.util.Locale;
public class StringsHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  Configuration system=new Configuration(new Locale("ru","RU"));Resources resources=new Resources(system);
  Context application=new Context(resources,null),activity=new Context(resources,application);AppStrings.init(activity);
  int id=R.string.settings_screen_saver;
  check(AppStrings.get(id,AppLanguage.ENGLISH).equals("Screen Saver"),"app English overrides Russian Android");
  check(system.locale.getLanguage().equals("ru"),"system configuration not mutated");
  int reads=Resources.reads,contexts=Context.overrides;
  for(int i=0;i<1000;i++)check(AppStrings.get(id,AppLanguage.ENGLISH).equals("Screen Saver"),"stable cached label");
  check(Resources.reads==reads&&Context.overrides==contexts,"steady frames create no locale contexts or resource lookups");
  check(AppStrings.get(id,AppLanguage.RUSSIAN).equals("Заставка")&&Context.overrides==2,"Russian resource uses separate cached context");
  system.setLocale(Locale.US);Locale.setDefault(Locale.US);
  check(AppStrings.get(id,AppLanguage.RUSSIAN).equals("Заставка"),"app Russian overrides English Android");
  check(AppStrings.get(id,AppLanguage.ENGLISH).equals("Screen Saver"),"switching language reuses correct text");
  check(AppStrings.get(R.string.settings_help_airplay,AppLanguage.ENGLISH).contains("\\n"),"description newline preserved");
  check(AppStrings.get(R.string.settings_help_launcher,AppLanguage.ENGLISH).contains("TV's"),"Android apostrophe escaping preserved");
  check(AppStrings.get(R.string.settings_position,AppLanguage.RUSSIAN).equals("Позиция "),"trailing space preserved for spoken position");
  check(AppStrings.get(R.string.settings_ms,AppLanguage.ENGLISH).equals(" ms"),"leading space preserved for metric suffix");
  check(String.format(Locale.US,AppStrings.get(R.string.settings_update_available,AppLanguage.ENGLISH),"1.2").equals("Update 1.2 is available."),"version placeholder follows translation");
  int before=Context.overrides;
  check(AppStrings.quantity(R.plurals.settings_days_ago,AppLanguage.RUSSIAN,22).equals("%1$d дня назад"),"plural lookup uses app Russian");
  check(AppStrings.quantity(R.plurals.settings_days_ago,AppLanguage.ENGLISH,22).equals("%1$d days ago"),"plural lookup uses app English");
  check(Context.overrides==before,"plural lookup reuses locale resources");
  check(AppStrings.get(R.string.home_roulette_title,AppLanguage.ENGLISH).equals("🎲  Random Movie"),"home title preserves two spaces");
  check(AppStrings.get(R.string.home_weather_high_low,AppLanguage.RUSSIAN).equals("Макс. %1$s   Мин. %2$s"),"home template preserves three spaces");
  check(AppStrings.get(R.string.home_spoken_of,AppLanguage.RUSSIAN).equals(" из "),"home count separator keeps both edge spaces");
  AppLanguage.select("ru");check(AppStrings.get(id).equals("Заставка"),"default lookup uses registry selection");
  check(AppStrings.quantity(R.plurals.settings_days_ago,22).equals("%1$d дня назад"),"default plural uses registry selection");
  AppLanguage.select("en");check(AppStrings.get(id).equals("Screen Saver"),"registry change immediately changes default lookup");
  int hotReads=Resources.reads;for(int i=0;i<1000;i++)AppStrings.get(id);
  check(Resources.reads==hotReads&&Context.overrides==2,"registry lookup keeps hot cache and context reuse");
  AppStrings.init(activity);check(Context.overrides==2,"initializing another Activity does not reset cached resources");
  System.out.println("PASS: real XML translations, app/system language independence, application context, cached lookups and escaping");
 }
}
'''
})
with tempfile.TemporaryDirectory(prefix='tvos-app-strings-') as tmp:
 root=Path(tmp)
 for name,content in fixtures.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.StringsHarness'],check=True)
