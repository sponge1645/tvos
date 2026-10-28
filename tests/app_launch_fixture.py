"""Small platform fixtures for production external-launch regressions."""
from pathlib import Path

def files():
 return {
 'android/os/Bundle.java':'package android.os;public class Bundle {}',
 'android/net/Uri.java':'''package android.net;public class Uri {final String value;private Uri(String s){value=s;}public static Uri parse(String s){return new Uri(s);}public static String encode(String s){return s;}public String toString(){return value;}}''',
 'android/content/ActivityNotFoundException.java':'package android.content;public class ActivityNotFoundException extends RuntimeException {public ActivityNotFoundException(String s){super(s);}}',
 'android/content/ComponentName.java':'''package android.content;public class ComponentName {public String pkg,cls;public ComponentName(Context c,Class<?> type){pkg=c.getPackageName();cls=type.getName();}}''',
 'android/content/Intent.java':'''package android.content;import android.content.pm.PackageManager;import java.util.*;public class Intent {
 public static final String ACTION_MAIN="main",ACTION_VIEW="view",ACTION_SEARCH="search",CATEGORY_LEANBACK_LAUNCHER="tv",CATEGORY_LAUNCHER="phone";
 public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_RESET_TASK_IF_NEEDED=2;
 public String action,pkg,cls,category;public int flags;public android.net.Uri uri;public ComponentName component;public Map<String,String> extras=new HashMap<>();
 public Intent(){}public Intent(String a){action=a;}public Intent(String a,android.net.Uri u){action=a;uri=u;}
 public Intent setPackage(String p){pkg=p;return this;}public Intent setClassName(String p,String c){pkg=p;cls=c;return this;}
 public Intent setComponent(ComponentName c){component=c;return this;}public ComponentName getComponent(){return component;}
 public Intent addFlags(int f){flags|=f;return this;}public Intent addCategory(String c){category=c;return this;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
 public ComponentName resolveActivity(PackageManager pm){if(pm.resolveFailure!=null)throw pm.resolveFailure;return pm.resolved;}
}''',
 'android/content/Context.java':'''package android.content;import java.util.*;public class Context {
 public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public int starts;public Intent launched;
 public RuntimeException startFailure;public Error fatal;public java.util.function.Function<Intent,RuntimeException> failureFor;
 public final List<Intent> attempts=new ArrayList<>();
 public android.content.pm.PackageManager getPackageManager(){return pm;}public String getPackageName(){return "io.tvos";}
 public void startActivity(Intent i){starts++;attempts.add(i);if(fatal!=null)throw fatal;if(startFailure!=null)throw startFailure;
  RuntimeException failure=failureFor==null?null:failureFor.apply(i);if(failure!=null)throw failure;launched=i;}
}''',
 'android/content/pm/ResolveInfo.java':'''package android.content.pm;public class ResolveInfo {
 public static class ActivityInfo {public String packageName;}public ActivityInfo activityInfo=new ActivityInfo();public String label;
 public ResolveInfo(String pkg,String name){activityInfo.packageName=pkg;label=name;}public CharSequence loadLabel(PackageManager pm){return label;}
}''',
 'android/content/pm/PackageManager.java':'''package android.content.pm;import android.content.*;import java.util.*;public class PackageManager {
 public Intent leanback,regular;public RuntimeException leanbackFailure,regularFailure,resolveFailure;public ComponentName resolved;public int tvQueries,regularQueries;
 public List<ResolveInfo> tv=new ArrayList<>(),phone=new ArrayList<>();
 public Intent getLeanbackLaunchIntentForPackage(String p){tvQueries++;if(leanbackFailure!=null)throw leanbackFailure;return leanback;}
 public Intent getLaunchIntentForPackage(String p){regularQueries++;if(regularFailure!=null)throw regularFailure;return regular;}
 public List<ResolveInfo> queryIntentActivities(Intent i,int flags){return Intent.CATEGORY_LEANBACK_LAUNCHER.equals(i.category)?tv:phone;}
}''',
 'android/app/Activity.java':'''package android.app;public class Activity extends android.content.Context {
 public android.os.Bundle options;public void startActivity(android.content.Intent i,android.os.Bundle b){options=b;startActivity(i);}
}''',
 'android/app/ActivityOptions.java':'''package android.app;public class ActivityOptions {
 public static RuntimeException failure;public static int width,height;public static android.os.Bundle last;
 public static ActivityOptions makeScaleUpAnimation(android.view.View v,int x,int y,int w,int h){if(failure!=null)throw failure;width=w;height=h;return new ActivityOptions();}
 public android.os.Bundle toBundle(){return last=new android.os.Bundle();}
}''',
 'android/view/View.java':'package android.view;public class View {}',
 'android/provider/Settings.java':'''package android.provider;public class Settings {public static final String ACTION_SETTINGS="settings",ACTION_WIRELESS_SETTINGS="wireless",ACTION_WIFI_SETTINGS="wifi";}''',
 'android/media/tv/TvContract.java':'''package android.media.tv;public class TvContract {public static android.net.Uri buildChannelUriForPassthroughInput(String id){return android.net.Uri.parse("passthrough:"+id);}}''',
 'android/app/SearchManager.java':'package android.app;public class SearchManager {public static final String QUERY="query";}',
 'android/content/DialogInterface.java':'''package android.content;public interface DialogInterface {interface OnClickListener {void onClick(DialogInterface dialog,int which);}}''',
 'android/app/AlertDialog.java':'''package android.app;import android.content.*;public class AlertDialog {
 public static int selected;public static String[] labels;
 public static class Builder {
  DialogInterface.OnClickListener action;public Builder(Context c){}public Builder setTitle(String s){return this;}
  public Builder setItems(String[] values,DialogInterface.OnClickListener l){labels=values;action=l;return this;}
  public Builder setNegativeButton(String s,DialogInterface.OnClickListener l){return this;}
  public void show(){action.onClick(null,selected);}
 }
}''',
 'android/widget/Toast.java':'''package android.widget;public class Toast {public static final int LENGTH_SHORT=0;public static int shown;
 public static Toast makeText(android.content.Context c,String s,int length){return new Toast();}public void show(){shown++;}}''',
 'android/util/Log.java':'''package android.util;import java.util.*;public class Log {
 public static int errors,infos;public static final List<String> messages=new ArrayList<>();
 public static void i(String tag,String value){infos++;messages.add(value);}public static void e(String tag,String value){errors++;messages.add(value);}
 public static void reset(){errors=infos=0;messages.clear();}
}''',
 'io/tvos/AppLog.java':Path('app/src/io/tvos/AppLog.java').read_text(),
 'io/tvos/AppLaunch.java':Path('app/src/io/tvos/AppLaunch.java').read_text(),
 }
