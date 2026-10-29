"""Real automatic-region routing, bounded HTTP, cache/backoff and stale-result delivery."""
from pathlib import Path
import runpy,subprocess,tempfile
from tmdb_metadata_fixture import JSON, ARRAY
s=runpy.run_path('tests/test_local_network.py',run_name='region_stubs')['stubs'].copy()
s.pop('io/tvos/NetworkHarness.java')
s['android/net/NetworkCapabilities.java']=s['android/net/NetworkCapabilities.java'].replace('NET_CAPABILITY_VALIDATED=16','NET_CAPABILITY_INTERNET=12,NET_CAPABILITY_VALIDATED=16')
s['android/net/Network.java']='''package android.net;import java.net.*;import java.io.*;public class Network {public long id;public int opens;public String body="good";public int status=200;public Connection last;public long getNetworkHandle(){return id;}public URLConnection openConnection(URL url,Proxy proxy)throws IOException {if(proxy!=Proxy.NO_PROXY||!url.toString().startsWith("https://ipwho.is/"))throw new AssertionError("bound HTTPS only");opens++;return last=new Connection(url,body,status);}public static class Connection extends HttpURLConnection {final String body;final int status;public boolean closed;Connection(URL u,String b,int s){super(u);body=b;status=s;}public int getResponseCode(){return status;}public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}public boolean usingProxy(){return false;}public void connect(){}public void disconnect(){closed=true;}}}'''
s['android/content/SharedPreferences.java']='''package android.content;import java.util.*;public class SharedPreferences {final Map<String,Long> data=new HashMap<>();public long getLong(String k,long d){return data.getOrDefault(k,d);}public SharedPreferences edit(){return this;}public SharedPreferences putLong(String k,long v){data.put(k,v);return this;}public void apply(){}}'''
s['android/content/Context.java']='''package android.content;public class Context {public static final int MODE_PRIVATE=0;public static final String CONNECTIVITY_SERVICE="connectivity";public android.net.ConnectivityManager manager;public final SharedPreferences prefs=new SharedPreferences();public Context getApplicationContext(){return this;}public Object getSystemService(String s){return manager;}public SharedPreferences getSharedPreferences(String s,int mode){return prefs;}}'''
s['android/os/Looper.java']='package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}'
s['android/os/Handler.java']='''package android.os;public class Handler {public static final java.util.concurrent.BlockingQueue<Runnable> jobs=new java.util.concurrent.LinkedBlockingQueue<>();public Handler(Looper l){}public void post(Runnable r){jobs.add(r);}}'''
s['org/json/JSONObject.java']=JSON.replace('public JSONObject put(', 'public JSONObject(){}public static final Map<String,JSONObject> all=new HashMap<>();public JSONObject(String body)throws JSONException {if(!all.containsKey(body))throw new JSONException("invalid");m.putAll(all.get(body).m);}public boolean optBoolean(String k,boolean d){return m.containsKey(k)?Boolean.TRUE.equals(m.get(k)):d;}public JSONObject put(')
s['org/json/JSONArray.java']=ARRAY
s['org/json/JSONException.java']='package org.json;public class JSONException extends Exception {private static final long serialVersionUID=1L;public JSONException(String s){super(s);}}'
s['io/tvos/Prefs.java']='''package io.tvos;class Prefs {static boolean enabled;static long generation;static String city="Manual";static boolean automaticRegion(){return enabled;}static long regionGeneration(){return generation;}static String language(){return "en";}static boolean applyAutomaticCity(String name,String lat,String lon,String language,long token){if(!enabled||token!=generation)return false;city=name;return true;}}'''
s['io/tvos/HomeData.java']='package io.tvos;class HomeData {static HomeData get(Object c){return new HomeData();}void refreshIfStale(){}}'
s['io/tvos/AppLog.java']='package io.tvos;class AppLog {static void w(String s){}}'
s['io/tvos/AutoRegion.java']=Path('app/src/io/tvos/AutoRegion.java').read_text()
s['io/tvos/RegionHarness.java']='''package io.tvos;import android.content.*;import android.net.*;import android.os.Handler;import org.json.*;public class RegionHarness {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}static Network add(ConnectivityManager m,long id,int transport){Network n=new Network();n.id=id;m.networks.add(n);NetworkCapabilities caps=new NetworkCapabilities();caps.transports.add(transport);caps.validated=true;m.caps.put(n,caps);return n;}
 static void resetTime()throws Exception {for(String key:new String[]{"checkedAt","attemptedAt"}){java.lang.reflect.Field f=AutoRegion.class.getDeclaredField(key);f.setAccessible(true);f.setLong(null,0);}}
 static Runnable delivery()throws Exception {Runnable r=Handler.jobs.poll(5,java.util.concurrent.TimeUnit.SECONDS);check(r!=null,"worker posts a result");return r;}
 static void invalid(String key)throws Exception {try{AutoRegion.parse(key);throw new AssertionError("invalid result accepted");}catch(java.io.IOException expected){}}
 public static void main(String[] args)throws Exception {
 JSONObject good=new JSONObject().put("success",true).put("city","Paris").put("latitude",48.85).put("longitude",2.35);JSONObject.all.put("good",good);
 JSONObject.all.put("failed",new JSONObject().put("success",false));JSONObject.all.put("nan",new JSONObject().put("success",true).put("city","City").put("latitude",Double.NaN).put("longitude",1));JSONObject.all.put("range",new JSONObject().put("success",true).put("city","City").put("latitude",91).put("longitude",1));invalid("failed");invalid("nan");invalid("range");check(AutoRegion.parse("good").city.equals("Paris"),"valid provider coordinates");
 Context c=new Context();ConnectivityManager m=new ConnectivityManager();c.manager=m;Network wifi=add(m,1,1),ethernet=add(m,2,3),vpn=add(m,3,4);m.active=vpn;m.caps.get(vpn).transports.add(1);check(AutoRegion.physical(m)==ethernet,"reject VPN even when it advertises Wi-Fi");
 AutoRegion.ensure(c);check(ethernet.opens==0,"manual mode never contacts provider");Prefs.enabled=true;Prefs.generation++;
 AutoRegion.ensure(c);delivery().run();check(Prefs.city.equals("Paris")&&ethernet.opens==1&&vpn.opens==0&&wifi.opens==0,"lookup bound exclusively to physical Ethernet");check(ethernet.last.closed&&!ethernet.last.getInstanceFollowRedirects(),"HTTPS connection closed and redirects disabled");
 resetTime();AutoRegion.ensure(c);check(ethernet.opens==1,"persisted successful network result reused");
 Prefs.generation++;AutoRegion.ensure(c);Runnable late=delivery();Prefs.enabled=false;late.run();check(Prefs.city.equals("Paris"),"disabled mode rejects pending delivery");
 Prefs.enabled=true;Prefs.generation++;ethernet.status=302;AutoRegion.ensure(c);delivery().run();check(Prefs.city.equals("Paris")&&ethernet.last.closed,"redirect failure preserves saved city");int opened=ethernet.opens;
 java.lang.reflect.Field checked=AutoRegion.class.getDeclaredField("checkedAt");checked.setAccessible(true);checked.setLong(null,0);AutoRegion.ensure(c);check(ethernet.opens==opened,"failure backoff prevents repeated requests");
 Prefs.generation++;ethernet.status=200;ethernet.body=new String(new char[66000]).replace('\\0','x');AutoRegion.ensure(c);delivery().run();check(Prefs.city.equals("Paris")&&ethernet.last.closed,"oversized response rejected with cleanup");
 m.networks.remove(wifi);m.networks.remove(ethernet);Prefs.generation++;AutoRegion.ensure(c);check(vpn.opens==0&&AutoRegion.physical(m)==null,"no physical network never falls back through VPN");m.denied=true;check(AutoRegion.physical(m)==null,"restricted connectivity fails closed");
 System.out.println("PASS: actual auto-region worker, physical routing, cached results, failure backoff, stale mode guards, bounded HTTPS and no VPN fallback");System.exit(0);
 }}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,content in s.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RegionHarness'],check=True,timeout=30)
