package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** APK downloads: check installed app and remote metadata before transferring an APK. */
public final class AppDownloads {
    private static final String[] NAMES={"AirPlay","Apple Music","TizenTube","INCY","Seena","Lampa","KinoPub"};
    private static final String[] PACKAGES={"io.github.jqssun.airplay","com.applemusicktv","io.gh.reisxd.tizentube.cobalt","llc.itdev.incy","com.seena.app","top.rootu.lampa","watch.kino"};
    private static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    private static final AtomicBoolean BUSY=new AtomicBoolean(false);
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private AppDownloads() {}
    private static String text(String russian, String english) {
        return Ui.ru ? russian : english;
    }
    private static final class Source {String url,version;Source(String u,String v){url=u;version=v;}}
    public static void start(Activity activity,int index){
        if(index<0||index>=NAMES.length||!BUSY.compareAndSet(false,true))return;
        final String name=NAMES[index];
        final AlertDialog progress=new AlertDialog.Builder(activity).setTitle(name)
            .setMessage(text("Проверка обновлений…", "Checking for updates…")).setCancelable(false).create();
        progress.show();
        new Thread(()->{
            try {
                PackageManager pm=activity.getPackageManager();
                PackageInfo old=null;
                try{old=pm.getPackageInfo(PACKAGES[index],0);}catch(PackageManager.NameNotFoundException ignored){}
                Source src=resolve(index);
                // Version strings are compared only when both are numeric and unambiguous.
                // A missing/ambiguous version MUST NOT falsely mark an installed app current.
                if(old!=null && comparable(src.version,old.versionName)){
                    if(compare(src.version,old.versionName)<=0){
                        UI.post(()->{progress.dismiss();BUSY.set(false);message(activity,name,text("Приложение уже установлено", "App is already installed"));});return;
                    }
                }
                UI.post(()->{if(progress.isShowing())progress.setMessage(text("Загрузка APK…", "Downloading APK…"));});
                File dest=new File(activity.getCacheDir(),"tvOS-installer.apk");
                File tmp=new File(activity.getCacheDir(),"tvOS-installer.tmp.apk");
                HttpURLConnection c=connection(src.url);
                long length=c.getContentLengthLong(),got=0,last=-1;
                try {
                    if(length > MAX_APK_BYTES) throw new IOException(text("APK превышает допустимый размер", "APK exceeds size limit"));
                    try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                    byte[] buf=new byte[65536];int n;
                    while((n=in.read(buf))!=-1){
                        got+=n;
                        if(got > MAX_APK_BYTES) throw new IOException(text("APK превышает допустимый размер", "APK exceeds size limit"));
                        out.write(buf,0,n);
                        if(length>0){long pct=got*100/length;
                            if(pct>=last+5){last=pct;final long p=pct;
                                UI.post(()->{if(progress.isShowing())progress.setMessage(text("Загрузка APK: ", "Downloading APK: ")+p+"%");});}}
                    }
                    }
                    if(length >= 0 && got != length) throw new IOException(text("APK скачан не полностью", "Incomplete APK download"));
                } catch(Exception failure) {
                    tmp.delete();
                    throw failure;
                } finally { c.disconnect(); }
                if(tmp.length()<10000){
                    tmp.delete();
                    throw new IOException(text("Получен слишком маленький файл", "Downloaded file is too small"));
                }
                // Validate before publishing the file through ApkShareProvider.
                PackageInfo archive=pm.getPackageArchiveInfo(tmp.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
                if(archive==null||!PACKAGES[index].equals(archive.packageName)){
                    tmp.delete();
                    throw new IOException(text("APK содержит другое приложение", "Unexpected APK package"));
                }
                PackageInfo actual=null;
                try{actual=pm.getPackageInfo(PACKAGES[index],PackageManager.GET_SIGNING_CERTIFICATES);}
                catch(PackageManager.NameNotFoundException ignored){}
                if(archive.signingInfo==null || archive.signingInfo.getApkContentsSigners()==null
                        || archive.signingInfo.getApkContentsSigners().length==0
                        || (actual!=null && !sameSigners(actual,archive))){
                    tmp.delete();
                    throw new IOException(text("Не удалось подтвердить сертификат подписи APK", "APK signing certificate could not be verified"));
                }
                if(dest.exists()&&!dest.delete())throw new IOException(text("Не удалось заменить временный APK", "Could not replace temporary APK"));
                if(!tmp.renameTo(dest))throw new IOException(text("Не удалось сохранить APK", "Could not save APK"));
                if(actual!=null && actual.getLongVersionCode()>=archive.getLongVersionCode()){
                    UI.post(()->{progress.dismiss();BUSY.set(false);message(activity,name,text("Приложение уже установлено", "App is already installed"));});return;
                }
                UI.post(()->{
                    progress.dismiss();BUSY.set(false);
                    Uri uri=Uri.parse("content://io.tvos.apkshare/installer.apk");
                    Intent install=new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(uri,"application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
                    try{activity.startActivity(install);}catch(Exception e){message(activity,name,text("Не удалось открыть установщик: ", "Could not open installer: ")+e.getMessage());}
                });
            }catch(Exception e){UI.post(()->{progress.dismiss();BUSY.set(false);message(activity,name,text("Ошибка: ", "Error: ")+e.getMessage());});}
        },"tvOS-apk-download").start();
    }
    /** Exact signer-set match: deliberately reject key rotation until explicitly reviewed.
     * First installs still trust the configured HTTPS source; no publisher pins are bundled.
     */
    private static boolean sameSigners(PackageInfo installed, PackageInfo archive) {
        if(installed.signingInfo==null || archive.signingInfo==null) return false;
        android.content.pm.Signature[] a=installed.signingInfo.getApkContentsSigners();
        android.content.pm.Signature[] b=archive.signingInfo.getApkContentsSigners();
        if(a==null || b==null || a.length==0 || a.length!=b.length) return false;
        return new java.util.HashSet<>(java.util.Arrays.asList(a))
                .equals(new java.util.HashSet<>(java.util.Arrays.asList(b)));
    }
    private static boolean comparable(String a,String b){return numeric(a)!=null&&numeric(b)!=null;}
    private static int[] numeric(String s){
        if(s==null)return null;
        s=s.trim().replaceFirst("^[vV]","");
        if(!s.matches("[0-9]+(\\.[0-9]+){0,3}"))return null;
        String[] p=s.split("\\.");int[] v=new int[4];
        try{for(int i=0;i<p.length;i++)v[i]=Integer.parseInt(p[i]);}catch(NumberFormatException e){return null;}
        return v;
    }
    private static int compare(String a,String b){int[] x=numeric(a),y=numeric(b);for(int i=0;i<4;i++)if(x[i]!=y[i])return Integer.compare(x[i],y[i]);return 0;}
    private static void message(Activity a,String title,String msg){if(!a.isFinishing())new AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();}
    private static HttpURLConnection connection(String url)throws IOException{
        URL current=new URL(url);
        if(!"https".equals(current.getProtocol())) throw new IOException(text("Требуется HTTPS", "HTTPS required"));
        for(int i=0;i<8;i++){
            HttpURLConnection c=(HttpURLConnection)current.openConnection();c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent","tvOS-AndroidTV/1.0");
            c.setRequestProperty("Accept","application/octet-stream, application/vnd.github+json, */*");
            int code=c.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308){
                String next=c.getHeaderField("Location");c.disconnect();if(next==null)throw new IOException("Redirect without Location");
                current=new URL(current,next);if(!"https".equals(current.getProtocol()))throw new IOException(text("Небезопасная переадресация", "Insecure redirect"));continue;
            }
            if(code<200||code>=300){c.disconnect();throw new IOException("HTTP "+code);}return c;
        }throw new IOException(text("Слишком много переадресаций", "Too many redirects"));
    }
    private static String json(String url)throws Exception{
        HttpURLConnection c=connection(url);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))>0){out.write(b,0,n);if(out.size()>1500000)throw new IOException(text("Ответ слишком большой", "Response is too large"));}
            return out.toString("UTF-8");
        }finally{c.disconnect();}
    }
    private static Source release(String repo)throws Exception{
        JSONArray releases=new JSONArray(json("https://api.github.com/repos/"+repo+"/releases?per_page=12"));
        for(int r=0;r<releases.length();r++){
            JSONObject rel=releases.getJSONObject(r);if(rel.optBoolean("draft")||rel.optBoolean("prerelease"))continue;
            JSONArray assets=rel.optJSONArray("assets");if(assets==null)continue;
            String best=null;int bestScore=-1000;
            for(int i=0;i<assets.length();i++){
                JSONObject asset=assets.getJSONObject(i);String filename=asset.optString("name").toLowerCase(Locale.ROOT);
                if(!filename.endsWith(".apk"))continue;
                int score=0;
                if(filename.contains("arm64")||filename.contains("aarch64"))score+=5;
                if(filename.contains("armeabi")||filename.contains("armv7"))score+=3;
                if(filename.contains("universal"))score+=4;
                if(filename.contains("x86")||filename.contains("debug"))score-=20;
                if(score>bestScore){bestScore=score;best=asset.optString("browser_download_url");}
            }
            if(best!=null&&!best.isEmpty())return new Source(best,rel.optString("tag_name"));
        }throw new IOException(text("В релизах GitHub нет подходящего APK", "No suitable APK in GitHub releases"));
    }
    private static Source resolve(int i)throws Exception{
        switch(i){
            case 0:return release("jqssun/android-airplay-server");
            case 1:return new Source("https://github.com/JObersi10/apple-music-tv/releases/download/v1.2/AM-TV-1.2.apk","1.2");
            case 2:return release("reisxd/TizenTubeCobalt");
            case 3:return new Source("https://github.com/INCY-DEV/incy-platforms/releases/download/latest/Incy.apk",null);
            case 4:return new Source("https://seena.su/updates/Seena-1.9.0-armeabi-v7a.apk","1.9.0");
            case 5:return release("lampa-app/LAMPA");
            case 6:{
                JSONArray files=new JSONArray(json("https://api.github.com/repos/kotlinbestuser/apk/contents"));
                String best=null,version=null;
                for(int j=0;j<files.length();j++){
                    JSONObject f=files.getJSONObject(j);String n=f.optString("name").toLowerCase(Locale.ROOT);
                    if(n.startsWith("kino-watch")&&n.endsWith(".apk")&&(best==null||n.compareTo(best)>0)){
                        best=n;Matcher m=Pattern.compile("kino-watch-([0-9]+(?:\\.[0-9]+){0,3})\\.apk").matcher(n);version=m.matches()?m.group(1):null;
                    }
                }
                if(best==null)throw new IOException(text("KinoPub APK не найден", "KinoPub APK not found"));
                return new Source("https://raw.githubusercontent.com/kotlinbestuser/apk/HEAD/"+best,version);
            }
            default:throw new IOException(text("Неизвестное приложение", "Unknown app"));
        }
    }
}
