"""Execute real setup policy and Android first-run gate, including upgrade/reset/session escape."""
from pathlib import Path
import subprocess,tempfile
files={
'io/tvos/setup/SetupState.java':Path('app/src/io/tvos/setup/SetupState.java').read_text(),
'io/tvos/SetupGate.java':Path('app/src/io/tvos/SetupGate.java').read_text(),
'io/tvos/SetupActivity.java':'package io.tvos;class SetupActivity extends android.app.Activity {}',
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {public final Map<String,Object> data=new HashMap<>();public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}public Map<String,Object> getAll(){return data;}public SharedPreferences edit(){return this;}public SharedPreferences putBoolean(String k,boolean v){data.put(k,v);return this;}public void apply(){}}''',
'android/content/pm/PackageInfo.java':'package android.content.pm;public class PackageInfo {public long firstInstallTime=10,lastUpdateTime=10;}',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {public final PackageInfo p=new PackageInfo();public PackageInfo getPackageInfo(String s,int f)throws NameNotFoundException{return p;}public static class NameNotFoundException extends Exception {private static final long serialVersionUID=1;}}''',
'android/content/Intent.java':'package android.content;public class Intent {public Intent(Context c,Class<?> t){}}',
'android/content/Context.java':'''package android.content;import java.util.*;public class Context {public static final int MODE_PRIVATE=0;public final Map<String,SharedPreferences> stores=new HashMap<>();public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public SharedPreferences getSharedPreferences(String s,int m){return stores.computeIfAbsent(s,k->new SharedPreferences());}public android.content.pm.PackageManager getPackageManager(){return pm;}public String getPackageName(){return "io.tvos";}}''',
'android/app/Activity.java':'package android.app;public class Activity extends android.content.Context {public int launches;public void startActivity(android.content.Intent i){launches++;}}',
'io/tvos/SetupTest.java':'''package io.tvos;import io.tvos.setup.*;import android.app.*;public class SetupTest {static void check(boolean b,String s){if(!b)throw new AssertionError(s);}public static void main(String[] args){
for(boolean complete:new boolean[]{false,true})for(boolean started:new boolean[]{false,true})for(boolean upgrade:new boolean[]{false,true})for(boolean configured:new boolean[]{false,true})check(SetupState.needed(complete,started,upgrade,configured)==(!complete&&(started||!upgrade||!configured)),"first-run decision matrix");
check(SetupState.step(-1)==0&&SetupState.step(999)==6&&SetupState.step(3)==3,"bounded steps");check(SetupState.step(4)==5&&SetupState.next(3)==5&&SetupState.previous(5)==3,"retired update step migrates and skips both directions");check(SetupState.language("ru-RU").equals("ru")&&SetupState.language("de").equals("en")&&SetupState.language(null).equals("en"),"system language suggestion and fallback");
Activity fresh=new Activity();check(SetupGate.pending(fresh),"fresh install starts wizard");SetupGate.route(fresh);check(fresh.launches==1,"route fresh install");SetupGate.defer();SetupGate.route(fresh);check(fresh.launches==1&&SetupGate.pending(fresh),"Back escapes this session without fake completion");SetupGate.restart();SetupGate.route(fresh);check(fresh.launches==2,"explicit replay available");
Activity old=new Activity();old.pm.p.lastUpdateTime=20;old.getSharedPreferences("settings",0).data.put("language","ru");check(!SetupGate.pending(old)&&old.getSharedPreferences("setup",0).getBoolean("complete",false),"configured upgrade migrated without rerunning");check(old.getSharedPreferences("settings",0).data.get("language").equals("ru"),"upgrade settings retained");
Activity interrupted=new Activity();interrupted.pm.p.lastUpdateTime=20;interrupted.getSharedPreferences("settings",0).data.put("language","ru");interrupted.getSharedPreferences("setup",0).data.put("started",true);check(SetupGate.pending(interrupted),"incomplete setup resumes even after update");
Activity reset=new Activity();reset.pm.p.lastUpdateTime=20;check(SetupGate.pending(reset),"cleared app data runs wizard again");reset.getSharedPreferences("setup",0).data.put("complete",true);check(!SetupGate.pending(reset),"explicit completion wins");SetupActivity wizard=new SetupActivity();SetupGate.route(wizard);check(wizard.launches==0,"no wizard recursion");System.out.println("PASS: actual first-run gate, configured upgrades, cleared data, incomplete resume, explicit completion, Back escape and language/step policy");}}
'''}
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SetupTest'],check=True)
