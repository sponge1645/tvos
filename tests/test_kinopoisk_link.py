"""Exact catalogue identity, canonical title URLs, bounded lookup transport and caching."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import JSON,ARRAY
json=JSON.replace('public static final Object NULL=', 'public static JSONObject next;public JSONObject(){}public JSONObject(String s){if(next!=null)m.putAll(next.m);}\n public static final Object NULL=')
h=r'''package io.tvos;import org.json.*;import java.net.*;import java.io.*;
class LinkTest {
 static int requests;static int status=200;static boolean closed;static String body="{}";
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static JSONObject entry(String kind,int id,String... urls){JSONArray offers=new JSONArray();for(String u:urls)offers.put(new JSONObject().put("standardWebURL",u));return new JSONObject().put("node",new JSONObject().put("objectType",kind).put("content",new JSONObject().put("externalIds",new JSONObject().put("tmdbId",String.valueOf(id)))).put("offers",offers));}
 static JSONObject reply(JSONObject... entries){JSONArray a=new JSONArray();for(JSONObject e:entries)a.put(e);return new JSONObject().put("data",new JSONObject().put("popularTitles",new JSONObject().put("edges",a)));}
 static final String URL="https://hd.kinopoisk.ru/film/4ba2562d337ca9548d398f4a80509f08";
 interface Work{void run()throws Exception;}static void rejects(Work w)throws Exception{try{w.run();throw new AssertionError("expected failure");}catch(IOException expected){}}
 public static void main(String[] args)throws Exception{
  java.net.URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){protected URLConnection openConnection(URL u){requests++;closed=false;return new HttpURLConnection(u){
   public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closed=true;}
   public OutputStream getOutputStream(){return new ByteArrayOutputStream();}public int getResponseCode(){return status;}
   public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes());}
  };}});
  check(KinopoiskLink.select(reply(entry("MOVIE",438631,URL)),"movie",438631).equals(URL),"exact movie ID resolves");
  check(KinopoiskLink.select(reply(entry("SHOW",438631,URL)),"movie",438631).isEmpty(),"movie/series numeric ID collision rejected");
  check(KinopoiskLink.select(reply(entry("MOVIE",841,URL)),"movie",438631).isEmpty(),"Dune remake does not open older namesake");
  check(KinopoiskLink.select(reply(entry("SHOW",63435,URL)),"tv",63435).equals(URL),"series identity resolves");
  for(String u:new String[]{"http://hd.kinopoisk.ru/film/4ba2562d337ca9548d398f4a80509f08",URL.replace("hd.kinopoisk.ru","hd.kinopoisk.ru.evil.test"),URL+"/other",URL+"#fragment","https://www.kinopoisk.ru/film/447301/","kpatv://film/?filmId=438631"})
   check(KinopoiskLink.select(reply(entry("MOVIE",438631,u)),"movie",438631).isEmpty(),"unverified URL rejected: "+u);
  check(KinopoiskLink.canonical(URL+"/?utm_source=test").equals(URL),"tracking stripped from verified title URL");
  check(KinopoiskLink.select(reply(entry("MOVIE",438631,URL,URL+"?x=1")),"movie",438631).equals(URL),"duplicate offers deduplicated");
  check(KinopoiskLink.select(reply(entry("MOVIE",438631,URL,URL.replace("4ba2","aaaa"))),"movie",438631).isEmpty(),"conflicting destinations rejected");
  Recs.Pick p=new Recs.Pick();p.kind="movie";p.id=438631;p.title="Дюна";p.originalTitle="Dune";
  JSONObject.next=reply(entry("MOVIE",438631,URL));check(KinopoiskLink.resolve(p,()->false).equals(URL)&&closed,"lookup closes connection");int before=requests;
  check(KinopoiskLink.resolve(p,()->false).equals(URL)&&requests==before,"successful identity cached");
  p.id=999;JSONObject.next=reply();check(KinopoiskLink.resolve(p,()->false).isEmpty()&&requests==before+2,"localized and original titles tried without fuzzy matching");
  before=requests;rejects(()->KinopoiskLink.resolve(p,()->true));check(requests==before,"cancel before request");
  status=503;rejects(()->KinopoiskLink.resolve(p,()->false));check(closed,"HTTP failure closes connection");status=200;
  body="x".repeat(513*1024);rejects(()->KinopoiskLink.resolve(p,()->false));check(closed,"oversized response rejected and connection closed");
  System.out.println("PASS: exact movie/series IDs, safe HD URLs, ambiguity, transport bounds/cancel and positive cache");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 sources={'org/json/JSONObject.java':json,'org/json/JSONArray.java':ARRAY,'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {public JSONException(String s){super(s);}}','io/tvos/Recs.java':'package io.tvos;class Recs{static class Pick{int id;String kind,title,originalTitle;}}','io/tvos/KinopoiskLink.java':Path('app/src/io/tvos/KinopoiskLink.java').read_text(),'io/tvos/LinkTest.java':h}
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.LinkTest'],check=True)
