"""Production OTA refresh: bypass redirect caches, recheck ready releases and reuse only matching artifacts."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/SelfUpdate.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);i=b+1;d=1
 while d:
  d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
body=''.join(method(s)for s in ['    void maybeAutomatic(', '    void check(', '    private static boolean sameRelease(', '    private String readManifest(', '    private static HttpURLConnection connection('])
stubs={
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {
 public Map<String,Object> values=new HashMap<>();public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
 public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}public SharedPreferences edit(){return this;}
 public SharedPreferences putLong(String k,long v){values.put(k,v);return this;}public void apply(){} }''',
'org/json/JSONObject.java':'''package org.json;import java.util.*;public class JSONObject {
 public static Map<String,Map<String,Object>> fixtures=new HashMap<>();Map<String,Object> data;
 public JSONObject(String key){data=fixtures.get(key);}public String getString(String k){return (String)data.get(k);}
 public long getLong(String k){return ((Number)data.get(k)).longValue();} }'''}
harness='''package io.tvos;
import java.util.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.atomic.AtomicBoolean;import org.json.JSONObject;
public class OtaRefreshHarness {
 static final int IDLE=0,CHECKING=1,CURRENT=2,AVAILABLE=3,DOWNLOADING=4,READY=5,ERROR=6;
 static final long AUTO_INTERVAL=24*60*60_000L;
 static class Status {int kind;UpdateRelease release;Status(int k,UpdateRelease r,int p,String m){kind=k;release=r;}}
 static class PackageInfo {long code=2;long getLongVersionCode(){return code;}}
 static class PackageManager {PackageInfo installed=new PackageInfo();PackageInfo getPackageInfo(String p,int f){return installed;}}
 static class Context {
  static final int MODE_PRIVATE=0;File dir;PackageManager pm=new PackageManager();android.content.SharedPreferences prefs=new android.content.SharedPreferences();
  PackageManager getPackageManager(){return pm;}String getPackageName(){return "io.tvos";}File getCacheDir(){return dir;}
  android.content.SharedPreferences getSharedPreferences(String n,int m){return prefs;}
 }
 static class Queue {ArrayDeque<Runnable> jobs=new ArrayDeque<>();void execute(Runnable r){jobs.add(r);}void post(Runnable r){jobs.add(r);}void drain(){while(!jobs.isEmpty())jobs.remove().run();}}
 Context context=new Context();AtomicBoolean busy=new AtomicBoolean();Status status=new Status(IDLE,null,-1,"");Queue io=new Queue(),main=new Queue();int downloads;
 void publish(int k,UpdateRelease r,int p,String m){status=new Status(k,r,p,m);}void fail(Exception e){throw new AssertionError(e);}void download(){downloads++;}
 static String text(String ru,String en){return en;}
 static String offered="old";static List<Response> requests=new ArrayList<>();
 static class Response extends HttpURLConnection {
  final boolean redirect;boolean disconnected;Response(URL u){super(u);redirect=u.getPath().contains("/latest/");}
  public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;}
  public int getResponseCode(){return redirect?302:200;}
  public String getHeaderField(String key){return key.equals("Location")?"/releases/download/"+offered+"/update.json":null;}
  public InputStream getInputStream(){return new ByteArrayInputStream(offered.getBytes(StandardCharsets.UTF_8));}
 }
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 static Map<String,Object> metadata(String version,long code){Map<String,Object> m=new HashMap<>();m.put("packageName","io.tvos");m.put("versionName",version);m.put("versionCode",code);m.put("tag","v"+version);m.put("sha256",String.join("",Collections.nCopies(64,"a")));m.put("size",20000L);m.put("asset","tvOS.apk");return m;}
''' +body+'''
 void drain(){io.drain();main.drain();}
 public static void main(String[] args)throws Exception {
  URL.setURLStreamHandlerFactory(p->p.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL u){Response r=new Response(u);requests.add(r);return r;}}:null);
  org.json.JSONObject.fixtures.put("old",metadata("1.0.1",3));org.json.JSONObject.fixtures.put("new",metadata("1.0.2",4));
  OtaRefreshHarness h=new OtaRefreshHarness();h.context.dir=new File(args[0]);
  h.check(false);h.check(false);check(h.io.jobs.size()==1,"concurrent refresh is deduplicated");h.drain();
  check(h.status.kind==AVAILABLE&&h.status.release.versionCode==3,"first check sees offered 1.0.1");
  for(Response r:requests){check(!r.getUseCaches(),"cache disabled on every redirect hop");check("no-cache".equals(r.getRequestProperty("Cache-Control")),"revalidation on each hop");check(r.disconnected,"responses closed");}
  check(requests.get(0).getURL().getQuery().startsWith("check="),"latest redirect receives cache-busting query");
  UpdateRelease old=h.status.release;File apk=new File(h.context.dir,"tvos-update.apk");try(RandomAccessFile f=new RandomAccessFile(apk,"rw")){f.setLength(old.size);}
  h.status=new Status(READY,old,100,"");int count=requests.size();h.check(false);h.drain();
  check(requests.size()>count&&h.status.kind==READY,"manual refresh reaches server and retains matching ready file");
  offered="new";h.check(false);h.drain();check(h.status.kind==AVAILABLE&&h.status.release.versionCode==4,"newer offer replaces stale ready metadata");
  h.status=new Status(READY,h.status.release,100,"");offered="old";h.check(false);h.drain();
  check(h.status.kind==AVAILABLE&&h.status.release.versionCode==3,"server rollback also replaces cached 1.0.2 offer");
  h.context.prefs.values.put("auto_update",true);h.context.prefs.values.put("self_update_attempt",1L);h.status=new Status(READY,old,100,"");
  count=requests.size();h.maybeAutomatic(false);h.drain();check(requests.size()>count&&h.status.kind==READY&&h.downloads==0,"automatic checks revalidate ready release without duplicate download");
  offered="new";h.context.prefs.values.put("self_update_attempt",1L);h.maybeAutomatic(false);h.drain();
  check(h.downloads==1,"newer automatic offer downloads once");
  count=requests.size();h.maybeAutomatic(false);h.drain();check(requests.size()==count,"daily automatic throttle preserved");
  check(apk.delete(),"remove cached APK");offered="old";h.status=new Status(READY,old,100,"");h.check(false);h.drain();
  check(h.status.kind==AVAILABLE,"missing ready file is not reused");
  h.context.pm.installed.code=3;h.check(false);h.drain();check(h.status.kind==CURRENT,"equal installed version is current");
  System.out.println("PASS: cache headers/redirect nonce, ready refresh/reuse, rollback, missing file, automatic throttle and new offers");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-ota-refresh-')as directory:
 root=Path(directory)
 for name,content in stubs.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 (root/'OtaRefreshHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,'app/src/io/tvos/UpdateRelease.java',*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.OtaRefreshHarness',directory],check=True)
