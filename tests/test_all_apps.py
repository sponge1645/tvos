from settings_strings_fixture import write as write_strings
"""Run production app discovery against incomplete TV launcher queries and package fallbacks."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/HomeApps.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
body='\n'.join(method(s) for s in ['    static List<String> shelfPackages(', '    static List<String[]> shelfCandidates(', '    static List<Item> others(', '    private static String settingsPackage(', '    private static void addAllAppsPackage(', '    private static List<ResolveInfo> launcherActivities('])
header='''import java.util.*;
class HomeApps {
 static final int APP=0,ANDROID_SETTINGS=5,MAX_SHELF=8;
 static final String TV_SETTINGS_FAVORITE="io.tvos.virtual.tvsettings";
 static class Prefs {static List<String> selected;static List<String> shelf(){return selected;}}
 static final String[][] SHELF={{"youtube","YouTube","com.google.android.youtube.tv"},{"netflix","Netflix","com.netflix.ninja"}};
 static final Set<String> HIDDEN=new HashSet<>(Arrays.asList("hidden.promo"));
 static final Map<String,Object> TILES=new HashMap<>();
 static class Item {int kind;String pkg,key,label,symbol;Object tile;}
 static class Ui {static boolean ru;static String tr(String p,String en){return en;}}
 static class Settings {static final String ACTION_SETTINGS="settings";}
 static class Intent {static final String ACTION_MAIN="main",CATEGORY_LEANBACK_LAUNCHER="tv",CATEGORY_LAUNCHER="phone";String action,category;Intent(String a){action=a;}Intent addCategory(String c){category=c;return this;}}
 static class ApplicationInfo {boolean enabled=true,launchable=true;String pkg,label;}
 static class ActivityInfo {boolean enabled=true;String packageName;}
 static class ResolveInfo {ActivityInfo activityInfo=new ActivityInfo();String label;ResolveInfo(String p,String l){activityInfo.packageName=p;label=l;}String loadLabel(PackageManager pm){return label;}}
 static class PackageManager {
  static class NameNotFoundException extends Exception {}
  Map<String,ApplicationInfo> installed=new HashMap<>();List<ResolveInfo> tv=new ArrayList<>(),phone=new ArrayList<>();String settings;
  List<ResolveInfo> queryIntentActivities(Intent i,int flags){return "tv".equals(i.category)?tv:phone;}
  ResolveInfo resolveActivity(Intent i,int flags){ApplicationInfo app=installed.get(settings);return app!=null&&app.enabled?new ResolveInfo(settings,"System Settings"):null;}
  ApplicationInfo getApplicationInfo(String p,int flags)throws NameNotFoundException {if(!installed.containsKey(p))throw new NameNotFoundException();return installed.get(p);}
  String getApplicationLabel(ApplicationInfo a){return a.label;}
 }
 static class Context {PackageManager pm=new PackageManager();PackageManager getPackageManager(){return pm;}String getPackageName(){return "io.tvos";}}
 static Intent launchIntent(PackageManager pm,String pkg){ApplicationInfo a=pm.installed.get(pkg);return a!=null&&a.enabled&&a.launchable?new Intent(pkg):null;}
 static Item shortcut(int kind,String key,String label,String symbol){Item i=new Item();i.kind=kind;i.key=key;i.label=label;i.symbol=symbol;return i;}
 static class AppArtwork {static Object brandedAllAppsTile(Context c,String p){return p;}static Object tvSettingsFavoriteTile(Context c){return "settings-art";}static void pruneTiles(Set<String> seen){}}
'''
tests='''
 static void add(Context c,String p,String name,boolean query){ApplicationInfo a=new ApplicationInfo();a.pkg=p;a.label=name;c.pm.installed.put(p,a);if(query)c.pm.tv.add(new ResolveInfo(p,name));}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static long count(List<Item> list,String pkg){return list.stream().filter(i->pkg.equals(i.pkg)).count();}
 public static void main(String[] args){
  Context c=new Context();add(c,"com.google.android.youtube.tv","YouTube",true);
  add(c,"com.android.tv.settings","Settings",false);c.pm.settings="com.android.tv.settings";
  add(c,"watch.kino","KinoPub",false);add(c,"watch.kino.mod","KinoPub mod",false);
  add(c,"com.seena.app","Seena",false);add(c,"com.mediatek.wwtv.tvcenter","TV Center",true);
  add(c,"io.tvos","tvOS",true);add(c,"hidden.promo","Promo",true);
  c.pm.phone.add(new ResolveInfo("com.google.android.youtube.tv","YouTube duplicate"));
  List<Item> list=others(c);
  check(count(list,"com.google.android.youtube.tv")==1,"YouTube not hidden by Dock catalog and deduplicated across launcher categories");
  check(count(list,"com.seena.app")==1,"known package fallback when launcher queries omit an installed app");
  check(count(list,"com.mediatek.wwtv.tvcenter")==1,"source apps remain visible in All Apps");
  check(count(list,"io.tvos")==0&&count(list,"hidden.promo")==0,"self and existing hidden list preserved");
  check(count(list,"com.android.tv.settings")==1,"Settings without launcher gets one system-action tile");
  check(count(list,"watch.kino")==1&&count(list,"watch.kino.mod")==1,"KinoPub variants stay separate and get package fallback");
  check(list.stream().filter(i->"watch.kino.mod".equals(i.pkg)).findFirst().get().key.equals("watch.kino.mod"),"visual alias never rewrites launch identity");
  Item settings=list.stream().filter(i->i.kind==ANDROID_SETTINGS).findFirst().get();check(settings.key.equals("tv-settings"),"Settings uses system action, not absent package launch intent");
  for(int i=1;i<list.size();i++)check(list.get(i-1).label.compareToIgnoreCase(list.get(i).label)<=0,"fallbacks sorted with other apps");
  c.pm.tv.add(new ResolveInfo("com.android.tv.settings","Settings"));list=others(c);check(count(list,"com.android.tv.settings")==1,"launcher Settings does not duplicate system tile");
  c.pm.tv.removeIf(i->i.activityInfo.packageName.equals("com.google.android.youtube.tv"));c.pm.phone.clear();
  list=others(c);check(count(list,"com.google.android.youtube.tv")==1,"YouTube direct package fallback");
  c.pm.installed.get("com.google.android.youtube.tv").enabled=false;c.pm.installed.get("com.seena.app").launchable=false;
  list=others(c);check(count(list,"com.google.android.youtube.tv")==0&&count(list,"com.seena.app")==0,"disabled/nonlaunchable packages not invented or enabled");
  c.pm.tv.removeIf(i->i.activityInfo.packageName.equals("com.android.tv.settings"));c.pm.settings=null;
  list=others(c);check(list.stream().noneMatch(i->i.kind==ANDROID_SETTINGS),"no dead Settings action without handler");
  c.pm.settings="vendor.settings";add(c,"vendor.settings","Vendor Settings",false);list=others(c);
  check(count(list,"vendor.settings")==1,"vendor settings package supported");
  c.pm.installed.get("vendor.settings").launchable=false;
  check(shelfCandidates(c).stream().anyMatch(row->row[0].equals(TV_SETTINGS_FAVORITE)),"Dock offers Settings without launcher entry");
  c.pm.tv.add(new ResolveInfo("vendor.settings","Vendor Settings"));
  check(shelfCandidates(c).stream().noneMatch(row->row[0].equals("vendor.settings")),"Dock deduplicates vendor Settings launcher");
  Prefs.selected=Arrays.asList("com.android.tv.settings","vendor.settings",TV_SETTINGS_FAVORITE);
  check(shelfPackages(c).equals(Arrays.asList(TV_SETTINGS_FAVORITE)),"existing standard and vendor selections migrate to stable system action");
  Prefs.selected=null;check(shelfPackages(c).contains(TV_SETTINGS_FAVORITE),"default Dock uses portable Settings action");
  System.out.println("PASS: Dock apps, source apps, known package fallback, disabled packages, Settings action fallback/vendor handler, deduplication and sorting");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 write_strings(Path(d),package=False)
 p=Path(d)/'HomeApps.java';p.write_text(header+body+tests)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,Path(d).rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'HomeApps'],check=True)
