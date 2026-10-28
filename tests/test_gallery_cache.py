"""Execute the production gallery cache and GalleryPhotos adapter with real files and decoder leases."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import JSON,ARRAY,block
# Parser/network responses are not exercised here; actual cache and ownership use real filesystem operations.
java_json=JSON.replace('private final Map', 'public JSONObject(){}public JSONObject(String s){}public java.util.Iterator<String> keys(){return m.keySet().iterator();}private final Map')
sources={
 'org/json/JSONObject.java':java_json,'org/json/JSONArray.java':ARRAY,
 'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {public JSONException(String s){super(s);}}',
 'android/content/Context.java':'package android.content;public class Context {final java.io.File cache;public Context(java.io.File f){cache=f;}public java.io.File getCacheDir(){return cache;}}',
 'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
 'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void post(Runnable r){r.run();}}',
 'android/graphics/BitmapFactory.java':'package android.graphics;public class BitmapFactory {public static class Options {public boolean inJustDecodeBounds;public int outWidth=1920,outHeight=1080;}public static Object decodeFile(String p,Options o){return null;}}',
 'io/tvos/AppLog.java':'package io.tvos;class AppLog {static void w(String text){}}',
 'io/tvos/GalleryCache.java':Path('app/src/io/tvos/GalleryCache.java').read_text(),
 'io/tvos/GalleryPhotos.java':Path('app/src/io/tvos/GalleryPhotos.java').read_text(),
 'io/tvos/GalleryCacheHarness.java':'''package io.tvos;
import java.io.*;import java.util.*;import android.content.Context;
public class GalleryCacheHarness {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static File photo(File folder,int id,long size)throws Exception {folder.mkdirs();File f=new File(folder,id+".jpg");try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(size);}f.setLastModified(1000L+id);try(FileWriter w=new FileWriter(new File(folder,id+".json"))){w.write("metadata");}return f;}
 static long bytes(File folder){long bytes=0;for(File f:folder.listFiles())if(f.getName().endsWith(".jpg"))bytes+=f.length();return bytes;}
 static int count(File folder){return folder.listFiles((dir,name)->name.endsWith(".jpg")).length;}
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]),old=new File(root,"old");for(int i=0;i<95;i++)photo(old,i,80001);GalleryCache cache=new GalleryCache(old);
  check(count(old)==80,"95-file historical cache trimmed at startup");check(!new File(old,"0.json").exists()&&new File(old,"94.json").exists(),"photo and sidecar evicted together oldest first");
  Set<File> visited=new HashSet<>();for(int i=0;i<80;i++){File f=cache.next();check(f!=null&&f.isFile(),"playlist only contains existing files");visited.add(f);cache.release(f);}check(visited.size()==80,"remaining playlist has no holes");
  File sizeFolder=new File(root,"bytes");for(int i=0;i<4;i++)photo(sizeFolder,i,40L*1024*1024);GalleryCache sizeCache=new GalleryCache(sizeFolder);check(bytes(sizeFolder)<=GalleryCache.MAX_BYTES&&count(sizeFolder)==3,"byte budget independent of count using sparse files");
  File protectedFolder=new File(root,"leases");photo(protectedFolder,0,80001);GalleryCache leased=new GalleryCache(protectedFolder,1,160002);File active=leased.next();File again=leased.next();check(active.equals(again),"same file can have overlapping decoder leases");active.setLastModified(1);File fresh=photo(protectedFolder,1,80001);leased.add(fresh);leased.prune();check(active.exists()&&!fresh.exists(),"active decoder survives eviction even when oldest");
  leased.release(active);photo(protectedFolder,2,80001);leased.prune();check(active.exists(),"one release keeps second decoder protected");leased.release(again);photo(protectedFolder,3,80001);leased.prune();check(!active.exists()&&count(protectedFolder)==1,"after final release old photo can be evicted");
  File partialFolder=new File(root,"partial");photo(partialFolder,0,80001);new File(partialFolder,"download.tmp").createNewFile();new File(partialFolder,"settings.txt").createNewFile();GalleryCache partial=new GalleryCache(partialFolder,0,0);check(new File(partialFolder,"download.tmp").exists()&&new File(partialFolder,"settings.txt").exists(),"cleanup touches only gallery photos and matching sidecars");check(!partial.hasImages()&&partial.next()==null,"empty cache is valid");
  File externalFolder=new File(root,"external");File external=photo(externalFolder,0,80001);GalleryCache externalCache=new GalleryCache(externalFolder);external.delete();check(externalCache.next()==null&&!externalCache.hasImages(),"manual cache removal removes missing playlist entries");
  File appRoot=new File(root,"app");GalleryPhotos google=new GalleryPhotos(new Context(appRoot),"google");check(!google.hasImages(),"empty GalleryPhotos adapter");google.close();
  File googleFolder=new File(appRoot,"tvos_gallery_v2_google"),natureFolder=new File(appRoot,"tvos_gallery_v2_na");for(int i=0;i<95;i++)photo(googleFolder,i,80001);photo(natureFolder,0,80001);google=new GalleryPhotos(new Context(appRoot),"google");check(count(googleFolder)==80&&count(natureFolder)==1,"source budgets are isolated");File selected=google.next();check(selected!=null,"real adapter borrows from bounded cache");google.release(selected);google.close();
  System.out.println("PASS: whole GalleryCache/GalleryPhotos, 95→80 startup cleanup, byte cap, sidecars, LRU, overlapping decoder leases, empty/missing files and source isolation");
 }
}'''
}
dream=Path('app/src/io/tvos/DreamView.java').read_text()
prepare=block(dream,'    private void prepareNext(')
assert 'finally {\n                gallery.release(file);' in prepare
assert 'gallery.release(file);\n            return;' in prepare
with tempfile.TemporaryDirectory(prefix='tvos-gallery-cache-') as tmp:
 root=Path(tmp)
 for name,value in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 (root/'data').mkdir();subprocess.run(['java','-cp',tmp,'io.tvos.GalleryCacheHarness',str(root/'data')],check=True)
