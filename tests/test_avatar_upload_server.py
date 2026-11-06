"""Actual loopback HTTP upload session, headers/body caps, single use and cancellation-before-commit."""
from pathlib import Path
import subprocess,tempfile
with tempfile.TemporaryDirectory() as d:
 root=Path(d);(root/'AvatarUploadServer.java').write_text(Path('app/src/io/tvos/avatar/AvatarUploadServer.java').read_text())
 (root/'Harness.java').write_text('''import io.tvos.avatar.AvatarUploadServer;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
 public class Harness {
 static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
 static String request(AvatarUploadServer s,String method,String path,String extra,byte[] body)throws Exception{
  URI url=new URI(s.url());try(Socket socket=new Socket(url.getHost(),url.getPort())){socket.setSoTimeout(2000);
   String head=method+" "+path+" HTTP/1.1\\r\\nHost: "+url.getAuthority()+"\\r\\n"+extra+"\\r\\n";
   socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));if(body!=null)socket.getOutputStream().write(body);socket.getOutputStream().flush();
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=socket.getInputStream().read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}
 }
 static String uploadPath(AvatarUploadServer server)throws Exception {
  String page=request(server,"GET",new URI(server.url()).getPath(),"",null);
  return "/"+page.split("script-src 'nonce-")[1].split("'")[0]+"/upload";
 }
 public static void main(String[] args)throws Exception{
  AtomicInteger writes=new AtomicInteger();CountDownLatch uploaded=new CountDownLatch(1);
  AvatarUploadServer server=new AvatarUploadServer(InetAddress.getLoopbackAddress(),"<script nonce={{NONCE}}>ok</script>".getBytes(StandardCharsets.UTF_8), bytes->{
   if(bytes[0]!=42)throw new IOException("not a photo");return new AvatarUploadServer.Prepared(){public void commit(){writes.incrementAndGet();}public void close(){}};
  },uploaded::countDown);server.start();String path=new URI(server.url()).getPath();String uploadPath=uploadPath(server);check(path.equals("/avatar"),"short manual URL");
  String page=request(server,"GET",path,"",null);check(page.startsWith("HTTP/1.1 200"),"QR URL works");check(!page.contains("{{NONCE}}")&&page.contains("frame-ancestors 'none'"),"rendered nonce and restricted page");
  String[] split=page.split("\\r\\n\\r\\n",2);int length=Integer.parseInt(split[0].split("Content-Length: ")[1].split("\\r\\n")[0]);check(split[1].getBytes(StandardCharsets.UTF_8).length==length,"rendered Content-Length");
  check(request(server,"POST","/avatar/upload","Content-Type: image/jpeg\\r\\nContent-Length: 1\\r\\n",new byte[]{42}).startsWith("HTTP/1.1 404"),"short page URL does not authorize upload endpoint");
  check(request(server,"GET","/","",null).startsWith("HTTP/1.1 404"),"unknown path rejected");
  check(request(server,"POST",uploadPath,"Origin: http://evil.example\\r\\nContent-Type: image/jpeg\\r\\nContent-Length: 1\\r\\n",new byte[]{42}).startsWith("HTTP/1.1 403"),"foreign origin rejected");
  check(request(server,"POST",uploadPath,"Content-Type: application/zip\\r\\nContent-Length: 1\\r\\n",new byte[]{42}).startsWith("HTTP/1.1 415"),"non-photo MIME refused");
  check(request(server,"POST",uploadPath,"Content-Type: image/jpeg\\r\\nContent-Length: 2097153\\r\\n",null).startsWith("HTTP/1.1 413"),"oversized body rejected before allocation/read");
  check(request(server,"POST",uploadPath,"Content-Type: image/jpeg\\r\\nContent-Length: 1\\r\\n",new byte[]{1}).startsWith("HTTP/1.1 422"),"sink validates actual content");
  check(request(server,"POST",uploadPath,"Content-Type: image/jpeg\\r\\nContent-Length: 1\\r\\n",new byte[]{42}).startsWith("HTTP/1.1 200"),"valid photo succeeds");check(uploaded.await(2,TimeUnit.SECONDS)&&writes.get()==1,"exactly one commit");server.close();
  CountDownLatch preparing=new CountDownLatch(1),release=new CountDownLatch(1),discarded=new CountDownLatch(1);AtomicInteger late=new AtomicInteger();
  AvatarUploadServer cancelled=new AvatarUploadServer(InetAddress.getLoopbackAddress(),new byte[0],bytes->{preparing.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}return new AvatarUploadServer.Prepared(){public void commit(){late.incrementAndGet();}public void close(){discarded.countDown();}};},()->{});cancelled.start();String cancelledUpload=uploadPath(cancelled);
  Thread client=new Thread(()->{try{request(cancelled,"POST",cancelledUpload,"Content-Type: image/jpeg\\r\\nContent-Length: 1\\r\\n",new byte[]{42});}catch(Exception expected){}});client.start();check(preparing.await(2,TimeUnit.SECONDS),"prepare in progress");cancelled.close();release.countDown();client.join(2000);check(discarded.await(2,TimeUnit.SECONDS)&&late.get()==0,"leaving QR cancels publication and discards prepared image");
  AtomicInteger committed=new AtomicInteger(),notified=new AtomicInteger();
  try(AvatarUploadServer broken=new AvatarUploadServer(InetAddress.getLoopbackAddress(),new byte[0],bytes->new AvatarUploadServer.Prepared(){public void commit(){committed.incrementAndGet();}public void close(){}},notified::incrementAndGet)){
   broken.start();String host=new URI(broken.url()).getAuthority();
   byte[] wire=("POST "+uploadPath(broken)+" HTTP/1.1\\r\\nHost: "+host+"\\r\\nContent-Type: image/jpeg\\r\\nContent-Length: 1\\r\\n\\r\\nx").getBytes(StandardCharsets.US_ASCII);
   Socket disconnected=new Socket(){public InputStream getInputStream(){return new ByteArrayInputStream(wire);}public OutputStream getOutputStream(){return new OutputStream(){public void write(int b)throws IOException{throw new IOException("peer closed");}};}};
   java.lang.reflect.Method handle=AvatarUploadServer.class.getDeclaredMethod("handle",Socket.class);handle.setAccessible(true);
   try{handle.invoke(broken,disconnected);throw new AssertionError("response should fail");}catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof IOException,"response write failed");}
   check(committed.get()==1&&notified.get()==1,"successful commit notifies TV exactly once even when response fails");
  }
  AtomicInteger links=new AtomicInteger();
  try(AvatarUploadServer link=new AvatarUploadServer(InetAddress.getLoopbackAddress(),"link form".getBytes(StandardCharsets.UTF_8),bytes->{if(!new String(bytes,StandardCharsets.UTF_8).startsWith("https://www.icloud.com/sharedalbum/"))throw new IOException("Invalid link");return new AvatarUploadServer.Prepared(){public void commit(){links.incrementAndGet();}public void close(){}};},()->{},"/icloud","text/plain",4096)){
   link.start();check(new URI(link.url()).getPath().equals("/icloud"),"short manual iCloud route");String linkPath=uploadPath(link);
   check(request(link,"POST",linkPath,"Content-Type: image/jpeg\\r\\nContent-Length: 1\\r\\n",new byte[]{1}).startsWith("HTTP/1.1 415"),"iCloud form rejects image bodies");
   check(request(link,"POST",linkPath,"Content-Type: text/plain\\r\\nContent-Length: 4097\\r\\n",null).startsWith("HTTP/1.1 413"),"iCloud link body is bounded");
   byte[] body="https://www.icloud.com/sharedalbum/#A1ExampleAlbum".getBytes(StandardCharsets.UTF_8);
   check(request(link,"POST",linkPath,"Content-Type: text/plain;charset=UTF-8\\r\\nContent-Length: "+body.length+"\\r\\n",body).startsWith("HTTP/1.1 200")&&links.get()==1,"local link form commits once");
  }
  System.out.println("PASS: real HTTP, session token, nonce/length, type/body bounds, Origin, single commit and cancellation race");
 }}''')
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',d,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'Harness'],check=True,timeout=20)
