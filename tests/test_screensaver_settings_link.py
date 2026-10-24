"""Run the actual Android settings launch path against missing/restricted TV activities."""
from pathlib import Path
import subprocess
import tempfile

source = Path('app/src/io/tvos/SettingsActivity.java').read_text()
start = source.index('        private void openAndroidScreenSaverSettings(')
brace = source.index('{', start)
depth, end = 1, brace + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
body = source[start:end]
stubs = {
 'android/content/ActivityNotFoundException.java': 'package android.content; public class ActivityNotFoundException extends RuntimeException {}',
 'android/widget/Toast.java': '''package android.widget;public class Toast {
 public static final int LENGTH_SHORT=0;public static int shown;
 public static Toast makeText(Object c,String s,int duration){return new Toast();}public void show(){shown++;}}
''',
}
harness = '''import java.util.*;
public class ScreenSaverLinkHarness {
 static class Settings {static final String ACTION_DREAM_SETTINGS="android.settings.DREAM_SETTINGS",ACTION_SETTINGS="android.settings.SETTINGS";}
 static class Intent {String action;Intent(String value){action=value;}}
 static class Activity {
  List<String> tried=new ArrayList<>();int dreamFailure,generalFailure;
  void startActivity(Intent intent){tried.add(intent.action);
   int failure=intent.action.equals(Settings.ACTION_DREAM_SETTINGS)?dreamFailure:generalFailure;
   if(failure==1)throw new android.content.ActivityNotFoundException();
   if(failure==2)throw new SecurityException();
  }
 }
 Activity activity=new Activity();Object getContext(){return this;}String tr(String ru,String en){return en;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
''' + body + '''
 public static void main(String[] args){
  ScreenSaverLinkHarness h=new ScreenSaverLinkHarness();h.openAndroidScreenSaverSettings();
  check(h.activity.tried.equals(Arrays.asList(Settings.ACTION_DREAM_SETTINGS)),"prefer direct screen saver settings");
  for(int failure:new int[]{1,2}){
   h=new ScreenSaverLinkHarness();h.activity.dreamFailure=failure;h.openAndroidScreenSaverSettings();
   check(h.activity.tried.equals(Arrays.asList(Settings.ACTION_DREAM_SETTINGS,Settings.ACTION_SETTINGS)),
         "missing/restricted dream screen falls back to TV settings");
  }
  for(int failure:new int[]{1,2}){
   int before=android.widget.Toast.shown;
   h=new ScreenSaverLinkHarness();h.activity.dreamFailure=1;h.activity.generalFailure=failure;
   h.openAndroidScreenSaverSettings();
   check(android.widget.Toast.shown==before+1,"no crash when firmware exposes neither settings activity");
  }
  System.out.println("PASS: direct Android screen saver settings, missing/denied fallback, unavailable feedback");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-dream-link-') as directory:
    root = Path(directory)
    for name, content in stubs.items():
        path = root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    (root/'ScreenSaverLinkHarness.java').write_text(harness)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
    subprocess.run(['java','-cp',directory,'ScreenSaverLinkHarness'],check=True)
