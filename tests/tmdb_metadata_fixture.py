"""JSON and UI fixtures shared by metadata and catalog-language JVM regressions."""
from pathlib import Path

def block(source, signature):
 a=source.index(signature);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]

JSON=r'''package org.json;import java.util.*;public class JSONObject {
 public static final Object NULL=new Object(){public String toString(){return "null";}};
 private final Map<String,Object> m=new HashMap<>();public JSONObject put(String k,Object v){m.put(k,v);return this;}
 public String optString(String k){return optString(k,"");}public String optString(String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}
 public int optInt(String k){return optInt(k,0);}public int optInt(String k,int d){Object v=m.get(k);return v instanceof Number?((Number)v).intValue():d;}
 public double optDouble(String k,double d){Object v=m.get(k);return v instanceof Number?((Number)v).doubleValue():d;}
 public boolean optBoolean(String k){return Boolean.TRUE.equals(m.get(k));}
 public boolean isNull(String k){return !m.containsKey(k)||m.get(k)==null||m.get(k)==NULL;}
 public JSONArray optJSONArray(String k){Object v=m.get(k);return v instanceof JSONArray?(JSONArray)v:null;}
 public JSONArray getJSONArray(String k)throws JSONException{JSONArray a=optJSONArray(k);if(a==null)throw new JSONException(k);return a;}
 public JSONObject optJSONObject(String k){Object v=m.get(k);return v instanceof JSONObject?(JSONObject)v:null;}
}'''
ARRAY=r'''package org.json;import java.util.*;public class JSONArray {
 private final List<Object> a=new ArrayList<>();public JSONArray put(Object v){a.add(v);return this;}public int length(){return a.size();}
 public JSONObject getJSONObject(int i)throws JSONException{JSONObject o=optJSONObject(i);if(o==null)throw new JSONException("not an object");return o;}
 public JSONObject optJSONObject(int i){return i>=0&&i<a.size()&&a.get(i) instanceof JSONObject?(JSONObject)a.get(i):null;}
 public String optString(int i,String d){return i>=0&&i<a.size()?String.valueOf(a.get(i)):d;}
 public int optInt(int i){return i>=0&&i<a.size()&&a.get(i) instanceof Number?((Number)a.get(i)).intValue():0;}
}'''

def files():
 source=Path('app/src/io/tvos/Recs.java').read_text()
 return {
  'org/json/JSONObject.java':JSON,
  'org/json/JSONArray.java':ARRAY,
  'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {public JSONException(String s){super(s);}}',
  'io/tvos/Recs.java':'package io.tvos;final class Recs {\n'+block(source,'    static final class Pick {')+'\n}',
  'io/tvos/Ui.java':'''package io.tvos;final class Ui {
   static String language="en";static boolean ru,en=true,de,es;
   static void language(String s){AppLanguage.select(s);language=s;ru=s.equals("ru");en=s.equals("en");de=s.equals("de");es=s.equals("es");}
   static String tr(String pt,String english){return ru?Russian.text(english):en?english:pt;}
  }''',
  'io/tvos/AppLanguage.java':Path('app/src/io/tvos/AppLanguage.java').read_text(),
  'io/tvos/Russian.java':Path('app/src/io/tvos/Russian.java').read_text(),
  'io/tvos/TmdbMetadata.java':Path('app/src/io/tvos/TmdbMetadata.java').read_text(),
 }
