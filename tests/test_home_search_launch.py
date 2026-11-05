"""The Home search action launches the standalone screen with the current backdrop."""
from pathlib import Path
import subprocess
import tempfile
from tmdb_metadata_fixture import block

home = Path('app/src/io/tvos/HomeView.java').read_text()
sources = {
    'android/content/Intent.java': '''package android.content;
public class Intent {
 public final Object activity; public final Class<?> target; public String key; public Object value;
 public Intent(Object a,Class<?> t){activity=a;target=t;}
 public Intent putExtra(String k,Object v){key=k;value=v;return this;}
}''',
    'io/tvos/SearchLaunchHarness.java': '''package io.tvos;
class SearchActivity {}
class AppLaunch {
 static android.content.Intent launched;
 static boolean start(Object a,android.content.Intent i,String label){
  if(a!=i.activity)throw new AssertionError("Activity owner");launched=i;return true;
 }
}
public class SearchLaunchHarness {
 final Object activity=new Object();
 static class Background {final Object pixels=new Object();Object profilePixels(){return pixels;}}
 final Background background=new Background();
''' + block(home, '    private void openSearch(') + '''
 public static void main(String[] args){
  SearchLaunchHarness h=new SearchLaunchHarness();h.openSearch();
  if(AppLaunch.launched.target!=SearchActivity.class
    ||!"profile_frost".equals(AppLaunch.launched.key)
    ||AppLaunch.launched.value!=h.background.pixels)throw new AssertionError("Standalone search launch");
  System.out.println("PASS: Home opens standalone search with current backdrop");
 }
}''',
}
with tempfile.TemporaryDirectory(prefix='tvos-search-launch-') as tmp:
    root = Path(tmp)
    for name, source in sources.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
    subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', tmp,
                    *map(str, root.rglob('*.java'))], check=True)
    subprocess.run(['java', '-cp', tmp, 'io.tvos.SearchLaunchHarness'], check=True)
