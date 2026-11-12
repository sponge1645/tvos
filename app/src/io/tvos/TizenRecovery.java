package io.tvos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** A durable recovery pointer, written before destructive native uninstall consent. Never contains app data. */
final class TizenRecovery {
    private static final String FILE="tizen-install.pending";
    final String version,url,sha256;
    TizenRecovery(String version,String url,String sha256){
        if(version==null||!version.matches("v?[0-9]+(?:\\.[0-9]+){1,3}")||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
        String prefix="https://github.com/reisxd/TizenTubeCobalt/releases/download/"+version+"/cobalt-";
        if(!((prefix+"arm.apk").equals(url)||(prefix+"arm64.apk").equals(url)))throw new IllegalArgumentException();
        this.version=version;this.url=url;this.sha256=sha256;
    }
    void write(File dir)throws IOException{
        File temp=File.createTempFile("tizen-install-",".tmp",dir);
        try{
            try(FileOutputStream stream=new FileOutputStream(temp);DataOutputStream out=new DataOutputStream(stream)){
                out.writeInt(1);out.writeUTF(version);out.writeUTF(url);out.writeUTF(sha256);out.flush();stream.getFD().sync();
            }
            if(!temp.renameTo(new File(dir,FILE)))throw new IOException("Recovery rename failed");
        }finally{if(temp.exists())temp.delete();}
    }
    static TizenRecovery read(File dir){
        File file=new File(dir,FILE);if(file.length()<1||file.length()>4096)return null;
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))){
            if(in.readInt()!=1)return null;TizenRecovery value=new TizenRecovery(in.readUTF(),in.readUTF(),in.readUTF());
            return in.read()==-1?value:null;
        }catch(IOException|IllegalArgumentException e){return null;}
    }
    static void clear(File dir){new File(dir,FILE).delete();}
}
