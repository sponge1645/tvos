"""Compile the whole launcher; exercise app routing and KinoPub HTTP/cache/lifecycle with controlled adapters."""
from pathlib import Path
import subprocess,tempfile
from app_launch_fixture import files
from settings_strings_fixture import write as write_strings
from tmdb_metadata_fixture import JSON,ARRAY,block
sources=files()
sources['android/content/Context.java']=sources['android/content/Context.java'].replace('public class Context {','public class Context {public static final int MODE_PRIVATE=0;public SharedPreferences prefs=new SharedPreferences();public SharedPreferences getSharedPreferences(String n,int m){return prefs;}')
sources['android/content/SharedPreferences.java']='''package android.content;import java.util.*;public class SharedPreferences {
 public Map<String,Integer> data=new HashMap<>();public int writes;public int getInt(String key,int fallback){return data.getOrDefault(key,fallback);}
 public Editor edit(){return new Editor();}public class Editor {String key;int value;public Editor putInt(String k,int v){key=k;value=v;return this;}public void apply(){writes++;data.put(key,value);}}
}'''
sources['android/os/Handler.java']='''package android.os;import java.util.*;public class Handler {public List<Runnable> queue=new ArrayList<>();public void post(Runnable r){queue.add(r);}public void drain(){for(Runnable r:new ArrayList<>(queue)){queue.remove(r);r.run();}}}'''
sources['org/json/JSONObject.java']=JSON
sources['org/json/JSONArray.java']=ARRAY.replace('private final List<Object> a=new ArrayList<>();','''public static List<JSONObject> response=new ArrayList<>();public static boolean malformed;
 private final List<Object> a=new ArrayList<>();public JSONArray(){}public JSONArray(String body)throws JSONException{if(malformed)throw new JSONException("bad");a.addAll(response);}''')
