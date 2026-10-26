"""Production branding plus real shelf/All Apps paths: aliases affect visuals only."""
from pathlib import Path
import subprocess, tempfile
from dock_graphics_fixture import GRAPHICS

source=Path('app/src/io/tvos/HomeApps.java').read_text()
def method(signature, source=source):
    a=source.index(signature);b=source.index('{',a);depth=1;end=b+1
    while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[a:end]

home='''package io.tvos;import android.graphics.*;import java.util.*;
final class HomeApps {
 static final int APP=0,ANDROID_SETTINGS=5,TILE_W=384,TILE_H=216,MAX_SHELF=8;
 static final String TV_SETTINGS_FAVORITE="virtual.settings";
 static final Map<String,Bitmap> TILES=new HashMap<>(),LOGOS=new HashMap<>();
 static class Item {int kind;String key,pkg,label;Bitmap logo,logoDark,tile;float logoInk,logoScale=1;}
 static class Context {List<String> packages=new ArrayList<>();PackageManager getPackageManager(){return new PackageManager();}}
 static class Intent {String pkg;Intent(String pkg){this.pkg=pkg;}}
 static class PackageManager {ResolveInfo resolveActivity(Intent i,int flags){return new ResolveInfo(i.pkg);}}
 static class ResolveInfo {String pkg;ResolveInfo(String pkg){this.pkg=pkg;}String loadLabel(PackageManager pm){return "Installed:"+pkg;}}
 static class Ui {static boolean ru;static String tr(String p,String en){return en;}}
 static List<String> shelfPackages(Context c){return c.packages;}
 static String[] known(String pkg){return null;}
 static Intent launchIntent(PackageManager pm,String pkg){return new Intent(pkg);}
 static Item shortcut(int kind,String key,String label,String symbol){Item i=new Item();i.kind=kind;i.key=key;i.label=label;return i;}
 static class AppArtwork {
 static Bitmap ownLogoCached(Context c,String key){return LOGOS.computeIfAbsent(key,k->{Bitmap b=new Bitmap(540,252);b.asset=k;return b;});}
 static Bitmap shelfLogo(Context c,String key,String pkg,String mode,boolean extract){return ownLogoCached(c,key);}
 static float ink(Bitmap b){return .3f;}
 static Bitmap appTile(Context c,String key,String pkg,int brand){Bitmap b=new Bitmap(TILE_W,TILE_H);b.asset="fallback:"+pkg;return b;}
 static Bitmap tvSettingsFavoriteTile(Context c){return appTile(c,null,"settings",0);}
'''+method('    static synchronized void pruneTiles(', Path('app/src/io/tvos/AppArtwork.java').read_text())+method('    static synchronized Bitmap brandedAllAppsTile(', Path('app/src/io/tvos/AppArtwork.java').read_text())+'''
 }
'''+method('    static List<Item> shelf(')+'''
 static Bitmap all(Context c,String pkg){return AppArtwork.brandedAllAppsTile(c,pkg);}
}
'''

