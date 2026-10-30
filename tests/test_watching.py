"""Execute production Watch Next parsing, permission/lifecycle, deduplication and localized captions."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import files as metadata_files
f=metadata_files()
f.pop('io/tvos/TmdbMetadata.java')
f.update({
'android/net/Uri.java':'''package android.net;public class Uri {final String s;private Uri(String s){this.s=s;}public static Uri parse(String s){return new Uri(s);}public String getLastPathSegment(){return s.substring(s.lastIndexOf('/')+1);}public String toString(){return s;}}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {public static final int PERMISSION_GRANTED=0;public static final java.util.Set<String> missing=new java.util.HashSet<>();public static class NameNotFoundException extends Exception {}public Object getApplicationInfo(String p,int flags)throws NameNotFoundException{if(missing.contains(p))throw new NameNotFoundException();return p;}public CharSequence getApplicationLabel(Object p){return \"App \"+p;}}''',
'android/content/ComponentName.java':'package android.content;public class ComponentName {final String owner;public ComponentName(String owner){this.owner=owner;}public String getPackageName(){return owner;}}',
'android/content/Intent.java':'''package android.content;import java.util.*;import android.net.Uri;public class Intent {
 public static final int URI_INTENT_SCHEME=1,FLAG_ACTIVITY_NEW_TASK=0x10000000;
 public static final Map<String,Intent> data=new HashMap<>();public ComponentName component;public String owner,extra;public int flags;public Object selector,clip;public Uri uri;
 public static Intent parseUri(String link,int flags)throws java.net.URISyntaxException {Intent original=data.get(link);if(original==null)throw new java.net.URISyntaxException(link,"bad");Intent i=new Intent();i.component=original.component;i.extra=original.extra;i.uri=original.uri;return i;}
 public ComponentName getComponent(){return component;}public Intent setSelector(Object o){selector=o;return this;}public Intent setPackage(String p){owner=p;return this;}public void setClipData(Object o){clip=o;}public Intent setFlags(int f){flags=f;return this;}public String getStringExtra(String key){return extra;}public Uri getData(){return uri;}
}''',
'android/content/Context.java':'''package android.content;public class Context {public boolean permitted;public final ContentResolver resolver=new ContentResolver();public Context getApplicationContext(){return this;}public int checkSelfPermission(String p){return permitted?0:-1;}public ContentResolver getContentResolver(){return resolver;}public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
'android/app/Activity.java':'''package android.app;public class Activity extends android.content.Context {public int requested;public void requestPermissions(String[] p,int code){requested=code;}}''',
'android/database/ContentObserver.java':'''package android.database;public abstract class ContentObserver {public ContentObserver(android.os.Handler h){}public void onChange(boolean self){}}''',
'android/database/Cursor.java':'''package android.database;public interface Cursor extends AutoCloseable {boolean moveToNext();String getString(int i);long getLong(int i);int getInt(int i);void close();}''',
'android/content/ContentResolver.java':'''package android.content;import java.util.*;import android.database.*;import android.net.Uri;
public class ContentResolver {public List<Object[]> rows=new ArrayList<>(),channels=new ArrayList<>(),previews=new ArrayList<>();public int queries,closes,registered,unregistered;public boolean denied;public ContentObserver observer;
 public void registerContentObserver(Uri u,boolean children,ContentObserver o){observer=o;registered++;}public void unregisterContentObserver(ContentObserver o){unregistered++;observer=null;}
 public Cursor query(Uri u,String[] columns,String a,String[] b,String sort){boolean channel=u.toString().equals("content://android.media.tv/channel"),preview=u.toString().contains("preview_program?");if(channel&&channels.isEmpty())return null;queries++;if(denied)throw new SecurityException();if(!channel&&!preview&&!sort.equals("last_engagement_time_utc_millis DESC"))throw new AssertionError("order");List<Object[]> snapshot=new ArrayList<>(channel?channels:preview?previews:rows);return new Cursor(){int at=-1;public boolean moveToNext(){return ++at<snapshot.size();}public String getString(int i){Object v=snapshot.get(at)[i];return v==null?null:v.toString();}public long getLong(int i){Object v=snapshot.get(at)[i];return v==null?0:((Number)v).longValue();}public int getInt(int i){return (int)getLong(i);}public void close(){closes++;}};}
}''',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {public static long delay;static final ArrayDeque<Runnable> tasks=new ArrayDeque<>();public Handler(Looper l){}public boolean post(Runnable r){tasks.add(r);return true;}public boolean postDelayed(Runnable r,long d){delay=d;tasks.add(r);return true;}public void removeCallbacks(Runnable r){tasks.removeIf(t->t==r);}public static void drain(){while(!tasks.isEmpty())tasks.remove().run();}}''',
'android/util/Log.java':'package android.util;public class Log {public static int i(String t,String m){return 0;}public static int w(String t,String m){return 0;}}',
'io/tvos/AppLaunch.java':'''package io.tvos;class AppLaunch {static android.content.Intent opened;static boolean start(android.content.Context c,android.content.Intent i,String op){opened=i;return true;}}'''
})
f['io/tvos/SeenaChannelRefresh.java']='package io.tvos;class SeenaChannelRefresh {static void request(Object c){}}'
f['io/tvos/Prefs.java']='package io.tvos;class Prefs {static boolean categoryEnabled(int i){return true;}static String source="all";static String watchingSource(){return source;}static void setWatchingSource(String value){source=value;}}'
f['org/json/JSONObject.java']=f['org/json/JSONObject.java'].replace('private final Map<String,Object> m=new HashMap<>();','''public static final Map<String,JSONObject> encoded=new HashMap<>();private final Map<String,Object> m=new HashMap<>();public JSONObject(){}public JSONObject(String s)throws JSONException{JSONObject value=encoded.get(s);if(value==null)throw new JSONException("bad JSON");m.putAll(value.m);}''')
for name in ['WatchingCatalog','WatchingItems','WatchingText']:
 f['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
f['io/tvos/WatchingHarness.java']='''package io.tvos;
import java.util.*;import java.util.concurrent.Executor;import android.content.*;import android.os.Handler;import android.net.Uri;import org.json.JSONObject;
public class WatchingHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Worker implements Executor {ArrayDeque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable r){tasks.add(r);}void drain(){while(!tasks.isEmpty())tasks.remove().run();}}
 static String link(String key,String owner,String json){Intent intent=new Intent();intent.component=new ComponentName(owner);intent.extra=json;intent.uri=Uri.parse("kinowatch://resume/42");Intent.data.put(key,intent);return key;}
 static Object[] row(String owner,String key,String title,long updated,String link){return new Object[]{1L,owner,key,title,"https://cdn.example/wide.jpg",link,"1","2",1800000L,840000L,updated,3};}
 static Recs.Pick pick(String key,long time){Recs.Pick p=new Recs.Pick();p.watchingKey=key;p.watchingUpdated=time;return p;}
 public static void main(String[] args){
  Ui.language("ru");
  Recs.Pick caption=new Recs.Pick();caption.pkg="top.rootu.lampa";caption.kind="movie";
  check(WatchingText.caption(caption).equals("Lampa"),"missing playback time shows service name");
  caption.pkg="com.seena.app";check(WatchingText.caption(caption).equals("Seena"),"Seena caption fallback");
  caption.kind="tv";caption.watchingSeason="1";caption.watchingEpisode="2";
  check(WatchingText.caption(caption).equals("С1 / Э2 · Seena"),"episode information retained with source name");
  String trace=WatchingItems.trace(Arrays.asList(caption));caption.title="Changed";
  check(!trace.equals(WatchingItems.trace(Arrays.asList(caption))),"trace detects content changes without logging titles");
  check(WatchingItems.remaining(1800000,840000)==16,"remaining minutes");
  check(WatchingItems.remaining(60001,0)==2,"round up partial minute");
  check(WatchingItems.remaining(0,0)==-1,"unknown duration stays unknown");
  check(WatchingItems.remaining(60000,999999)==0,"overshoot clamped");
  check(WatchingItems.remaining(60000,-1)==1,"negative position clamped");
  List<Recs.Pick> input=new ArrayList<>();for(int i=0;i<20;i++)input.add(pick("id"+i,i));
  Recs.Pick latest=pick("id3",100);input.add(latest);Collections.reverse(input);
  List<Recs.Pick> selected=WatchingItems.latest(input);
  check(selected.size()==10&&selected.get(0)==latest&&selected.get(1).watchingUpdated==19,"10 unique most recent titles");
  check(input.size()==21,"caller list unchanged");
  for(Recs.Pick p:input)p.pkg="watch.kino.mod";
  for(String pkg:new String[]{"com.seena.app","top.rootu.lampa","org.example.player"})for(int i=0;i<14;i++){Recs.Pick p=pick("id"+i,200+i);p.pkg=pkg;input.add(p);}
  selected=WatchingItems.latest(input);check(selected.size()==40,"All gives ten per service without cross-service deduplication");
  check(WatchingItems.latest(input,"seena").size()==10&&WatchingItems.latest(input,"missing").isEmpty(),"single-service filter and empty source");
  Recs.Pick alias=pick("id3",999);alias.pkg="watch.kino";input.add(alias);selected=WatchingItems.latest(input,"kinopub");check(selected.size()==10&&selected.get(0)==alias,"original and MOD share a quota and title identity");
  android.app.Activity context=new android.app.Activity();Worker worker=new Worker();int[] notices={0};WatchingCatalog catalog=new WatchingCatalog(context,()->notices[0]++,worker);
  catalog.start();check(worker.tasks.isEmpty()&&catalog.picks().isEmpty(),"no permission no query");
  catalog.requestPermission(context);check(context.requested==WatchingCatalog.PERMISSION_REQUEST,"runtime permission requested");
  context.permitted=true;
  JSONObject.encoded.put("resume",new JSONObject().put("id",42).put("posterBig","https://cdn.example/portrait.jpg"));
  String target=link("target","watch.kino.mod","resume");
  context.resolver.rows.add(row("watch.kino.mod","s42:1:2","Serial",300,target));
  context.resolver.rows.add(row("watch.kino.mod","s42:1:1","Old episode",100,target));
  context.resolver.rows.add(row("watch.kino","m7:0:0","Movie",200,link("movie","watch.kino",null)));
  Object[] movie=context.resolver.rows.get(2);movie[6]=null;movie[7]=null;movie[8]=1920000L;movie[9]=0L;movie[11]=0;
  context.resolver.rows.add(row("other.app","s999","Foreign",999,target));
  context.resolver.rows.add(row("watch.kino.mod","s8","Wrong component",999,link("wrong","other.app",null)));
  context.resolver.rows.add(row("watch.kino.mod","s9","Bad URI",999,"malformed"));
  catalog.refresh();catalog.refresh();check(worker.tasks.size()==1,"overlapping requests coalesced");worker.drain();Handler.drain();worker.drain();Handler.drain();
  check(catalog.picks().size()==2,"foreign rows, broken intents and duplicate episodes excluded");
  Recs.Pick serial=catalog.picks().get(0),film=catalog.picks().get(1);
  check(serial.id==0&&serial.watchingKey.equals("kinopub:42"),"source identity never becomes TMDB id");
  check(serial.poster.equals("https://cdn.example/portrait.jpg"),"portrait extracted from resume metadata");
  check(serial.meta.equals("С1 / Э2 · Осталось: 16 мин."),"serial caption");
  check(film.meta.equals("Осталось: 32 мин."),"movie caption omits season/episode");
  Ui.language("en");check(WatchingText.caption(serial).equals("S1 / E2 · 16 min left"),"English caption");Ui.language("ru");
  check(catalog.open(serial)&&AppLaunch.opened.owner.equals("watch.kino.mod")&&AppLaunch.opened.extra.equals("resume"),"resume extras and source package preserved");
  check(AppLaunch.opened.flags==Intent.FLAG_ACTIVITY_NEW_TASK&&AppLaunch.opened.selector==null&&AppLaunch.opened.clip==null,"external intent flags constrained");
  check(context.resolver.closes==context.resolver.queries,"cursors closed");
  context.resolver.rows.add(row("com.seena.app","id42","Seena title",500,link("seena","com.seena.app",null)));
  context.resolver.rows.add(row("top.rootu.lampa","id42","Lampa title",600,link("lampa","top.rootu.lampa",null)));
  context.resolver.rows.add(row("org.example.player","id42","Other title",700,link("other","org.example.player",null)));
  catalog.refresh();worker.drain();Handler.drain();check(catalog.picks().size()==5,"generic Watch Next publishers included");
  check(catalog.sources().equals(Arrays.asList("kinopub","seena","lampa","org.example.player")),"known sources pinned and other publishers discovered");
  check(catalog.sourceLabel("org.example.player").equals("App org.example.player"),"unknown publisher uses application label");
  android.content.pm.PackageManager.missing.add("com.seena.app");check(!catalog.sources().contains("seena"),"uninstalled service hidden even with stale cards");
  android.content.pm.PackageManager.missing.add("watch.kino");check(catalog.sources().contains("kinopub"),"MOD alone keeps KinoPub visible");
  android.content.pm.PackageManager.missing.add("watch.kino.mod");check(!catalog.sources().contains("kinopub"),"both KinoPub aliases absent hides source");
  android.content.pm.PackageManager.missing.clear();
  catalog.selectSource("seena");check(catalog.picks().size()==1&&catalog.picks().get(0).title.equals("Seena title"),"selection filters cached results immediately");
  check(catalog.open(catalog.picks().get(0))&&AppLaunch.opened.owner.equals("com.seena.app"),"generic resume link opens its own service");
  catalog.selectSource("lampa");catalog.refresh();worker.drain();Handler.drain();check(catalog.picks().size()==1&&catalog.picks().get(0).pkg.equals("top.rootu.lampa"),"filter survives provider refresh");
  catalog.selectSource("all");check(catalog.picks().size()==5,"All restores combined list");check(!catalog.forSource("kinopub").isEmpty()&&catalog.source().equals("all")&&catalog.picks().size()==5,"Dock source snapshot does not change Watching selection");
  int before=context.resolver.queries;context.resolver.observer.onChange(false);context.resolver.observer.onChange(false);check(Handler.delay==5000,"observer refreshes rate limited to five seconds");Handler.drain();check(worker.tasks.size()==1,"observer bursts debounced");worker.drain();Handler.drain();check(context.resolver.queries==before+1,"one refresh after changes");
  List<Recs.Pick> saved=catalog.picks();context.resolver.denied=true;catalog.refresh();worker.drain();Handler.drain();check(catalog.picks()==saved,"transient query failure keeps previous successful list");
  context.resolver.denied=false;catalog.refresh();catalog.stop();worker.drain();Handler.drain();check(catalog.picks()==saved,"late result after pause ignored");
  check(context.resolver.unregistered==1,"observer removed on pause");
  catalog.start();worker.drain();Handler.drain();check(context.resolver.registered==6,"observer restored on resume");catalog.stop();
  catalog.selectSource("seena");context.resolver.rows.clear();
  context.resolver.channels.add(new Object[]{323L,"com.seena.app","seena_tv_history","Seena — История"});
  context.resolver.channels.add(new Object[]{999L,"other.app","seena_tv_history","Seena — История"});
  context.resolver.previews.add(row("com.seena.app","old","Seena History First",1,link("history1","com.seena.app",null)));
  context.resolver.previews.add(row("com.seena.app","old2","Seena History Second",2,link("history2","com.seena.app",null)));
  catalog.start();worker.drain();Handler.drain();check(catalog.picks().size()==2&&catalog.picks().get(0).title.equals("Seena History First"),"Seena history channel read in publisher order");
  check(catalog.picks().get(0).watchingRemaining==-1,"preview duration never presented as remaining time");catalog.stop();
  context.resolver.channels.clear();context.resolver.previews.clear();
  context.resolver.channels.add(new Object[]{10L,"top.rootu.lampa","","History","lampa://top.rootu.lampa/update_channel/history"});
  context.resolver.previews.add(row("top.rootu.lampa","l1","Local history",1,link("local","top.rootu.lampa",null)));
  catalog.selectSource("lampa");catalog.start();worker.drain();Handler.drain();check(catalog.picks().size()==1&&catalog.picks().get(0).title.equals("Local history"),"Lampa history fallback without any account or sync dependency");
  check(catalog.open(catalog.picks().get(0))&&AppLaunch.opened.owner.equals("top.rootu.lampa"),"history card opens Lampa");catalog.stop();
  System.out.println("PASS: Lampa local history, Seena history channel and Watch Next permissions, foreign-row filtering, safe source intents, portrait extraction, dedup/sort/limit, remaining time, RU/EN captions, lifecycle and refresh coalescing");
 }
}
'''
f['io/tvos/ProviderImages.java']='package io.tvos;class ProviderImages {static boolean allowed(Object c,String v,String o){return v!=null&&v.startsWith("https://");}}'
f['android/os/SystemClock.java']='package android.os;public class SystemClock {public static long elapsedRealtime(){return 0;}}'
f['io/tvos/watching/WatchingSources.java']=Path('app/src/io/tvos/watching/WatchingSources.java').read_text()
for key in list(f):
 if key.endswith('.java') and 'WatchingSources.' in f[key] and 'package io.tvos;' in f[key]: f[key]=f[key].replace('package io.tvos;', 'package io.tvos;import io.tvos.watching.WatchingSources;')
with tempfile.TemporaryDirectory(prefix='tvos-watching-') as directory:
 root=Path(directory)
 for name,code in f.items():
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(code)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.WatchingHarness'],check=True,timeout=20)
