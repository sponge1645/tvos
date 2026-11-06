package io.tvos;

import android.content.Context;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import io.nayuki.qrcodegen.QrCode;
import io.tvos.avatar.AvatarUploadServer;
import io.tvos.icloud.ICloudAlbum;
import java.net.InetAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Short-lived local form, using the same bounded server and per-session protection as avatars. */
final class ICloudSetup implements AutoCloseable {
    interface Listener { void ready(String address,Path qr,int modules); void message(int resource); void saved(); }
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile AvatarUploadServer server;
    private volatile boolean closed;
    ICloudSetup(Context context,Listener listener) {
        Context app=context.getApplicationContext();
        Thread setup=new Thread(() -> {
            try {
                LocalNetwork.Info network=LocalNetwork.read(app);if(network==null)throw new IOException("No LAN");
                byte[] page;
                try(InputStream in=app.getAssets().open("icloud/setup.html");ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>128*1024)throw new IOException("Page limit");out.write(b,0,n);}
                    page=new String(out.toByteArray(),StandardCharsets.UTF_8).replace("{{LANG}}",AppLanguage.current()==AppLanguage.RUSSIAN?"ru":"en").getBytes(StandardCharsets.UTF_8);
                }
                AvatarUploadServer session=new AvatarUploadServer(InetAddress.getByName(network.address),page,bytes -> {
                    final String link=new String(bytes,StandardCharsets.UTF_8).trim();
                    try {ICloudAlbum.photos(link,1);}catch(Exception failure){throw new IOException("Album unavailable");}
                    return new AvatarUploadServer.Prepared(){
                        public void commit() throws IOException {
                            if(!app.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("icloud_album",link).putString("video_dream_source","icloud").commit())throw new IOException("Save failed");
                        }
                        public void close(){ }
                    };
                },() -> main.post(() -> {if(!closed)listener.saved();}),"/icloud","text/plain",4096);
                server=session;if(closed){session.close();return;}
                QrCode code=QrCode.encodeText(session.url(),QrCode.Ecc.LOW);Path qr=new Path();
                for(int y=0;y<code.size;y++)for(int x=0;x<code.size;x++)if(code.getModule(x,y))qr.addRect(x+4,y+4,x+5,y+5,Path.Direction.CW);
                session.start();
                main.post(() -> {if(!closed)listener.ready(session.url(),qr,code.size+8);});
                main.postDelayed(() -> {if(!closed){session.close();listener.message(R.string.icloud_expired);}},10*60_000L);
            }catch(IOException|RuntimeException failure){AvatarUploadServer session=server;if(session!=null)session.close();main.post(() -> {if(!closed)listener.message(R.string.icloud_no_network);});}
        },"icloud-setup");setup.setDaemon(true);setup.start();
    }
    public void close(){closed=true;AvatarUploadServer session=server;if(session!=null)session.close();main.removeCallbacksAndMessages(null);}
}