sources['org/json/JSONException.java']='package org.json;public class JSONException extends Exception {public JSONException(String message){super(message);}}'
sources['io/tvos/HomeApps.java']='''package io.tvos;import android.app.Activity;import android.content.*;import android.content.pm.*;import android.view.View;
class HomeApps {static class Item {String pkg,label;}static Item app(String pkg,String label){Item item=new Item();item.pkg=pkg;item.label=label;return item;}
 static Intent launchIntent(PackageManager pm,String pkg){return AppLaunch.launchIntent(pm,pkg);}
 static boolean open(Activity a,Item i,View v,int x,int y,int w,int h){Intent intent=launchIntent(a.getPackageManager(),i.pkg);return intent!=null&&AppLaunch.start(a,intent,"app");}
}'''
sources['io/tvos/Recs.java']='package io.tvos;class Recs {'+block(Path('app/src/io/tvos/Recs.java').read_text(),'    static final class Pick {')+'}'
for name in ['MovieAppLauncher','KinoPubLink']:
 sources['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
sources['io/tvos/MovieLauncherHarness.java']='''package io.tvos;
import android.app.*;import android.content.*;import android.content.pm.*;import android.os.Handler;import android.view.View;
import java.util.*;import java.util.concurrent.Executor;import java.net.*;import java.io.*;import org.json.*;
public class MovieLauncherHarness implements MovieAppLauncher.Host {
 final Activity activity=new Activity();final Handler handler=new Handler();final List<Runnable> queue=new ArrayList<>();
 final MovieAppLauncher launcher=new MovieAppLauncher(activity,new View(),queue::add,handler,this);
 boolean active=true;int epoch,toasts;static int requests,disconnects,status=200;static boolean ioFailure;
 public boolean running(){return active;}public int epoch(){return epoch;}public void toast(String text){toasts++;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static MovieLauncherHarness app(String pkg){MovieLauncherHarness h=new MovieLauncherHarness();h.activity.pm.tv.add(new ResolveInfo(pkg,"Video"));h.activity.pm.leanback=new Intent("launch").setPackage(pkg);return h;}
 static Recs.Pick pick(){Recs.Pick p=new Recs.Pick();p.kind="movie";p.id=42;p.title="Тест";p.originalTitle="Original";p.movieOrigin="2024";return p;}
 static JSONObject item(int id,String title,String type){return new JSONObject().put("id",id).put("value",title).put("type",type);}
 void worker(){for(Runnable r:new ArrayList<>(queue)){queue.remove(r);r.run();}}
 public static void main(String[] args)throws Exception{
  URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new HttpURLConnection(url){
   public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnects++;}
   public int getResponseCode()throws IOException{requests++;if(ioFailure)throw new IOException("private");check(getConnectTimeout()==6000&&getReadTimeout()==6000,"network timeouts retained");return status;}
   public InputStream getInputStream(){return new ByteArrayInputStream("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  };}}:null);
  MovieLauncherHarness missing=new MovieLauncherHarness();missing.launcher.choose(pick());check(missing.toasts==1&&missing.activity.starts==0,"empty providers");
  MovieLauncherHarness lampa=app("top.rootu.lampa");lampa.activity.failureFor=i->Intent.ACTION_VIEW.equals(i.action)?new ActivityNotFoundException("link"):null;
  lampa.launcher.choose(pick());check(lampa.activity.attempts.size()==2&&lampa.toasts==0,"Lampa normal-launch fallback");
  check(lampa.activity.attempts.get(0).uri.toString().endsWith("movie/42"),"TMDB movie link");
  Recs.Pick tv=pick();tv.kind="tv";lampa=app("top.rootu.lampa");lampa.launcher.choose(tv);check(lampa.activity.launched.uri.toString().endsWith("tv/42"),"TMDB series link");
  MovieLauncherHarness seena=app("com.seena.app");seena.launcher.choose(pick());check(seena.activity.starts==1&&seena.activity.launched.extras.get(SearchManager.QUERY).equals("Тест"),"Seena one search with original query");
  seena=app("com.seena.app");seena.activity.startFailure=new SecurityException("blocked");seena.launcher.choose(pick());check(seena.toasts==1&&seena.activity.starts==1,"Seena refusal no duplicate relaunch");
  MovieLauncherHarness vk=app("com.vk.tv");vk.launcher.choose(pick());check(vk.activity.launched.uri.toString().startsWith("vkvideo://video/search?query="),"VK deep link");
  MovieLauncherHarness filter=app("com.vk.tv");filter.activity.pm.tv.add(new ResolveInfo("unknown","AAA"));filter.activity.pm.phone.add(new ResolveInfo("com.seena.app","Another"));filter.activity.pm.phone.add(new ResolveInfo("com.vk.tv","Duplicate"));
  filter.launcher.choose(pick());check(Arrays.equals(AlertDialog.labels,new String[]{"Another","Video"}),"allowlist, phone providers, dedup and alphabetical labels");
  for(String pkg:new String[]{"watch.kino","watch.kino.mod"}){
   MovieLauncherHarness h=app(pkg);JSONArray.response=Arrays.asList(item(81,"Тест (2024)","movie"),item(82,"Тест (2000)","movie"),item(83,"Unrelated (2024)","movie"));
   h.launcher.choose(pick());check(h.queue.size()==1&&h.activity.starts==0,"lookup background");h.worker();check(h.handler.queue.size()==1&&h.activity.starts==0,"publication on main handler");h.handler.drain();
   check(h.activity.launched.pkg.equals(pkg)&&h.activity.launched.uri.toString().endsWith("/81")&&h.activity.prefs.writes==1,"exact identity year and target cached");
   int calls=requests;h.launcher.choose(pick());check(requests==calls&&h.queue.isEmpty()&&h.activity.starts==2,"cache avoids network");
   for(boolean stop:new boolean[]{true,false}){
    MovieLauncherHarness stale=app(pkg);stale.launcher.choose(pick());stale.worker();if(stop)stale.active=false;else stale.epoch++;
    stale.handler.drain();check(stale.activity.starts==0&&stale.activity.prefs.writes==0,"pause and stale epoch reject launch/cache publication");
   }
  }
  MovieLauncherHarness ambiguous=app("watch.kino");JSONArray.response=Arrays.asList(item(91,"Тест (2024)","movie"),item(92,"Тест (2024)","movie"));
  ambiguous.launcher.choose(pick());ambiguous.worker();ambiguous.handler.drain();check(ambiguous.activity.starts==0&&ambiguous.toasts==2,"ambiguous match never guessed");
  MovieLauncherHarness original=app("watch.kino.mod");JSONArray.response=Arrays.asList(item(93,"Original (2024)","movie"));original.launcher.choose(pick());original.worker();original.handler.drain();check(original.activity.launched.uri.toString().endsWith("/93"),"original title lookup retained");
  for(int failure=0;failure<3;failure++){
   MovieLauncherHarness h=app("watch.kino");status=failure==0?503:200;ioFailure=failure==1;JSONArray.malformed=failure==2;
   h.launcher.choose(pick());h.worker();h.handler.drain();check(h.activity.starts==0&&h.toasts==2,"HTTP/IO/JSON failure reported without launch");
  }
  check(requests==disconnects,"every connection disconnected");
  System.out.println("PASS: whole MovieAppLauncher, providers, links, KinoPub scoring/ambiguity, standard/MOD cache, HTTP failures and lifecycle publication guards");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-movie-launcher-') as tmp:
 root=Path(tmp);write_strings(root)
 for name,value in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.MovieLauncherHarness'],check=True)
