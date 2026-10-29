"""Run real TMDB transport and Recs delegation against controlled URL connections."""
from pathlib import Path
import subprocess
import tempfile
source=Path('app/src/io/tvos/Recs.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);d=1;i=b+1
 while d:d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
files={
'io/tvos/TmdbClient.java':Path('app/src/io/tvos/TmdbClient.java').read_text(),
'io/tvos/Config.java':'package io.tvos;final class Config {static String TMDB_KEY="";}',
'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {public JSONException(String message){super(message);}}',
'org/json/JSONObject.java':'''package org.json;public class JSONObject {
 public final String text;public JSONObject(String text)throws JSONException {if(!text.startsWith("{")||!text.endsWith("}"))throw new JSONException("invalid JSON");this.text=text;}}
''',
'io/tvos/TmdbHarness.java':'''package io.tvos;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import org.json.*;
public class TmdbHarness {
 static int status=200,opens;static String response="{\\"title\\":\\"Ёжик и café\\"}";
 static boolean failStatus,failRead;static Connection last;
 static class Body extends InputStream {
  final ByteArrayInputStream data=new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
  boolean closed;
  @Override public int read()throws IOException{if(failRead)throw new IOException("read failed");return data.read();}
  @Override public int read(byte[] b,int offset,int length)throws IOException{if(failRead)throw new IOException("read failed");return data.read(b,offset,length);}
  @Override public void close()throws IOException{closed=true;data.close();}
 }
 static class Connection extends HttpURLConnection {
  Body body;boolean disconnected;
  Connection(URL url){super(url);}
  public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;}
  @Override public int getResponseCode()throws IOException{if(failStatus)throw new IOException("connection failed");return status;}
  @Override public InputStream getInputStream(){body=new Body();return body;}
 }
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
''' + method('    private JSONObject get(') + '''
 public static void main(String[] args)throws Exception{
  URL.setURLStreamHandlerFactory(protocol->new URLStreamHandler(){protected URLConnection openConnection(URL url){opens++;last=new Connection(url);return last;}});
  TmdbHarness h=new TmdbHarness();Config.TMDB_KEY="  ";check(!TmdbClient.hasKey(),"blank key not configured");
  try{h.get("/movie/1");throw new AssertionError("missing key allowed");}catch(IOException e){check(e.getMessage().equals("no TMDB key"),"missing-key error preserved");}
  check(opens==0,"no network without configured key");
  Config.TMDB_KEY="  api key+&  ";check(TmdbClient.hasKey(),"configured API key");JSONObject result=h.get("/movie/1");
  check(last.getURL().toString().equals("https://api.themoviedb.org/3/movie/1?api_key=api+key%2B%26"),"key trimmed and URL encoded");
  check(last.getRequestProperty("Authorization")==null&&"application/json".equals(last.getRequestProperty("Accept")),"v3 headers preserved");
  check(last.getConnectTimeout()==8000&&last.getReadTimeout()==8000,"both timeouts remain eight seconds");
  check(last.disconnected&&last.body.closed&&result.text.contains("Ёжик и café"),"UTF-8 body closed and connection disconnected");
  h.get("/search/multi?language=ru-RU&query=test");
  check(last.getURL().getQuery().equals("language=ru-RU&query=test&api_key=api+key%2B%26"),"existing query retained");
  Config.TMDB_KEY=" eyJ.example.token ";h.get("/movie/2?language=en-US");
  check(last.getURL().getQuery().equals("language=en-US")&&last.getRequestProperty("Authorization").equals("Bearer eyJ.example.token"),"v4 token is header only");
  for(int code:new int[]{401,403,429,500}){
   status=code;try{h.get("/movie/3");throw new AssertionError("HTTP error accepted");}catch(IOException e){
    check(e.getMessage().equals(code==401?"TMDB rejected the key":"TMDB returned HTTP "+code),"status error semantics preserved");
    check(!e.getMessage().contains(Config.TMDB_KEY.trim()),"status error omits credentials");}
   check(last.disconnected&&last.body==null,"error status disconnects without reading success body");
  }
  status=200;response="invalid";try{h.get("/movie/4");throw new AssertionError("invalid JSON accepted");}catch(JSONException expected){}
  check(last.disconnected&&last.body.closed,"invalid JSON closes body and disconnects");
  failStatus=true;try{h.get("/movie/5");throw new AssertionError("connection failure accepted");}catch(IOException expected){}
  check(last.disconnected,"connection failure disconnects");failStatus=false;
  response="{}";failRead=true;try{h.get("/movie/6");throw new AssertionError("read failure accepted");}catch(IOException expected){}
  check(last.disconnected&&last.body.closed,"read failure closes body and disconnects");failRead=false;
  response="{\\"text\\":\\""+"я".repeat(12000)+"\\"}";h.get("/movie/7");
  check(last.disconnected&&last.body.closed,"response larger than buffer is read and closed");
  java.util.List<HttpURLConnection> handles=new java.util.ArrayList<>();TmdbClient.get("/movie/8",handles::add);check(handles.size()==2&&handles.get(0)==last&&handles.get(1)==null,"transport registers active connection and clears it after completion");
  try{TmdbClient.get("/movie/9",connection->{if(connection!=null)throw new java.util.concurrent.CancellationException();});throw new AssertionError("stale request accepted");}catch(java.util.concurrent.CancellationException expected){}check(last.disconnected&&last.body==null,"cancellation before connect still disconnects without response read");
  System.out.println("PASS: Recs delegation, API key/token auth, URL encoding, timeouts, UTF-8, HTTP errors and response cleanup");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='tvos-tmdb-client-') as tmp:
 root=Path(tmp)
 for name,value in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.TmdbHarness'],check=True)
