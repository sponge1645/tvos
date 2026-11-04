package io.tvos;

import android.content.Context;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import io.nayuki.qrcodegen.QrCode;
import io.tvos.avatar.AvatarUploadServer;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Owns the upload session only while its QR page is visible. */
final class AvatarUpload implements AutoCloseable {
    interface Listener { void ready(String address, Path qr, int modules); void failed(); void expired(); void uploaded(); }
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile AvatarUploadServer server;
    private volatile boolean closed;
    AvatarUpload(Context context, Listener listener) {
        Context app=context.getApplicationContext();
        Thread setup=new Thread(() -> {
            try {
                LocalNetwork.Info local=LocalNetwork.read(app);
                if(local==null)throw new IOException("No LAN");
                byte[] page;
                try(InputStream in=app.getAssets().open("avatar/upload.html");ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>128*1024)throw new IOException("Page too large");out.write(buffer,0,n);}
                    String lang=AppLanguage.current()==AppLanguage.RUSSIAN?"ru":"en";
                    page=new String(out.toByteArray(),StandardCharsets.UTF_8).replace("{{LANG}}",lang).getBytes(StandardCharsets.UTF_8);
                }
                AvatarUploadServer session=new AvatarUploadServer(InetAddress.getByName(local.address),page,
                        bytes -> AvatarPhotoStore.prepare(app,bytes),() -> main.post(() -> {if(!closed)listener.uploaded();}));
                server=session;
                if(closed){session.close();return;}
                QrCode code=QrCode.encodeText(session.url(),QrCode.Ecc.LOW);
                Path qr=new Path();
                for(int y=0;y<code.size;y++)for(int x=0;x<code.size;x++)if(code.getModule(x,y))qr.addRect(x+4,y+4,x+5,y+5,Path.Direction.CW);
                session.start();
                main.postDelayed(() -> { if (!closed) { session.close(); listener.expired(); } }, 10*60_000L);
                main.post(() -> {if(!closed)listener.ready(session.url(),qr,code.size+8);});
            } catch(IOException|RuntimeException failure){AvatarUploadServer session=server;if(session!=null)session.close();main.post(() -> {if(!closed)listener.failed();});}
        },"avatar-prepare");setup.setDaemon(true);setup.start();
    }
    @Override public void close(){closed=true;AvatarUploadServer session=server;if(session!=null)session.close();main.removeCallbacksAndMessages(null);}
}
