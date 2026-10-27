"""Ensure retired quote code/config/assets stay absent and migration removes only their persisted state."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write
source=Path('app/src/io/tvos/Prefs.java').read_text()
sig='    private static void removeRetiredWidgetData()';a=source.index(sig);b=source.index('{',a);d=1;i=b+1
while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
method=source[a:i]
for name in ['Glyphs.java']:
 assert not Path('app/src/io/tvos',name).exists()
assert not list(Path('app/res/drawable').glob('coin_*.xml'))
assert not list(Path('app/assets/symbols').glob('bitcoin.*'))
for p in Path('app/src/io/tvos').glob('*.java'):
 s=p.read_text()
 for banned in ['Prefs.quotes(', 'Prefs.coin(', 'HomeData.COINS','HomeData.coinCount(', 'HomeData.active(', 'Glyphs.', 'api.binance.com', 'Config.CURRENCY', 'Config.SHOW_CRYPTO', 'R.string.settings_quotes', 'R.string.home_crypto']:
  assert banned not in s,(p,banned)
assert 'SHOW_CRYPTO' not in Path('build.sh').read_text()
assert 'currency=' not in Path('config.example.properties').read_text()
files={
 'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {public final Map<String,Object> values=new HashMap<>();public int writes;public boolean contains(String k){return values.containsKey(k);}public Editor edit(){return new Editor();}public class Editor {final List<String> remove=new ArrayList<>();public Editor remove(String k){remove.add(k);return this;}public void apply(){writes++;for(String k:remove)values.remove(k);}}}''',
 'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public final SharedPreferences settings=new SharedPreferences(),data=new SharedPreferences();public SharedPreferences getSharedPreferences(String n,int mode){return n.equals("homedata")?data:settings;}}''',
 'MigrationHarness.java':'''import android.content.*;public class MigrationHarness {
 static Context app=new Context();static SharedPreferences sp(){return app.settings;}
'''+method+'''
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  for(String key:new String[]{"quotes","coin0","coin1","coin2","coin3","showDetectives","category_2"})sp().values.put(key,true);
  sp().values.put("city","Test City");sp().values.put("weather",true);sp().values.put("device_name","TV");app.data.values.put("prices","old");app.data.values.put("pricesAt",1L);app.data.values.put("weather","forecast");app.data.values.put("weatherPlace","1,2");
  removeRetiredWidgetData();check(sp().values.size()==3&&!sp().contains("quotes")&&sp().contains("weather"),"only retired widget and category preferences removed");check(app.data.values.size()==2&&app.data.contains("weather")&&app.data.contains("weatherPlace"),"weather cache survives");
  int writes=sp().writes+app.data.writes;removeRetiredWidgetData();check(sp().writes+app.data.writes==writes,"migration idempotent without repeated disk writes");
  System.out.println("PASS: quote code/config/assets absent; targeted migration preserves weather/region/name and is idempotent");
 }
}'''
}
view=Path('app/src/io/tvos/SettingsActivity.java').read_text()
a=view.index('        private int rowCount()');b=view.index('{',a);depth=1;i=b+1
while depth:depth+=(view[i]=='{')-(view[i]=='}');i+=1
files['io/tvos/SettingsContent.java']=Path('app/src/io/tvos/SettingsContent.java').read_text()
files['io/tvos/SettingsActions.java']=Path('app/src/io/tvos/SettingsActions.java').read_text()
files['io/tvos/RowHarness.java']='''package io.tvos;import java.util.*;import static io.tvos.SettingsContent.*;public class RowHarness {int screen=WIDGETS,settingsGroup;static final int PICK_VISIBLE=10;final List<String> categoryEditor=new ArrayList<>(),buttonEditor=new ArrayList<>(),pickLabels=new ArrayList<>(),dockEditor=new ArrayList<>();static class Prefs {static final int[] KEYS={1,2,3,4};}String[] settingLabels(){return new String[6];}'''+view[a:i]+'''public static void main(String[] args){AppLanguage.select("en");RowHarness h=new RowHarness();if(h.rowCount()!=2||SettingsContent.menuLabels(WIDGETS).length!=2)throw new AssertionError("no ghost widget row");if(SettingsActions.activation(WIDGETS,1,0,false,6,false)!=SettingsActions.Action.DATE_SCREEN)throw new AssertionError("date index");if(SettingsActions.back(DATE_TIME,0,GENERAL,6).row!=1)throw new AssertionError("date return focus");h.screen=HOME_SETTINGS;h.settingsGroup=SYSTEM;if(h.rowCount()!=1)throw new AssertionError("only random category switch remains");System.out.println("PASS: actual widget row count, labels, date activation and return focus agree");}}'''
with tempfile.TemporaryDirectory(prefix='tvos-no-quotes-') as tmp:
 root=Path(tmp);write(root)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'MigrationHarness'],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RowHarness'],check=True)
