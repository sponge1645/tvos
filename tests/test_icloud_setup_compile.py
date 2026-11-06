"""Compile the actual setup adapter with Java 8 rules used by the APK build."""
from pathlib import Path
import re,subprocess,tempfile
source=Path('app/src/io/tvos/ICloudSetup.java').read_text()
f={
'android/content/Context.java':'''package android.content;public class Context {public static final int MODE_PRIVATE=0;public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String s,int m){return new SharedPreferences();}public java.io.File getCacheDir(){return new java.io.File(".");}public Assets getAssets(){return new Assets();}public static class Assets {public java.io.InputStream open(String s)throws java.io.IOException{return new java.io.ByteArrayInputStream(new byte[0]);}}}''',
'android/content/SharedPreferences.java':'''package android.content;public class SharedPreferences {public String getString(String k,String d){return d;}public Editor edit(){return new Editor();}public static class Editor {public Editor putString(String k,String v){return this;}public Editor remove(String k){return this;}public boolean commit(){return true;}public void apply(){}}}''',
'android/graphics/Path.java':'package android.graphics;public class Path {public enum Direction {CW}public void addRect(float a,float b,float c,float d,Direction e){}}',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void post(Runnable r){}public void postDelayed(Runnable r,long n){}public void removeCallbacksAndMessages(Object o){}}',
'android/util/Log.java':'package android.util;public class Log {public static int w(String t,String s){return 0;}}',
'io/tvos/LocalNetwork.java':'package io.tvos;class LocalNetwork {static class Info {String address;}static Info read(android.content.Context c){return null;}}',
'io/tvos/AppLanguage.java':'package io.tvos;enum AppLanguage {RUSSIAN,ENGLISH;static AppLanguage current(){return RUSSIAN;}}',
'io/tvos/icloud/ICloudAlbum.java':'package io.tvos.icloud;public class ICloudAlbum {public static void photos(String s,int n)throws Exception {}}',
'io/tvos/ICloudSetup.java':source,
'io/tvos/avatar/AvatarUploadServer.java':Path('app/src/io/tvos/avatar/AvatarUploadServer.java').read_text(),
'io/tvos/R.java':'package io.tvos;class R {static class string {'+''.join('static final int '+s+'='+str(i)+';' for i,s in enumerate(sorted(set(re.findall(r'R.string.(\w+)',source)))))+'}}'}
for p in Path('app/src/io/nayuki/qrcodegen').glob('*.java'):f['io/nayuki/qrcodegen/'+p.name]=p.read_text()
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,text in f.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',d,*map(str,root.rglob('*.java'))],check=True)
print('PASS: actual iCloud setup, upload server and QR encoder compile under Java 8 rules')
