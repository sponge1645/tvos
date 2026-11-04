package io.tvos.avatar;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** One short-lived LAN listener, one bounded request at a time, no general file serving. */
public final class AvatarUploadServer implements AutoCloseable {
    public static final int MAX_BYTES = 2*1024*1024;
    public interface Prepared extends AutoCloseable {
        void commit() throws IOException;
        @Override void close();
    }
    public interface Sink { Prepared prepare(byte[] photo) throws IOException; }
    private final ServerSocket server;
    private final byte[] page;
    private final String token, authority;
    private final Sink sink;
    private final Runnable uploaded;
    private final Object commitLock = new Object();
    private volatile boolean running = true;
    private volatile Socket active;
    private final long expires = System.nanoTime()+10*60_000_000_000L;
    public AvatarUploadServer(InetAddress address, byte[] page, Sink sink, Runnable uploaded) throws IOException {
        this.page=page; this.sink=sink; this.uploaded=uploaded;
        byte[] secret=new byte[16];new SecureRandom().nextBytes(secret);
        StringBuilder value=new StringBuilder();for(byte b:secret)value.append(String.format(Locale.ROOT,"%02x",b&255));token=value.toString();
        server=new ServerSocket();
        try {server.bind(new InetSocketAddress(address,0),2);server.setSoTimeout(1000);}
        catch(IOException|RuntimeException failed){try{server.close();}catch(IOException ignored){}throw failed;}
        authority=address.getHostAddress()+":"+server.getLocalPort();
    }
    public String url() { return "http://"+authority+"/avatar"; }
    public void start() { Thread thread=new Thread(this::serve,"avatar-upload");thread.setDaemon(true);thread.start(); }
    private void serve() {
        try {
            while(running && System.nanoTime()<expires) {
                try(Socket socket=server.accept()) {
                    active=socket;socket.setSoTimeout(3000);
                    if(!running)break;
                    handle(socket);
                } catch(SocketTimeoutException timeout) { /* bounded idle wait */ }
                catch(IOException|RuntimeException|OutOfMemoryError failure) { /* a failed/closed client never stops the session */ }
                finally {active=null;}
            }
        } finally {close();}
    }
    private void handle(Socket socket) throws IOException {
        InputStream in=new BufferedInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
        long deadline=System.nanoTime()+12_000_000_000L;
        try {
            int[] remaining={8192};String request=line(in,remaining,deadline);
            String[] parts=request.split(" ",-1);
            if(parts.length!=3 || !parts[2].equals("HTTP/1.1")){reply(out,400,"application/json","{}");return;}
            Map<String,String> headers=new HashMap<>();String header;
            while(!(header=line(in,remaining,deadline)).isEmpty()) {
                int colon=header.indexOf(':');if(colon<=0)throw new IOException("Bad header");
                String name=header.substring(0,colon).toLowerCase(Locale.ROOT),value=header.substring(colon+1).trim();
                if(headers.put(name,value)!=null)throw new IOException("Duplicate header");
            }
            if(!authority.equals(headers.get("host"))) {reply(out,404,"application/json","{}");return;}
            if(parts[0].equals("GET")&&(parts[1].equals("/avatar")||parts[1].equals("/avatar/"))){reply(out,200,"text/html; charset=utf-8",page);return;}
            if(!parts[0].equals("POST")||!parts[1].equals("/"+token+"/upload")){reply(out,404,"application/json","{}");return;}
            String origin=headers.get("origin");
            if(origin!=null&&!origin.equals("http://"+authority)){reply(out,403,"application/json","{}");return;}
            String type=headers.getOrDefault("content-type","").split(";",2)[0];
            if(!Arrays.asList("image/jpeg","image/png","image/webp").contains(type)||headers.containsKey("transfer-encoding")){reply(out,415,"application/json","{}");return;}
            long length;
            try{length=Long.parseLong(headers.getOrDefault("content-length","-1"));}catch(NumberFormatException bad){length=-1;}
            if(length<=0||length>MAX_BYTES){reply(out,413,"application/json","{}");return;}
            byte[] data=new byte[(int)length];int at=0;
            while(at<data.length){if(System.nanoTime()>deadline)throw new IOException("Request expired");int n=in.read(data,at,data.length-at);if(n<0)throw new EOFException();at+=n;}
            try(Prepared prepared=sink.prepare(data)) {
                synchronized(commitLock){if(!running||System.nanoTime()>=expires)throw new IOException("Session closed");prepared.commit();running=false;}
            } catch(IOException|RuntimeException|OutOfMemoryError invalid){reply(out,422,"application/json","{}");return;}
            reply(out,200,"application/json","{\"ok\":true}");uploaded.run();
        } catch(EOFException incomplete){ /* ignore a cancelled body */ }
    }
    private static String line(InputStream in,int[] remaining,long deadline) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(--remaining[0]<0||System.nanoTime()>deadline)throw new IOException("Header limit");if(c=='\n'){byte[] data=bytes.toByteArray();if(data.length==0||data[data.length-1]!='\r')throw new IOException("Bad line");return new String(data,0,data.length-1,StandardCharsets.US_ASCII);}bytes.write(c);}
        throw new EOFException();
    }
    private void reply(OutputStream out,int code,String type,String body)throws IOException{reply(out,code,type,body.getBytes(StandardCharsets.UTF_8));}
    private void reply(OutputStream out,int code,String type,byte[] body)throws IOException {
        if(type.startsWith("text/html"))body=new String(body,StandardCharsets.UTF_8).replace("{{NONCE}}",token).replace("{{UPLOAD_PATH}}","/"+token+"/upload").getBytes(StandardCharsets.UTF_8);
        String headers="HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+
                "\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"+
                "Content-Security-Policy: default-src 'none'; img-src blob: data:; style-src 'unsafe-inline'; script-src 'nonce-"+token+"'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);out.flush();
    }
    @Override public void close(){synchronized(commitLock){running=false;}try{server.close();}catch(IOException ignored){}Socket socket=active;if(socket!=null)try{socket.close();}catch(IOException ignored){}}
}
