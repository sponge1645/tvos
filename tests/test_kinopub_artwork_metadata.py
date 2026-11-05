"""Exact publisher ID/year recovery, bounded HTTP response and cancellation cleanup."""
from pathlib import Path
import subprocess, tempfile
from tmdb_metadata_fixture import JSON, ARRAY

array = ARRAY.replace('private final List<Object> a=new ArrayList<>();', '''public static JSONArray response;private final List<Object> a=new ArrayList<>();public JSONArray(){}public JSONArray(String s)throws JSONException{if(!s.equals("ok"))throw new JSONException("bad");a.addAll(response.a);}''')
files = {
 'org/json/JSONObject.java': JSON,
 'org/json/JSONArray.java': array,
 'org/json/JSONException.java': 'package org.json;public class JSONException extends Exception {public JSONException(String s){super(s);}}',
 'io/tvos/KinoPubArtworkMetadata.java': Path('app/src/io/tvos/KinoPubArtworkMetadata.java').read_text(),
 'io/tvos/network/ConnectionObserver.java': Path('app/src/io/tvos/network/ConnectionObserver.java').read_text(),
 'io/tvos/PublisherHarness.java': r'''package io.tvos;
import java.net.*;import java.io.*;import org.json.*;import io.tvos.network.ConnectionObserver;
public class PublisherHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static class Connection extends HttpURLConnection {
  byte[] bytes="ok".getBytes();int status=200;boolean disconnected,streamClosed;
  Connection()throws Exception{super(new URL("https://test.invalid"));}
  public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;}
  public int getResponseCode(){return status;}
  public InputStream getInputStream(){return new ByteArrayInputStream(bytes){public void close()throws IOException{streamClosed=true;super.close();}};}
 }
 static class Observer implements ConnectionObserver {
  int opened,closed;boolean cancel;
  public void opened(HttpURLConnection c){opened++;if(cancel)throw new java.util.concurrent.CancellationException();}
  public void closed(HttpURLConnection c){closed++;}
 }
 public static void main(String[] args)throws Exception {
  JSONArray twilight=new JSONArray().put(new JSONObject().put("id",16995).put("value","Сумерки / Twilight (1998)"))
   .put(new JSONObject().put("id",348).put("value","Сумерки / Twilight (2008)"));
  check(KinoPubArtworkMetadata.matchYear(twilight,348).equals("2008"),"exact ID selects Twilight 2008 regardless of ordering");
  JSONArray buddy=new JSONArray().put(new JSONObject().put("id",7635).put("value","Приятель / Buddy (2013)"))
   .put(new JSONObject().put("id",128170).put("value","Приятель / Buddy (2026)"));
  check(KinoPubArtworkMetadata.matchYear(buddy,128170).equals("2026"),"exact ID selects Buddy 2026");
  check(KinoPubArtworkMetadata.matchYear(twilight,999).isEmpty(),"missing ID never uses name");
  twilight.put(new JSONObject().put("id",348).put("value","Сумерки (1998)"));
  check(KinoPubArtworkMetadata.matchYear(twilight,348).isEmpty(),"conflicting years for same ID rejected");
  check(KinoPubArtworkMetadata.matchYear(new JSONArray().put(new JSONObject().put("id",348).put("value","2008: A title")),348).isEmpty(),"unmarked title numbers are not years");
  check(KinoPubArtworkMetadata.year("lampa:348","x","",ConnectionObserver.NONE).isEmpty(),"other source cannot enter KinoPub lookup");
  JSONArray.response=buddy;Connection c=new Connection();Observer o=new Observer();
  check(KinoPubArtworkMetadata.readYear(c,128170,o).equals("2026")&&c.disconnected&&c.streamClosed&&o.opened==1&&o.closed==1,"success closes stream/connection and notifies observer");
  c=new Connection();c.bytes=new byte[131073];o=new Observer();
  try{KinoPubArtworkMetadata.readYear(c,348,o);throw new AssertionError("unbounded response");}catch(IOException expected){}
  check(c.disconnected&&c.streamClosed&&o.closed==1,"oversized body rejected and closed");
  c=new Connection();c.status=503;o=new Observer();
  try{KinoPubArtworkMetadata.readYear(c,348,o);throw new AssertionError("HTTP error");}catch(IOException expected){}
  check(c.disconnected&&o.closed==1,"HTTP failure closes connection");
  c=new Connection();o=new Observer();o.cancel=true;
  try{KinoPubArtworkMetadata.readYear(c,348,o);throw new AssertionError("cancellation");}catch(java.util.concurrent.CancellationException expected){}
  check(c.disconnected&&o.closed==1,"cancel during observer registration closes request");
  System.out.println("PASS: exact KinoPub ID/year, conflicts, response bound, HTTP cleanup and cancellation");
 }
}'''
}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,code in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.PublisherHarness'],check=True,timeout=20)
