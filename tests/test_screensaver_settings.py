"""Exercise production provider reads with separate caller/owner preference caches."""
import subprocess
import tempfile
from pathlib import Path
import xml.etree.ElementTree as ET

ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse('app/AndroidManifest.xml').getroot()
provider = next(p for p in manifest.findall('application/provider') if p.get(ns+'name') == '.ScreensaverSettingsProvider')
assert provider.get(ns+'process') == ':home' and provider.get(ns+'exported') == 'false'

sources = {
'android/os/Bundle.java': '''package android.os; import java.util.HashMap;
public class Bundle extends HashMap<String,Object> {
 public void putString(String k,String v){put(k,v);} public void putBoolean(String k,boolean v){put(k,v);}
 public String getString(String k){return (String)get(k);} public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}
}''',
'android/net/Uri.java': 'package android.net; public class Uri {public static Uri parse(String s){return new Uri();}}',
'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
String getString(String k,String d); boolean getBoolean(String k,boolean d); }''',
'android/content/Context.java': '''package android.content; public abstract class Context {
public static final int MODE_PRIVATE=0; public abstract SharedPreferences getSharedPreferences(String n,int m);
public abstract ContentResolver getContentResolver(); }''',
'android/content/ContentResolver.java': '''package android.content; import android.os.Bundle; import android.net.Uri;
public abstract class ContentResolver {public abstract Bundle call(Uri u,String m,String a,Bundle b);}''',
'android/content/ContentValues.java': 'package android.content; public class ContentValues {}',
'android/database/Cursor.java': 'package android.database; public interface Cursor {}',
'android/content/ContentProvider.java': '''package android.content; import android.database.Cursor; import android.net.Uri; import android.os.Bundle;
public abstract class ContentProvider {
 public Context context; public Context getContext(){return context;} public abstract boolean onCreate();
 public Bundle call(String m,String a,Bundle b){return null;} public abstract String getType(Uri u);
 public abstract Cursor query(Uri u,String[] p,String s,String[] a,String o);
 public abstract Uri insert(Uri u,ContentValues v); public abstract int delete(Uri u,String s,String[] a);
 public abstract int update(Uri u,ContentValues v,String s,String[] a);
}''',
'io/tvos/SettingsHarness.java': '''package io.tvos;
import android.content.*; import android.os.Bundle; import android.net.Uri; import java.util.*;
public class SettingsHarness {
 static class Prefs implements SharedPreferences {
  String source="apple"; boolean clock,location,weather;
  public String getString(String k,String d){return source;}
  public boolean getBoolean(String k,boolean d){return k.equals("aerial_show_clock")?clock:k.equals("aerial_show_location")?location:weather;}
 }
 static class App extends Context {
  Prefs prefs=new Prefs(); ScreensaverSettingsProvider owner;
  public SharedPreferences getSharedPreferences(String n,int m){return prefs;}
  public ContentResolver getContentResolver(){return new ContentResolver(){public Bundle call(Uri u,String m,String a,Bundle b){return owner.call(m,a,b);}};}
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  App home=new App(),dream=new App(); ScreensaverSettingsProvider provider=new ScreensaverSettingsProvider();
  provider.context=home; dream.owner=provider; home.owner=provider;
  check(ScreensaverSettingsProvider.read(dream).getString("video_dream_source").equals("apple"));
  for(String source:new String[]{"google","na","apple","google"}){
   home.prefs.source=source;home.prefs.clock=true;home.prefs.weather=true;home.prefs.location=true;
   Bundle snapshot=ScreensaverSettingsProvider.read(dream);
   check(snapshot.getString("video_dream_source").equals(source));
   check(snapshot.getBoolean("aerial_show_clock") && snapshot.getBoolean("screensaver_show_weather") && snapshot.getBoolean("aerial_show_location"));
  }
  check(dream.prefs.source.equals("apple")); // stale cache was never consulted
  boolean rejected=false;try{provider.call("write",null,null);}catch(IllegalArgumentException e){rejected=true;}check(rejected);
  System.out.println("PASS: private home-process provider, repeated source changes, overlay snapshot, stale caller cache ignored");
 }
}'''
}
sources['io/tvos/ScreensaverSettingsProvider.java'] = Path('app/src/io/tvos/ScreensaverSettingsProvider.java').read_text()
with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp)
    for name,content in sources.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*[str(root/n) for n in sources]],check=True)
    subprocess.run(['java','-cp',tmp,'io.tvos.SettingsHarness'],check=True)
