package io.tvos;

import android.content.Context;
import android.graphics.*;
import io.tvos.avatar.AvatarUploadServer;
import java.io.*;

/** Decode untrusted photos with a pixel budget; persist only a fresh 512px JPEG in private storage. */
final class AvatarPhotoStore {
    static File file(Context context) { return new File(context.getFilesDir(), "profile-avatar.jpg"); }
    static AvatarUploadServer.Prepared prepare(Context context, byte[] data) throws IOException {
        if(data.length==0||data.length>AvatarUploadServer.MAX_BYTES||!photoSignature(data))throw new IOException("Invalid photo");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>8192||bounds.outHeight>8192
                ||(long)bounds.outWidth*bounds.outHeight>40_000_000)throw new IOException("Invalid dimensions");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;
        Bitmap source=BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(source==null)throw new IOException("Invalid photo");
        File temp=null;Bitmap target=null;
        try {
            target=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(target);canvas.drawColor(Color.WHITE);
            float scale=Math.max(512f/source.getWidth(),512f/source.getHeight());
            float w=source.getWidth()*scale,h=source.getHeight()*scale;
            canvas.drawBitmap(source,null,new RectF((512-w)/2,(512-h)/2,(512+w)/2,(512+h)/2),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
            temp=File.createTempFile("avatar-", ".jpg",context.getFilesDir());
            try(FileOutputStream output=new FileOutputStream(temp)){
                if(!target.compress(Bitmap.CompressFormat.JPEG,90,output))throw new IOException("Cannot save photo");
                output.flush();output.getFD().sync();
            }
            final File prepared=temp;temp=null;
            return new AvatarUploadServer.Prepared(){
                public void commit()throws IOException{if(!prepared.renameTo(file(context)))throw new IOException("Cannot publish avatar");ProfileAvatar.selectCustom(context);}
                public void close(){prepared.delete();}
            };
        } finally {source.recycle();if(target!=null)target.recycle();if(temp!=null)temp.delete();}
    }
    static boolean photoSignature(byte[] b) {
        int n=b.length;
        if(n>=4&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255&&(b[n-2]&255)==255&&(b[n-1]&255)==217)return true;
        if(n>=32&&b[0]==(byte)137&&b[1]==80&&b[2]==78&&b[3]==71&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10
                &&b[n-8]==73&&b[n-7]==69&&b[n-6]==78&&b[n-5]==68)return true;
        return n>=20&&b[0]==82&&b[1]==73&&b[2]==70&&b[3]==70&&b[8]==87&&b[9]==69&&b[10]==66&&b[11]==80
                &&(((b[4]&255L)|((b[5]&255L)<<8)|((b[6]&255L)<<16)|((b[7]&255L)<<24))+8)==n;
    }
}
