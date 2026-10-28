"""Execute external launch boundaries and actual UI routing with deterministic Android refusals."""
from pathlib import Path
import subprocess,tempfile
from app_launch_fixture import files
from settings_strings_fixture import write as write_strings
from tmdb_metadata_fixture import block

home=Path('app/src/io/tvos/HomeView.java').read_text()
movie=Path('app/src/io/tvos/MovieAppLauncher.java').read_text()
apps=Path('app/src/io/tvos/HomeApps.java').read_text()
settings=Path('app/src/io/tvos/SettingsActivity.java').read_text()
assert 'catch (Exception' not in home
lookup=block(movie,'    private void openKinoPub(')
assert 'java.io.IOException | org.json.JSONException' in lookup
assert 'AppLog.unexpected("KinoPub lookup", e);' in lookup
assert 'lookup failed for ' not in lookup
app_wrapper='''package io.tvos;
import android.app.*;import android.content.*;import android.content.pm.*;import android.provider.Settings;
import android.media.tv.TvContract;import android.view.View;
final class SettingsActivity {}
final class HomeApps {
 static final int APP=0,LIVE_TV=1,INPUT=2,SETTINGS=4,ANDROID_SETTINGS=5;
 static class Item {int kind;String pkg,inputId,label;}
 static Item app(String pkg,String label){Item it=new Item();it.kind=APP;it.pkg=pkg;it.label=label;return it;}
 static Intent live;static Intent liveTv(Context c){return live;}
'''+block(apps,'    static boolean open(')+block(apps,'    static Intent launchIntent(')+'}'
harness='''package io.tvos;
import java.util.*;import android.app.*;import android.content.*;import android.content.pm.*;
import android.os.Bundle;import android.provider.Settings;import android.util.Log;import android.view.View;
public class LaunchHarness extends View {
 static class Ui {static boolean ru;}
 Activity activity=new Activity();int toasts;String target;
 Context getContext(){return activity;}void showToast(String message){toasts++;}
 void openKinoPub(Recs.Pick p,String pkg){target=pkg;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static Recs.Pick pick(){Recs.Pick p=new Recs.Pick();p.kind="movie";p.id=42;p.title="A & Б";return p;}
 static LaunchHarness app(String pkg){LaunchHarness h=new LaunchHarness();h.activity.pm.tv.add(new ResolveInfo(pkg,"Video"));
  h.activity.pm.leanback=new Intent("launch").setPackage(pkg);return h;}
'''+''.join(block(movie,s).replace('void choose(', 'void chooseWatchApp(').replace('host.toast(', 'showToast(').replace('app, anchor,', 'app, this,') for s in ['    void choose(', '    private void open(', '    private void openVkVideoSearch('])+block(home,'    private void openNetworkSettings(')+block(settings,'        private void launchAirPlay(')+'''
 public static void main(String[] args){
  Intent intent=new Intent("test");Context c=new Context();Log.reset();
  check(AppLaunch.start(c,intent,"launch")&&c.launched==intent,"one-argument launch success");
  check(Log.messages.isEmpty(),"successful launch has no diagnostics");
  for(RuntimeException failure:new RuntimeException[]{new ActivityNotFoundException("SECRET uri"),new SecurityException("SECRET title")}){
   c=new Context();c.startFailure=failure;Log.reset();
   check(!AppLaunch.start(c,intent,"launch")&&Log.infos==1&&Log.errors==0,"expected launch refusal");
   check(!Log.messages.toString().contains("SECRET"),"expected refusal does not log private message");
  }
  c=new Context();c.startFailure=new IllegalStateException("SECRET firmware payload");Log.reset();
  check(!AppLaunch.start(c,intent,"launch")&&Log.errors==1,"unexpected launch failure preserves fallback result");
  check(Log.messages.toString().contains("IllegalStateException")&&!Log.messages.toString().contains("SECRET"),"unexpected failure logs type, not payload");
  Activity activity=new Activity();Bundle options=new Bundle();
  check(AppLaunch.start(activity,intent,options,"launch")&&activity.options==options&&activity.launched==intent,"animation options preserved");
  activity.startFailure=new SecurityException("private");check(!AppLaunch.start(activity,intent,options,"launch"),"options launch refusal");
  c=new Context();c.fatal=new AssertionError("fatal");boolean escaped=false;try{AppLaunch.start(c,intent,"launch");}catch(AssertionError expected){escaped=true;}
  check(escaped,"fatal VM/code errors are not swallowed");

  PackageManager pm=new PackageManager();Intent tv=new Intent("tv"),regular=new Intent("regular");pm.leanback=tv;pm.regular=regular;
  check(AppLaunch.launchIntent(pm,"pkg")==tv&&pm.regularQueries==0,"Leanback preferred");
  pm.leanback=null;check(AppLaunch.launchIntent(pm,"pkg")==regular,"regular launcher fallback");
  pm.leanbackFailure=new SecurityException("SECRET package details");Log.reset();
  check(AppLaunch.launchIntent(pm,"pkg")==regular&&Log.errors==0,"denied Leanback query still tries regular");
  pm.leanbackFailure=new IllegalStateException("SECRET vendor details");Log.reset();
  check(AppLaunch.launchIntent(pm,"pkg")==regular&&Log.errors==1,"unexpected Leanback query still tries regular");
  pm.regularFailure=new IllegalArgumentException("SECRET package");check(AppLaunch.launchIntent(pm,"pkg")==null,"both query failures return no handler");
  check(!Log.messages.toString().contains("SECRET"),"package query diagnostics omit payload");
  pm=new PackageManager();check(AppLaunch.launchIntent(pm,"missing")==null,"missing package remains absent");

  activity=new Activity();activity.pm.leanback=new Intent("launch");
  HomeApps.Item item=HomeApps.app("pkg","SECRET app label");
  check(HomeApps.open(activity,item,new View(),3,4,0,-1),"actual app item launches");
  check(activity.launched.flags==(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),"launch flags preserved");
  check(activity.options==ActivityOptions.last&&ActivityOptions.width==1&&ActivityOptions.height==1,"original zoom and size clamps preserved");
  activity.startFailure=new ActivityNotFoundException("SECRET missing");Log.reset();
  check(!HomeApps.open(activity,item,new View(),0,0,1,1)&&Log.errors==0,"expected item refusal");
  activity.startFailure=null;ActivityOptions.failure=new IllegalStateException("SECRET animation");Log.reset();
  check(!HomeApps.open(activity,item,new View(),0,0,1,1)&&Log.errors==1,"unexpected preparation failure diagnosed");
  ActivityOptions.failure=null;activity.pm.resolveFailure=new SecurityException("SECRET resolver");item.kind=HomeApps.INPUT;item.inputId="HDMI1";Log.reset();
  check(!HomeApps.open(activity,item,new View(),0,0,1,1)&&Log.errors==0,"expected resolver refusal contained");
  activity.pm.resolveFailure=null;activity.pm.resolved=new ComponentName(activity,SettingsActivity.class);
  check(HomeApps.open(activity,item,new View(),0,0,1,1)&&activity.launched.pkg.equals("com.yandex.tv.live"),"YaOS HDMI route retained");
  activity.pm.resolved=null;check(HomeApps.open(activity,item,new View(),0,0,1,1)&&activity.launched.uri.toString().equals("passthrough:HDMI1"),"standard HDMI route retained");
  item.kind=HomeApps.ANDROID_SETTINGS;check(HomeApps.open(activity,item,new View(),0,0,1,1)&&activity.launched.action.equals("settings"),"system settings route retained");
  item.kind=HomeApps.SETTINGS;check(HomeApps.open(activity,item,new View(),0,0,1,1)&&activity.launched.component!=null,"internal settings route retained");
  item.kind=HomeApps.LIVE_TV;HomeApps.live=null;check(!HomeApps.open(activity,item,new View(),0,0,1,1),"missing tuner remains unavailable");
  check(!Log.messages.toString().contains("SECRET"),"preparation diagnostics omit labels/messages");

  LaunchHarness lampa=app("top.rootu.lampa");lampa.activity.failureFor=i->Intent.ACTION_VIEW.equals(i.action)?new ActivityNotFoundException("private link"):null;
  lampa.chooseWatchApp(pick());check(lampa.activity.attempts.size()==2&&lampa.toasts==0,"Lampa rejected link falls back to normal launch");
  check(lampa.activity.attempts.get(0).uri.toString().equals("https://www.themoviedb.org/movie/42")&&lampa.activity.launched.pkg.equals("top.rootu.lampa"),"Lampa movie identity and target retained");
  LaunchHarness seena=app("com.seena.app");seena.chooseWatchApp(pick());
  check(seena.activity.starts==1&&seena.activity.launched.cls.equals("com.seena.app.MainActivity")&&seena.activity.launched.extras.get(SearchManager.QUERY).equals("A & Б"),"Seena query and component preserved");
  seena=app("com.seena.app");seena.activity.startFailure=new SecurityException("private query");seena.chooseWatchApp(pick());
  check(seena.activity.starts==1&&seena.toasts==1,"Seena refusal shows one message without relaunch");
  for(String pkg:new String[]{"watch.kino","watch.kino.mod"}){
   LaunchHarness kino=app(pkg);kino.chooseWatchApp(pick());check(pkg.equals(kino.target)&&kino.activity.starts==0,"chosen KinoPub package preserved");
  }
  LaunchHarness missing=new LaunchHarness();missing.chooseWatchApp(pick());check(missing.toasts==1,"empty provider list message");
  LaunchHarness vk=new LaunchHarness();vk.openVkVideoSearch("A & Б");
  check(vk.activity.launched.uri.toString().equals("vkvideo://video/search?query=A+%26+%D0%91")&&vk.activity.launched.pkg.equals("com.vk.tv"),"VK encoded query and package retained");
  vk=new LaunchHarness();vk.activity.startFailure=new ActivityNotFoundException("private title");vk.openVkVideoSearch("movie");
  check(vk.toasts==1&&vk.activity.starts==1,"VK refusal shows one message");
  LaunchHarness blank=new LaunchHarness();blank.openVkVideoSearch(" ");check(blank.activity.starts==0&&blank.toasts==0,"blank VK query remains ignored");
  LaunchHarness network=new LaunchHarness();network.activity.failureFor=i->i.action.equals("settings")?null:new ActivityNotFoundException("no settings handler");
  network.openNetworkSettings();check(network.activity.starts==3&&network.toasts==0&&network.activity.launched.action.equals("settings"),"network settings fallback order preserved");
  network=new LaunchHarness();network.activity.startFailure=new IllegalStateException("private firmware");network.openNetworkSettings();
  check(network.activity.starts==3&&network.toasts==1,"unexpected network failure still tries each fallback");
  LaunchHarness airplay=app("io.github.jqssun.airplay");airplay.activity.pm.leanbackFailure=new SecurityException("private");
  airplay.activity.pm.regular=new Intent("airplay");int shown=android.widget.Toast.shown;airplay.launchAirPlay();
  check(airplay.activity.starts==1&&airplay.activity.launched.flags==Intent.FLAG_ACTIVITY_NEW_TASK&&android.widget.Toast.shown==shown,"AirPlay regular-intent fallback retained");
  airplay.activity.startFailure=new SecurityException("private");airplay.launchAirPlay();check(android.widget.Toast.shown==shown+1,"AirPlay refusal shows one message");
  System.out.println("PASS: real launch boundaries, query and UI fallbacks, refusal/unexpected diagnostics, flags/options, supported video apps and AirPlay");
 }
}
'''
sources=files()
sources['android/content/Context.java']=sources['android/content/Context.java'].replace('public class Context {','public class Context {public static final int MODE_PRIVATE=0;public static class Preferences {public String getString(String k,String d){return d;}}public Preferences getSharedPreferences(String n,int m){return new Preferences();}')
sources['io/tvos/HomeApps.java']=app_wrapper
sources['io/tvos/LaunchHarness.java']=harness
sources['io/tvos/KinoPubLink.java']=Path('app/src/io/tvos/KinoPubLink.java').read_text()
sources['io/tvos/Recs.java']='package io.tvos;final class Recs {'+block(Path('app/src/io/tvos/Recs.java').read_text(),'    static final class Pick {')+'}'
with tempfile.TemporaryDirectory(prefix='tvos-app-launch-') as tmp:
 root=Path(tmp);write_strings(root)
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.LaunchHarness'],check=True)
