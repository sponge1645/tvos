"""Unexpected failures retain code locations without serializing private exception messages."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/util/Log.java':'''package android.util;public class Log {public static final int DEBUG=3;public static boolean enabled;public static int debug;public static boolean isLoggable(String tag,int level){return enabled;}public static void d(String tag,String s){last=s;debug++;}public static String last;public static int errors;public static void i(String tag,String s){}public static void e(String tag,String s){last=s;errors++;}}''',
'io/tvos/AppLog.java':Path('app/src/io/tvos/AppLog.java').read_text(),
'io/tvos/LogHarness.java':'''package io.tvos;import android.util.Log;
public class LogHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  IllegalStateException e=new IllegalStateException("private key=SECRET",new IllegalArgumentException("private response body"));
  StackTraceElement[] frames=new StackTraceElement[100];for(int i=0;i<frames.length;i++)frames[i]=new StackTraceElement("Example","work","Example.java",i+1);e.setStackTrace(frames);
  AppLog.unexpected("catalog refresh",e);
  check(Log.errors==1&&Log.last.contains("catalog refresh")&&Log.last.contains("IllegalStateException")&&Log.last.contains("IllegalArgumentException"),"operation and fault types logged");
  check(Log.last.contains("Example.java:16")&&!Log.last.contains("Example.java:17"),"bounded code-location trace");
  check(!Log.last.contains("SECRET")&&!Log.last.contains("private response body"),"exception messages omitted");
  RuntimeException a=new RuntimeException("a"),b=new RuntimeException("b");a.initCause(b);b.initCause(a);
  AppLog.unexpected("cycle",a);check(Log.last.length()<3000,"cyclic cause chain bounded");
  AppLog.artwork("disabled");check(Log.debug==0,"artwork trace off by default");
  Log.enabled=true;AppLog.artworkError("lookup",new java.io.IOException("secret https://host?api_key=SECRET"));check(!Log.last.contains("SECRET")&&Log.last.contains("IOException"),"diagnostics omit arbitrary exception text and URLs");
  AppLog.artworkError("lookup",new java.io.IOException("TMDB returned HTTP 403"));check(Log.last.contains("HTTP 403"),"safe HTTP error retained");
  AppLog.artwork("title\\nline\\rnext");check(!Log.last.contains("\\n")&&!Log.last.contains("\\r"),"trace stays on one line");
  System.out.println("PASS: unexpected-fault context, bounded stack/cause diagnostics and omitted private messages");
 }
}
'''
}
with tempfile.TemporaryDirectory(prefix='tvos-app-log-') as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.LogHarness'],check=True)