harness='''package io.tvos;import java.util.*;import android.graphics.*;
public class BrandingHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void close(float a,float b){check(Math.abs(a-b)<.0001f,a+" != "+b);}
 public static void main(String[] args){
  String[] keys={"com.android.vending","com.yandex.devices.appstore","netflix","youtube","io.gh.reisxd.tizentube.cobalt","watch.kino","com.seena.app","top.rootu.lampa","tv-settings","com.applemusicktv","com.android.tv.settings","io.github.jqssun.airplay","llc.itdev.incy","globoplay","prime","disney","max","appletv"};
  int[][] expected={{0xFF52ABEF,0xFF2D6AD4,0},{0xFF52ABEF,0xFF2D6AD4,0},{0xFF0A0A0A,0xFF000000,0},{0xFF1C1C1E,0xFF09090A,0},{-1,-1,0},{-1,-1,0},{0xFF007AFF,0xFF007AFF,0},{0xFF313130,0xFF161613,0},{0xFFA5A6AB,0xFF656368,0},{0xFFEB576D,0xFFEA333C,0},{0xFFA5A6AB,0xFF656368,0},{0xFFF79E1F,0xFFFF6933,0},{0xFF024824,0xFF0B1B11,0},{0xFFFF8A1F,0xFFFF2D5A,1},{0xFF2AA8FF,0xFF0A68D4,1},{0xFF173584,0xFF08143F,0},{0xFF5C30E8,0xFF221684,0},{0xFF1C1C1E,0xFF000000,0}};
  for(int i=0;i<keys.length;i++){AppBranding.Style s=AppBranding.get(keys[i]);check(s!=null&&s.top==expected[i][0]&&s.bottom==expected[i][1]&&s.mode==expected[i][2],"baseline styling "+keys[i]);}
  check(AppBranding.get("watch.kino")==AppBranding.get("watch.kino.mod"),"same immutable KinoPub style");
  check(AppBranding.get("watch.kino.mod.other")==null&&AppBranding.get("unknown.app")==null,"exact visual alias only");
  close(AppBranding.dockBoost("watch.kino.mod","watch.kino.mod"),1.32f);
  close(AppBranding.dockBoost("com.android.vending","arbitrary"),1.2f);
  close(AppBranding.dockBoost("com.android.tv.settings","arbitrary"),1.323f);
  close(AppBranding.dockBoost("com.applemusicktv","arbitrary"),1.25f);
  close(AppBranding.dockBoost(null,"tv-settings"),1.512f);
  close(AppBranding.dockBoost(null,"com.applemusicktv"),1f);
  check(AppBranding.forAllApps("com.android.tv.settings")==null,"system settings action card preserved");
  HomeApps.Context c=new HomeApps.Context();c.packages=Arrays.asList("watch.kino","watch.kino.mod","com.android.tv.settings","io.gh.reisxd.tizentube.cobalt");
  List<HomeApps.Item> items=HomeApps.shelf(c,true);check(items.size()==4,"all installed packages kept");
  HomeApps.Item base=items.get(0),mod=items.get(1);
  check(base.logo==mod.logo&&"kinopub".equals(mod.logo.asset),"same bundled logo");close(base.logoScale,mod.logoScale);close(base.logoInk,mod.logoInk);
  check(mod.pkg.equals("watch.kino.mod")&&mod.key.equals("watch.kino.mod"),"modified package retains launch/persistence identity");
  check(mod.label.equals("Installed:watch.kino.mod"),"installed application label retained");
  close(items.get(2).logoScale,2.4f);check(items.get(3).logo.asset.equals("tizentube"),"special source settings preserved");
  Bitmap b=HomeApps.all(c,"watch.kino"),m=HomeApps.all(c,"watch.kino.mod");
  check(b.top==-1&&b.bottom==-1&&m.top==b.top&&m.bottom==b.bottom,"identical white All Apps background");
  check(b.asset.equals("kinopub")&&m.asset.equals(b.asset),"same All Apps artwork");close(b.logoWidth,m.logoWidth);close(b.logoHeight,m.logoHeight);
  check(HomeApps.all(c,"watch.kino.mod")==m,"existing tile cache retained");
  HomeApps.AppArtwork.pruneTiles(new HashSet<>(Arrays.asList("watch.kino","watch.kino.mod")));
  check(HomeApps.all(c,"watch.kino")==b&&HomeApps.all(c,"watch.kino.mod")==m,"refresh retains live branded tiles instead of rebuilding");
  HomeApps.AppArtwork.pruneTiles(new HashSet<>(Arrays.asList("watch.kino")));
  check(HomeApps.TILES.containsKey("allapps-brand:watch.kino")&&!HomeApps.TILES.containsKey("allapps-brand:watch.kino.mod"),"removed package drops only its branded tile");
  check(HomeApps.all(c,"watch.kino")==b,"other package cache remains resident");
  System.out.println("PASS: 18 baseline styles, package/key scale precedence, KinoPub alias on Dock and All Apps, launch identity and fallback");
 }
}
'''

files=dict(GRAPHICS)
files['io/tvos/HomeApps.java']=home
files['io/tvos/BrandingHarness.java']=harness
files['io/tvos/AppBranding.java']=Path('app/src/io/tvos/AppBranding.java').read_text()
with tempfile.TemporaryDirectory() as directory:
    root=Path(directory)
    for name,text in files.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run(['java','-cp',directory,'io.tvos.BrandingHarness'],check=True)
