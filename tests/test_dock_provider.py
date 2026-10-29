"""Compile the complete Dock loader and exercise real channel queries with portrait-only Seena rows."""
from pathlib import Path
import subprocess,tempfile
files={
'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public ContentResolver resolver=new ContentResolver();public Context getApplicationContext(){return this;}public ContentResolver getContentResolver(){return resolver;}public SharedPreferences getSharedPreferences(String s,int m){return new SharedPreferences();}public java.io.File getCacheDir(){return new java.io.File(System.getProperty("java.io.tmpdir"));}}''',
'android/content/SharedPreferences.java':'package android.content;public class SharedPreferences {public boolean getBoolean(String k,boolean d){return d;}public SharedPreferences edit(){return this;}public SharedPreferences putBoolean(String k,boolean b){return this;}public void apply(){}}',
'android/app/Activity.java':'package android.app;public class Activity extends android.content.Context {public int checkSelfPermission(String s){return 0;}public void requestPermissions(String[] p,int n){}}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
'android/content/ContentResolver.java':'''package android.content;import android.net.Uri;import android.database.Cursor;public class ContentResolver {public String owner="com.seena.app";public boolean portrait=true;public Cursor query(Uri u,String[] columns,String sel,String[] args,String order){if(u.url.endsWith("/channel"))return new Cursor(new Object[][]{{1L,owner,"Movies","movies",""}});if(u.url.contains("preview_program"))return new Cursor(new Object[][]{{owner,"https://example.com/poster.jpg","",portrait?4:0,0,"Film","2026-01-01"}});return new Cursor(new Object[0][]);}public java.io.InputStream openInputStream(Uri u){return null;}}''',
'android/database/Cursor.java':'package android.database;public class Cursor implements AutoCloseable {Object[][] rows;int i=-1;public Cursor(Object[][] r){rows=r;}public boolean moveToNext(){return ++i<rows.length;}public String getString(int c){return rows[i][c]==null?null:rows[i][c].toString();}public long getLong(int c){return ((Number)rows[i][c]).longValue();}public int getInt(int c){return ((Number)rows[i][c]).intValue();}public void close(){}}',
'android/net/Uri.java':'package android.net;public class Uri {public String url;public static Uri parse(String s){Uri u=new Uri();u.url=s;return u;}}',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void removeCallbacks(Runnable r){}public void postDelayed(Runnable r,long n){}public void post(Runnable r){r.run();}}',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap {}',
'android/graphics/BitmapFactory.java':'package android.graphics;public class BitmapFactory {public static class Options {public boolean inJustDecodeBounds;public int outWidth,outHeight,inSampleSize;}public static Bitmap decodeFile(String f,Options o){return new Bitmap();}}',
'io/tvos/Backdrop.java':'package io.tvos;class Backdrop {static Backdrop hero(android.graphics.Bitmap b){return new Backdrop();}}',
'io/tvos/DockArtwork.java':'package io.tvos;class DockArtwork {String backdrop="",logo="";static DockArtwork find(String a,String b,String c)throws java.io.IOException,org.json.JSONException{return new DockArtwork();}}',
'io/tvos/PosterCache.java':'package io.tvos;class PosterCache {static String cacheName(String s){return Integer.toString(s.hashCode());}}',
'io/tvos/WatchingSources.java':'package io.tvos;class WatchingSources {static String id(String p){return p;}}',
'io/tvos/WatchingCatalog.java':'package io.tvos;class WatchingCatalog {static final int PERMISSION_REQUEST=1;}',
'io/tvos/AppLog.java':'package io.tvos;class AppLog {static void w(String s){}}',
'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {}',
'io/tvos/DockBackdrop.java':Path('app/src/io/tvos/DockBackdrop.java').read_text(),
'io/tvos/ProviderHarness.java':'''package io.tvos;import java.util.*;public class ProviderHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 @SuppressWarnings("unchecked")public static void main(String[] args)throws Exception {android.content.Context c=new android.content.Context();DockBackdrop loader=new DockBackdrop(c,a->{});java.lang.reflect.Method query=DockBackdrop.class.getDeclaredMethod("candidates",String.class);query.setAccessible(true);List<DockBackdrop.Candidate> items=(List<DockBackdrop.Candidate>)query.invoke(loader,"com.seena.app");check(items.size()==1&&items.get(0).urls.isEmpty()&&items.get(0).title.equals("Film")&&items.get(0).year.equals("2026"),"portrait-only Seena title remains eligible for TMDB landscape lookup");c.resolver.owner="top.rootu.lampa";c.resolver.portrait=false;items=(List<DockBackdrop.Candidate>)query.invoke(loader,"top.rootu.lampa");check(items.size()==1&&items.get(0).urls.size()==1,"published wide artwork retained");items=(List<DockBackdrop.Candidate>)query.invoke(loader,"com.seena.app");check(items.isEmpty(),"other publishers never leak into focused service");System.out.println("PASS: complete Dock loader, portrait-only Seena candidates, landscape programs and publisher isolation");}
}'''}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.ProviderHarness'],check=True,timeout=20)
