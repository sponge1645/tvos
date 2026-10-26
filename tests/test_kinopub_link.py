"""Exercise package-bound KinoPub intents and actual HomeView launch fallback."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import subprocess
import tempfile

source=Path('app/src/io/tvos/HomeView.java').read_text()
def method(signature):
 a=source.index(signature);b=source.index('{',a);depth=1;i=b+1
 while depth:depth+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
choose=method('    private void chooseWatchApp(')
assert '"watch.kino.mod"' in choose
assert 'openKinoPub(pick, selected.getKey());' in choose
lookup=method('    private void openKinoPub(')
assert 'launchKinoPub(cached, targetPackage);' in lookup
assert 'launchKinoPub(resolved, targetPackage);' in lookup
sources={
 'android/net/Uri.java':'''package android.net;public class Uri {final String value;private Uri(String s){value=s;}public static Uri parse(String s){return new Uri(s);}public String toString(){return value;}}''',
 'android/content/Intent.java':'''package android.content;public class Intent {
 public static final String ACTION_VIEW="view";public String action,pkg,cls;public android.net.Uri uri;
 public Intent(String a,android.net.Uri u){action=a;uri=u;}public Intent setClassName(String p,String c){pkg=p;cls=c;return this;}
 public Intent setPackage(String p){pkg=p;return this;}}
''',
 'android/util/Log.java':'package android.util;public class Log {public static void w(String t,String m,Throwable e){}}',
 'io/tvos/KinoPubLink.java':Path('app/src/io/tvos/KinoPubLink.java').read_text(),
 'io/tvos/KinoPubHarness.java':'''package io.tvos;
import java.util.*;import android.content.Intent;
public class KinoPubHarness {
 static class Ui {static boolean ru;}
 static class Activity {
  String pkg,cls;Intent launched;List<Intent> attempts=new ArrayList<>();
  void startActivity(Intent i){attempts.add(i);if(!i.pkg.equals(pkg)||!Objects.equals(i.cls,cls))throw new IllegalStateException("missing activity");launched=i;}
 }
 final Activity activity=new Activity();int toasts;
 void showToast(String message){toasts++;}
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
''' + method('    private void launchKinoPub(') + '''
 public static void main(String[] args){
  check(KinoPubLink.supports("watch.kino")&&KinoPubLink.supports("watch.kino.mod"),"both exact packages supported");
  check(!KinoPubLink.supports("watch.kino.mod.other")&&!KinoPubLink.supports(null),"no broad package aliases");
  for(String pkg:new String[]{"watch.kino","watch.kino.mod"}) {
   for(Intent intent:KinoPubLink.candidates(pkg,42)) {
    check(intent.pkg.equals(pkg),"all intents bound to chosen package");
    check(intent.uri.toString().equals("https://kino.watch/item/view/42"),"same resolved movie/series ID");
   }
   for(String cls:new String[]{"watch.kino.activity.InfoActivity",pkg+".activity.InfoActivity",null}){
    KinoPubHarness h=new KinoPubHarness();h.activity.pkg=pkg;h.activity.cls=cls;h.launchKinoPub(42,pkg);
    check(h.activity.launched!=null && h.activity.launched.pkg.equals(pkg) && h.toasts==0,"launch matching component or package-bound link");
   }
  }
  KinoPubHarness missing=new KinoPubHarness();missing.activity.pkg="none";missing.launchKinoPub(42,"watch.kino.mod");
  check(missing.activity.attempts.size()==3 && missing.toasts==1,"unavailable MOD reports one failure after scoped fallbacks");
  for(Intent intent:missing.activity.attempts)check(intent.pkg.equals("watch.kino.mod"),"failed MOD never silently opens standard KinoPub");
  try{KinoPubLink.candidates("other",42);throw new AssertionError("unsupported target accepted");}catch(IllegalArgumentException expected){}
  System.out.println("PASS: KinoPub standard/MOD selection, cache/network target propagation, scoped component fallbacks and failure");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='tvos-kinopub-link-') as tmp:
 root=Path(tmp)
 write_strings(root)
 for name,value in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.KinoPubHarness'],check=True)
