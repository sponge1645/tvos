"""Execute the actual diagnostic file writer with deterministic time and JSON serialization adapters."""
from pathlib import Path
from tmdb_metadata_fixture import block
import subprocess,tempfile
sources={
'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {private static final long serialVersionUID=1L;public JSONException(){super("bad JSON");}}',
'org/json/JSONObject.java':'''package org.json;import java.util.*;public class JSONObject {public static JSONObject last;public static boolean fail;public static String encoded;public final Map<String,Object> fields=new LinkedHashMap<>();public JSONObject(){last=this;}public JSONObject put(String k,Object v)throws JSONException {if(fail)throw new JSONException();fields.put(k,v);return this;}public String toString(){return encoded;}}''',
'external/StatusTest.java':'''package external;import io.tvos.catalog.RecommendationStatus;import org.json.*;import java.nio.file.*;import java.nio.charset.*;import java.io.*;import java.util.*;
public class StatusTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  File directory=new File(args[0]);long[] time={123};int[] reads={0};RecommendationStatus status=new RecommendationStatus(()->{reads[0]++;return time[0];});Path path=directory.toPath().resolve("recs-status.json");
  check(!Files.exists(path),"construction does not create diagnostic file");JSONObject.encoded="{\\"count\\":7,\\"error\\":\\"Ошибка сети\\",\\"at\\":123}";status.write(directory,7,"Ошибка сети");
  check(Arrays.equals(Files.readAllBytes(path),JSONObject.encoded.getBytes(StandardCharsets.UTF_8)),"existing filename and UTF-8 serializer output retained");check(JSONObject.last.fields.keySet().equals(new LinkedHashSet<>(Arrays.asList("count","error","at")))&&JSONObject.last.fields.get("count").equals(7)&&JSONObject.last.fields.get("error").equals("Ошибка сети")&&JSONObject.last.fields.get("at").equals(123L)&&reads[0]==1,"count/error/time schema and one clock read");
  JSONObject.encoded="{}";time[0]=456;status.write(directory,0,"");check(new String(Files.readAllBytes(path),StandardCharsets.UTF_8).equals("{}")&&JSONObject.last.fields.get("at").equals(456L),"rewrite truncates previous data and samples fresh time");
  RecommendationStatus missing=new RecommendationStatus(()->{reads[0]++;return 1;});int before=reads[0];boolean failed=false;try{missing.write(new File(directory,"missing"),1,"");}catch(IOException expected){failed=true;}check(failed&&reads[0]==before,"I/O failure propagates before reading clock");
  JSONObject.fail=true;failed=false;try{status.write(directory,1,"");}catch(JSONException expected){failed=true;}check(failed,"JSON failure remains caller-owned");JSONObject.fail=false;status.write(directory,1,"");check(Files.size(path)==2,"writer recovers after serialization failure");
  System.out.println("PASS: actual diagnostic writer filename/schema, UTF-8 bytes, overwrite, clock sampling, I/O/JSON propagation and recovery");
 }
}'''}
sources['io/tvos/StatusAdapterTest.java']='''package io.tvos;import java.io.*;import java.util.*;import io.tvos.catalog.RecommendationStatus;
public class StatusAdapterTest {
 static class Context {File directory;boolean denied;int queries;File getFilesDir(){queries++;if(denied)throw new SecurityException();return directory;}}
 static class AppLog {static int unexpected,warnings;static void w(String s){warnings++;}static void unexpected(String s,RuntimeException e){unexpected++;}}
 final Context context=new Context();final RecommendationStatus status=new RecommendationStatus(()->123L);final List<Object> picks=new ArrayList<>();String error="";
''' + block(Path('app/src/io/tvos/Recs.java').read_text(),'    private void writeStatus()') + '''
 public static void main(String[] args){StatusAdapterTest h=new StatusAdapterTest();if(h.context.queries!=0)throw new AssertionError("status initialization must not access Android file storage");h.context.denied=true;h.writeStatus();if(AppLog.unexpected!=1)throw new AssertionError("existing runtime failure boundary lost");h.context.denied=false;h.context.directory=new File(args[0],"absent");h.writeStatus();if(AppLog.warnings!=1)throw new AssertionError("existing IO failure boundary lost");System.out.println("PASS: production status adapter retains runtime/IO handling and defers Android storage lookup");}
}'''
sources['io/tvos/catalog/RecommendationStatus.java']=Path('app/src/io/tvos/catalog/RecommendationStatus.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-recs-status-') as tmp:
 root=Path(tmp);output=root/'data';output.mkdir()
 for name,source in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'external.StatusTest',str(output)],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.StatusAdapterTest',str(output)],check=True)
