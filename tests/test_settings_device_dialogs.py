"""Execute production maintenance dialogs with controlled confirmations and temporary files."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write
sources={
'android/content/DialogInterface.java':'''package android.content;public interface DialogInterface {interface OnClickListener {void onClick(DialogInterface d,int which);}}''',
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {public final Map<String,String> values=new HashMap<>();public String getString(String k,String d){return values.getOrDefault(k,d);}public SharedPreferences edit(){return this;}public SharedPreferences putString(String k,String v){values.put(k,v);return this;}public void apply(){}}''',
'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public static final String ACTIVITY_SERVICE="activity";public final SharedPreferences prefs=new SharedPreferences();public SharedPreferences getSharedPreferences(String n,int m){return prefs;}}''',
'android/app/Activity.java':'''package android.app;import java.io.File;public class Activity extends android.content.Context {public File cache,external;public ActivityManager manager=new ActivityManager();public File getCacheDir(){return cache;}public File getExternalCacheDir(){return external;}public Object getSystemService(String s){return manager;}}''',
'android/app/ActivityManager.java':'package android.app;public class ActivityManager {public int calls;public boolean result=true;public boolean clearApplicationUserData(){calls++;return result;}}',
'android/app/AlertDialog.java':'''package android.app;import android.content.*;public class AlertDialog {public static Builder shown;public static class Builder {public DialogInterface.OnClickListener positive,negative;public Object view;public String title,message;public Builder(Context c){}public Builder setTitle(String s){title=s;return this;}public Builder setMessage(String s){message=s;return this;}public Builder setView(Object v){view=v;return this;}public Builder setNegativeButton(String s,DialogInterface.OnClickListener l){negative=l;return this;}public Builder setPositiveButton(String s,DialogInterface.OnClickListener l){positive=l;return this;}public AlertDialog show(){shown=this;return new AlertDialog();}public void accept(){positive.onClick(null,1);}public void cancel(){if(negative!=null)negative.onClick(null,0);}}}''',
'android/widget/EditText.java':'''package android.widget;public class EditText {public String text="";public boolean focus;public EditText(android.content.Context c){}public void setSingleLine(boolean b){}public void setText(String s){text=s;}public CharSequence getText(){return text;}public void selectAll(){}public void requestFocus(){focus=true;}}''',
'android/widget/Toast.java':'''package android.widget;public class Toast {public static final int LENGTH_SHORT=0,LENGTH_LONG=1;public static String message;final String text;Toast(String t){text=t;}public static Toast makeText(android.content.Context c,String s,int n){return new Toast(s);}public void show(){message=text;}}''',
'io/tvos/DeviceDialogsHarness.java':'''package io.tvos;
import java.nio.file.*;import android.app.*;import android.widget.*;
public class DeviceDialogsHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  AppLanguage.select("en");Path root=Paths.get(args[0]);Activity a=new Activity();a.cache=root.resolve("cache").toFile();a.external=root.resolve("external").toFile();
  Files.createDirectories(a.cache.toPath().resolve("nested"));Files.write(a.cache.toPath().resolve("nested/image"),new byte[]{1});Files.createDirectories(a.external.toPath());Files.write(a.external.toPath().resolve("video"),new byte[]{2});
  Path keep=root.resolve("keep");Files.createDirectories(keep);Files.write(keep.resolve("settings"),new byte[]{3});Files.createSymbolicLink(a.cache.toPath().resolve("link"),keep);
  int[] changed={0};SettingsDeviceDialogs d=new SettingsDeviceDialogs(a,()->changed[0]++);
  d.confirmClearCache();check(Files.exists(a.cache.toPath().resolve("nested/image")),"opening cache dialog does not delete");AlertDialog.shown.cancel();check(Files.exists(a.external.toPath().resolve("video")),"cancel preserves cache");
  d.confirmClearCache();AlertDialog.shown.accept();check(a.cache.isDirectory()&&a.cache.list().length==0&&a.external.isDirectory()&&a.external.list().length==0,"confirmation removes only cache contents");
  check(Files.exists(keep.resolve("settings")),"symlink target outside cache survives");check(Toast.message.equals(AppStrings.get(R.string.settings_cache_cleared)),"clear success message");
  a.external=null;d.confirmClearCache();AlertDialog.shown.accept();check(Toast.message.equals(AppStrings.get(R.string.settings_cache_cleared)),"missing external cache supported");
  d.confirmResetTvos();check(a.manager.calls==0,"reset requires confirmation");AlertDialog.shown.cancel();check(a.manager.calls==0,"cancel does not reset");d.confirmResetTvos();AlertDialog.shown.accept();check(a.manager.calls==1,"accepted reset invokes application data API once");
  a.manager.result=false;d.confirmResetTvos();AlertDialog.shown.accept();check(Toast.message.equals(AppStrings.get(R.string.settings_could_not_reset_tvos)),"failed reset message");
  a.manager=null;d.confirmResetTvos();AlertDialog.shown.accept();check(Toast.message.equals(AppStrings.get(R.string.settings_could_not_reset_tvos)),"absent manager safe fallback");
  a.prefs.values.put("device_name","Old");d.askDeviceName();EditText field=(EditText)AlertDialog.shown.view;check(field.text.equals("Old")&&field.focus,"name prefilled and focused");field.text=" Unsaved ";AlertDialog.shown.cancel();check(a.prefs.values.get("device_name").equals("Old")&&changed[0]==0,"cancel does not save or refresh");
  d.askDeviceName();field=(EditText)AlertDialog.shown.view;field.text=" Living room ";AlertDialog.shown.accept();check(a.prefs.values.get("device_name").equals("Living room")&&changed[0]==1,"accepted name trimmed and refresh delivered");
  AppLanguage.select("ru");d.askDeviceName();field=(EditText)AlertDialog.shown.view;field.text=" ";AlertDialog.shown.accept();check(a.prefs.values.get("device_name").equals("ТВ")&&changed[0]==2,"empty name retains historical fallback");
  check(AlertDialog.shown.title.equals(AppStrings.get(R.string.settings_name)),"dialog copy follows current language");
  System.out.println("PASS: maintenance confirmations, cache boundaries/symlinks, reset API/failures, name save/cancel and live localization");
 }
}'''
}
sources['io/tvos/SettingsDeviceDialogs.java']=Path('app/src/io/tvos/SettingsDeviceDialogs.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-device-dialogs-') as tmp:
 root=Path(tmp);write(root)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.DeviceDialogsHarness',str(root/'data')],check=True)
