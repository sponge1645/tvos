"""Execute production catalog storage/schema with JSON-container and preference adapters."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import files
sources=files()
sources['org/json/JSONObject.java']=sources['org/json/JSONObject.java'].replace('put(String k,Object v){','put(String k,Object v)throws JSONException{')
sources['org/json/JSONArray.java']=sources['org/json/JSONArray.java'].replace(' private final List<Object> a=new ArrayList<>();',''' private static final Map<String,JSONArray> saved=new HashMap<>();private static int serial;
 private final List<Object> a=new ArrayList<>();public JSONArray(){}public JSONArray(String text)throws JSONException {JSONArray old=saved.get(text);if(old==null)throw new JSONException("invalid JSON");a.addAll(old.a);}public String toString(){String key="array-"+(++serial);saved.put(key,this);return key;}
''')
sources.update({
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public class SharedPreferences {public final Map<String,Object> data=new HashMap<>();public int clears;public int getInt(String k,int d){return (Integer)data.getOrDefault(k,d);}public long getLong(String k,long d){return (Long)data.getOrDefault(k,d);}public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}public String getString(String k,String d){return (String)data.getOrDefault(k,d);}public SharedPreferences edit(){return this;}public SharedPreferences clear(){clears++;data.clear();return this;}public SharedPreferences putInt(String k,int v){data.put(k,v);return this;}public SharedPreferences putLong(String k,long v){data.put(k,v);return this;}public SharedPreferences putString(String k,String v){data.put(k,v);return this;}public void apply(){}}''',
'io/tvos/AppLog.java':'package io.tvos;final class AppLog {static int errors;static void w(String s){errors++;}static void unexpected(String s,RuntimeException e){errors++;}}',
'io/tvos/StoreHarness.java':'''package io.tvos;import java.util.*;import org.json.*;import io.tvos.Recs.Pick;import android.content.SharedPreferences;
public class StoreHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  SharedPreferences prefs=new SharedPreferences();prefs.data.put("version",15);prefs.data.put("picks","broken");RecsStore store=new RecsStore(prefs);RecsStore.Snapshot empty=store.load();check(prefs.clears==1&&empty.picks.isEmpty(),"schema mismatch clears only recs preferences");
  Pick p=new Pick();p.id=42;p.kind="movie";p.title="Title";p.backdrop="/b";p.poster="/p";p.rating=8.1;p.meta="Drama";p.duration="120 min";p.movieOrigin="2020 · USA";p.tvOrigin="";p.app="test";p.pkg="pkg";p.detailed=true;
  store.save(Arrays.asList(p),Arrays.asList(p),Arrays.asList(p),2,1234,"en",99);
  check(prefs.data.get("version").equals(16)&&prefs.data.keySet().containsAll(Arrays.asList("picks","newPicks","detectivePicks","period","listAt","language","keyStamp")),"schema version and keys retained");
  RecsStore.Snapshot loaded=store.load();Pick q=loaded.picks.get(0);check(loaded.picks.size()==1&&loaded.newPicks.size()==1&&loaded.detectivePicks.size()==1&&loaded.period==2&&loaded.listAt==1234&&loaded.keyStamp==99&&loaded.listLanguage.equals("en"),"all lists and stamps restored");
  check(q.id==42&&q.kind.equals("movie")&&q.title.equals("Title")&&q.backdrop.equals("/b")&&q.poster.equals("/p")&&q.rating==8.1&&q.duration.equals("120 min")&&q.movieOrigin.equals("2020 · USA")&&q.app.equals("test")&&q.pkg.equals("pkg")&&q.detailed,"pick metadata roundtrip through production codec");
  check(RecsStore.parse(new JSONArray().put("bad").put(new JSONObject().put("id",1))).size()==1,"malformed rows skipped");q=RecsStore.parse(new JSONArray().put(new JSONObject().put("id",1))).get(0);check(q.app==null&&q.pkg==null&&q.duration.isEmpty()&&!q.detailed,"older rows default missing fields");
  prefs.data.remove("language");prefs.data.put("english",true);check(store.load().listLanguage.equals("en"),"legacy english flag restored");prefs.data.put("english",false);check(store.load().listLanguage.equals("pt"),"historical language fallback preserved");
  prefs.data.put("picks","broken");int errors=AppLog.errors;check(store.load().picks.isEmpty()&&AppLog.errors>errors,"corrupt cache falls back and logs");
  System.out.println("PASS: catalog snapshot schema, all lists/fields/stamps, legacy flags, missing fields and corrupt cache fallback");
 }
}'''
})
sources['io/tvos/RecsStore.java']=Path('app/src/io/tvos/RecsStore.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-recs-store-') as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.StoreHarness'],check=True)
