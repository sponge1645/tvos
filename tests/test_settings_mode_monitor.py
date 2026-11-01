"""Execute production HomeMode polling, timing, region entry, notifications and lifecycle."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/content/Context.java':'package android.content;public class Context {}',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {public final List<Runnable> jobs=new ArrayList<>();public long delay;public void post(Runnable r){jobs.add(r);}public void postDelayed(Runnable r,long d){delay=d;jobs.add(r);}public void removeCallbacks(Runnable r){jobs.removeIf(v->v==r);}public void next(){if(jobs.isEmpty())throw new AssertionError("no job");jobs.remove(0).run();}}''',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long up=100,elapsed=100;public static long uptimeMillis(){return up;}public static long elapsedRealtime(){return elapsed;}}',
'org/json/JSONObject.java':'''package org.json;import java.util.*;public class JSONObject {final Map<String,Object> m=new HashMap<>();public JSONObject put(String k,Object v){m.put(k,v);return this;}public long optLong(String k){Object v=m.get(k);return v instanceof Number?((Number)v).longValue():0;}public String optString(String k){Object v=m.get(k);return v instanceof String?(String)v:"";}public boolean optBoolean(String k){return Boolean.TRUE.equals(m.get(k));}public JSONArray optJSONArray(String k){Object v=m.get(k);return v instanceof JSONArray?(JSONArray)v:null;}}''',
'org/json/JSONArray.java':'package org.json;import java.util.*;public class JSONArray {final List<Object> v=new ArrayList<>();public JSONArray put(Object o){v.add(o);return this;}public int length(){return v.size();}public JSONObject optJSONObject(int i){return v.get(i) instanceof JSONObject?(JSONObject)v.get(i):null;}}',
'io/tvos/HomeMode.java':'package io.tvos;import org.json.*;class HomeMode {static final String FREE="free";static JSONObject state=new JSONObject();static int reads;static JSONObject state(android.content.Context c){reads++;return state;}}',
'io/tvos/AutoRegion.java':'package io.tvos;class AutoRegion {static int enters;static void enter(android.content.Context c){enters++;}}',
'io/tvos/NoticeText.java':'package io.tvos;class NoticeText {static String render(org.json.JSONObject o){return o.optString("text");}}',
'io/tvos/SettingsModeMonitor.java':Path('app/src/io/tvos/SettingsModeMonitor.java').read_text(),
'io/tvos/MonitorHarness.java':'''package io.tvos;import android.os.*;import org.json.*;import java.util.*;
public class MonitorHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){Handler h=new Handler();boolean[] running={true},region={false};int[] refresh={0};List<String> messages=new ArrayList<>();SettingsModeMonitor m=new SettingsModeMonitor(new android.content.Context(),h,()->running[0],()->region[0],messages::add,()->refresh[0]++);
 m.start();h.next();check(h.delay==2000&&!m.busy()&&refresh[0]==1,"idle cadence");
 m.markPending();check(m.busy(),"pending switch immediately busy");h.next();check(h.delay==500,"pending switch uses fast polling");SystemClock.up+=9999;check(m.busy(),"pending window retained");SystemClock.up++;check(!m.busy(),"pending expires at ten seconds");
 HomeMode.state=new JSONObject().put("busyAt",SystemClock.elapsed).put("busy","switching").put("mode","free");h.next();check(m.busy()&&m.freeActive()&&h.delay==500,"busy cadence and active mode");
 SystemClock.elapsed+=600000;check(m.busy(),"busy valid at exact ten-minute limit");SystemClock.elapsed++;check(!m.busy(),"stale state ignored");SystemClock.elapsed=50;check(!m.busy(),"pre-reboot timestamp ignored");
 HomeMode.state=new JSONObject().put("mode","free").put("notices",new JSONArray().put(new JSONObject().put("text","done")));h.next();check(messages.equals(Arrays.asList("done"))&&h.delay==2000,"completion announces newest notice once");h.next();check(messages.size()==1,"idle notice not replayed");
 HomeMode.state=new JSONObject().put("mode","free").put("homeReverted",true);h.next();check(!m.freeActive(),"reverted mode not active");
 region[0]=true;h.next();h.next();check(AutoRegion.enters==1,"region enters once per visit");region[0]=false;h.next();region[0]=true;h.next();check(AutoRegion.enters==2,"reenter observed region");
 m.stop();check(h.jobs.isEmpty(),"stop removes pending poll");m.start();h.next();check(AutoRegion.enters==3,"stop resets region entry");
 running[0]=false;int reads=HomeMode.reads;h.next();check(h.jobs.isEmpty()&&HomeMode.reads==reads,"stale callback after pause does not read or reschedule");
 running[0]=true;m.start();HomeMode.state=new JSONObject().put("busyAt",SystemClock.elapsed).put("busy","active");h.next();m.stop();HomeMode.state=new JSONObject().put("notices",new JSONArray().put(new JSONObject().put("text","finished while hidden")));m.start();h.next();check(messages.get(1).equals("finished while hidden"),"busy completion across pause preserved");
 System.out.println("PASS: real mode monitor, pending/busy clocks and boundaries, reboot/stale timestamps, polling cadence, free/reverted mode, notices, region visits and cancellation");
 }}'''}
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.MonitorHarness'],check=True)
