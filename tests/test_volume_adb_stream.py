"""Actual ADB framing/flow control over local sockets, including blocked-read cancellation."""
from pathlib import Path
import re
import subprocess
import tempfile
source=Path('app/src/io/tvos/Adb.java').read_text()
ids=sorted(set(re.findall(r'R\.string\.(\w+)',source)))
harness=r'''package io.tvos;
import java.io.*;import java.net.*;import java.lang.reflect.*;import java.nio.*;import java.nio.charset.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class StreamHarness {
 static final int OPEN=0x4e45504f,OKAY=0x59414b4f,WRTE=0x45545257,CLSE=0x45534c43;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static int[] read(DataInputStream in)throws IOException {byte[] h=new byte[24];in.readFully(h);ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);int cmd=b.getInt(),a=b.getInt(),c=b.getInt(),n=b.getInt();b.getInt();check(b.getInt()==(cmd^0xffffffff),"frame magic");byte[] data=new byte[n];in.readFully(data);if(cmd==WRTE)check(new String(data,StandardCharsets.US_ASCII).equals("PING\n"),"heartbeat bytes");return new int[]{cmd,a,c};}
 static void send(DataOutputStream out,int cmd,int a,int b,String data)throws Exception {byte[] d=data.getBytes(StandardCharsets.US_ASCII);byte[] h=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(a).putInt(b).putInt(d.length).putInt(0).putInt(cmd^0xffffffff).array();out.write(h,0,7);out.flush();Thread.sleep(10);out.write(h,7,17);out.write(d);out.flush();}
 static Adb adb(Socket socket)throws Exception {Constructor<Adb> c=Adb.class.getDeclaredConstructor(Socket.class);c.setAccessible(true);return c.newInstance(socket);}
 public static void main(String[] args)throws Exception {
  try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());Socket peer=server.accept()){
   peer.setSoTimeout(2500);Adb adb=adb(client);AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch output=new CountDownLatch(1);
   Thread worker=new Thread(()->{try{adb.stream("fixed command",new Adb.Stream(){public boolean active(){return true;}public void output(byte[] bytes){check(new String(bytes,StandardCharsets.US_ASCII).equals("READY\n"),"stream output");output.countDown();}});}catch(Throwable e){failure.set(e);}});worker.start();
   DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());int[] open=read(in);check(open[0]==OPEN,"opens shell channel");int local=open[1];send(out,OKAY,7,local,"");int[] ping=read(in);check(ping[0]==WRTE&&ping[2]==7,"first heartbeat after open ack");
   send(out,WRTE,7,local,"READY\n");int[] ack=read(in);check(ack[0]==OKAY,"output acknowledgement independent of pending input");check(output.await(1,TimeUnit.SECONDS),"output delivered");
   peer.setSoTimeout(650);boolean waited=false;try{read(in);}catch(SocketTimeoutException expected){waited=true;}check(waited,"no second heartbeat before first ACK");peer.setSoTimeout(2500);
   send(out,OKAY,7,local,"");check(read(in)[0]==WRTE,"heartbeat resumes after ACK");send(out,OKAY,7,local,"");send(out,CLSE,7,local,"");
   boolean close=false;for(int i=0;i<3&&!close;i++){int[] frame=read(in);close=frame[0]==CLSE;if(frame[0]==WRTE)send(out,OKAY,7,local,"");}check(close,"close frame acknowledged");worker.join(2000);check(!worker.isAlive()&&failure.get()==null,"clean stream shutdown");
  }
  for(boolean cancel:new boolean[]{true,false}){
   try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());Socket peer=server.accept()){
    Adb adb=adb(client);AtomicReference<Throwable> result=new AtomicReference<>();Thread t=new Thread(()->{try{adb.stream("blocked",new Adb.Stream(){public boolean active(){return true;}public void output(byte[] b){}});}catch(Throwable e){result.set(e);}});long began=System.nanoTime();t.start();read(new DataInputStream(peer.getInputStream()));if(cancel)adb.close();t.join(cancel?2000:7500);check(!t.isAlive()&&result.get() instanceof IOException,"close/ACK timeout releases blocked read");if(!cancel)check(System.nanoTime()-began>=4000000000L,"timeout not busy-looping");
   }
  }
  for(Thread t:Thread.getAllStackTraces().keySet())check(!t.isAlive()||!t.getName().equals("tvos-volume-heartbeat"),"no heartbeat thread leaked");
  System.out.println("PASS: actual ADB streaming frames, fragmented replies, output/input ACK independence, one outstanding heartbeat, EOF, cancellation and bounded missing-ACK timeout");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory)
 files={'io/tvos/AppLog.java':'package io.tvos;final class AppLog {static void w(String message){}}','io/tvos/Adb.java':source,'io/tvos/StreamHarness.java':harness,
 'android/content/Context.java':'package android.content;public class Context {public java.io.File getFilesDir(){return new java.io.File("unused");}}',
 'android/util/Base64.java':'package android.util;public class Base64 {public static final int NO_WRAP=2;public static String encodeToString(byte[] b,int flags){return java.util.Base64.getEncoder().encodeToString(b);}}',
 'io/tvos/ResourceFailure.java':'package io.tvos;public class ResourceFailure extends java.io.IOException {private static final long serialVersionUID=1L;ResourceFailure(int id){super("failure "+id);}}',
 'io/tvos/R.java':'package io.tvos;public final class R {public static final class string {'+''.join('public static final int '+name+'='+str(i)+';' for i,name in enumerate(ids))+'}}'}
 for name,content in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.StreamHarness'],check=True,timeout=15)
