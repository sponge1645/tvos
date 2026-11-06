package io.tvos.icloud;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Public Shared Albums only. No Apple account, cookies, arbitrary endpoints or URL logging. */
public final class ICloudAlbum {
    private ICloudAlbum() { }
    public static final class Photo {
        public final String id, url;
        Photo(String id, String url) { this.id = id; this.url = url; }
    }
    public static String token(String link) throws IOException {
        try {
            URI uri = new URI(link.trim());
            String token = uri.getRawFragment();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"www.icloud.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null
                    || !"/sharedalbum/".equals(uri.getPath()) || token == null
                    || !token.matches("[ABCD][0-9A-Za-z]{8,180}")) throw new IOException("Not a public iCloud album");
            return token;
        } catch (java.net.URISyntaxException error) { throw new IOException("Invalid album link"); }
    }
    public static String key(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String host(String token) {
        String alphabet="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        int partition=0, end=token.charAt(0)=='A'?2:3;
        for(int i=1;i<end;i++)partition=partition*62+alphabet.indexOf(token.charAt(i));
        return String.format(Locale.ROOT,"p%02d-sharedstreams.icloud.com",partition);
    }
    private static boolean apiHost(String host) { return host != null && host.matches("p[0-9]{2,4}-sharedstreams\\.icloud\\.com"); }
    public static boolean imageUrl(String value) {
        try { URI u=new URI(value);String h=u.getHost();return "https".equals(u.getScheme())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&h!=null&&h.endsWith(".icloud-content.com"); }
        catch(Exception invalid){return false;}
    }
    public interface Transport { JSONObject post(String host, String token, String path, JSONObject body) throws Exception; }
    public static List<Photo> photos(String link,int limit) throws Exception { return photos(link,limit,ICloudAlbum::post); }
    public static List<Photo> photos(String link,int limit,Transport transport) throws Exception {
        String token=token(link),host=host(token);
        JSONObject stream=transport.post(host,token,"webstream",new JSONObject());
        JSONArray raw=stream.optJSONArray("photos");
        List<JSONObject> eligible=new ArrayList<>();
        if(raw!=null)for(int i=0;i<Math.min(raw.length(),5000);i++) {
            JSONObject p=raw.optJSONObject(i);if(p==null)continue;
            JSONObject derivatives=p.optJSONObject("derivatives");
            if(derivatives==null||derivatives.has("720p")||derivatives.has("360p")||"video".equalsIgnoreCase(p.optString("mediaAssetType","")))continue;
            if(!p.optString("photoGuid","").isEmpty())eligible.add(p);
        }
        Collections.shuffle(eligible);
        if(eligible.size()>Math.max(1,Math.min(limit,12)))eligible.subList(Math.max(1,Math.min(limit,12)),eligible.size()).clear();
        JSONArray guids=new JSONArray();for(JSONObject p:eligible)guids.put(p.getString("photoGuid"));
        if(eligible.isEmpty())throw new IOException("Album has no usable photos");
        JSONObject assets=transport.post(host,token,"webasseturls",new JSONObject().put("photoGuids",guids));
        JSONObject items=assets.optJSONObject("items"),locations=assets.optJSONObject("locations");
        List<Photo> result=new ArrayList<>();
        if(items!=null&&locations!=null)for(JSONObject p:eligible) {
            JSONObject ds=p.getJSONObject("derivatives"),best=null;long area=-1;
            for(Iterator<String> it=ds.keys();it.hasNext();) {
                JSONObject d=ds.optJSONObject(it.next());if(d==null)continue;
                long size=(long)d.optInt("width",0)*d.optInt("height",0);
                if(size>area&&items.optJSONObject(d.optString("checksum",""))!=null){area=size;best=d;}
            }
            if(best==null)continue;
            JSONObject item=items.getJSONObject(best.getString("checksum"));
            JSONObject location=locations.optJSONObject(item.optString("url_location",""));
            if(location==null||!"https".equals(location.optString("scheme","")))continue;
            JSONArray hosts=location.optJSONArray("hosts");if(hosts==null||hosts.length()==0)continue;
            String url="https://"+hosts.getString(0)+item.optString("url_path","");
            if(imageUrl(url))result.add(new Photo(key(p.getString("photoGuid")),url));
        }
        if(result.isEmpty())throw new IOException("No photo URLs");
        return result;
    }
    private static JSONObject post(String host,String token,String path,JSONObject body) throws Exception {
        for(int attempt=0;attempt<2;attempt++) {
            if(!apiHost(host))throw new IOException("Invalid iCloud endpoint");
            HttpURLConnection c=(HttpURLConnection)new URL("https://"+host+"/"+token+"/sharedstreams/"+path).openConnection();
            c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Origin","https://www.icloud.com");
            try {
                try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
                int status=c.getResponseCode();
                InputStream in=status==330?c.getErrorStream():c.getInputStream();
                if(in==null&&status==330)in=c.getInputStream();
                JSONObject result=new JSONObject(read(in,5*1024*1024));
                if(status==330){host=result.optString("X-Apple-MMe-Host","");continue;}
                if(status!=200)throw new IOException("iCloud unavailable");
                return result;
            } finally {c.disconnect();}
        }
        throw new IOException("iCloud endpoint redirect");
    }
    private static String read(InputStream input,int max) throws IOException {
        if(input==null)throw new IOException("Empty response");
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted()||out.size()+n>max)throw new IOException("Response limit");out.write(b,0,n);}return out.toString("UTF-8");
        }
    }
    public static void download(String url,File target) throws IOException {
        for(int attempt=0;attempt<4;attempt++) {
            if(!imageUrl(url))throw new IOException("Invalid photo host");
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);
            try {
                int status=c.getResponseCode();
                if(status>=300&&status<400){url=new URL(new URL(url),c.getHeaderField("Location")).toString();continue;}
                if(status!=200)throw new IOException("Photo unavailable");
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){
                    byte[] b=new byte[16384];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>12*1024*1024||Thread.currentThread().isInterrupted())throw new IOException("Photo limit");out.write(b,0,n);}if(total<1000)throw new IOException("Empty photo");
                }return;
            }finally{c.disconnect();}
        }throw new IOException("Photo redirects");
    }
}
