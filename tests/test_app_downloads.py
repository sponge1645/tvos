from settings_strings_fixture import write as write_strings
"""Production download, catalog and signer policy on JVM, with deterministic HTTPS/Android stubs."""
from pathlib import Path
import os
import subprocess
import tempfile

stubs = {
 'io/tvos/AppDownloads.java': '''package io.tvos;import io.tvos.version.AppVersions; final class AppDownloads {
 static String text(String ru,String en){return en;} }''',
 'android/os/Build.java': '''package android.os; public class Build {
 public static class VERSION {public static int SDK_INT=30;} }''',
 'android/content/pm/Signature.java': '''package android.content.pm; public class Signature {
 private final String value;public Signature(String s){value=s;}
 public byte[] toByteArray(){return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);} }''',
 'android/content/pm/SigningInfo.java': '''package android.content.pm; public class SigningInfo {
 public Signature[] signers;public Signature[] getApkContentsSigners(){return signers;} }''',
 'android/content/pm/ApplicationInfo.java': '''package android.content.pm; public class ApplicationInfo {
 public int minSdkVersion=28;}''',
 'android/content/pm/PackageInfo.java': '''package android.content.pm; public class PackageInfo {
 public String packageName="test.app",versionName="1.0";public long code=2;
 public Signature[] signatures;public SigningInfo signingInfo=new SigningInfo();public ApplicationInfo applicationInfo=new ApplicationInfo();
 public long getLongVersionCode(){return code;} }''',
 'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager {
 public static final int GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2;public PackageInfo archive,installed;
 public static class NameNotFoundException extends Exception {}
 public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{
 if(installed==null)throw new NameNotFoundException();return installed;}
 public PackageInfo getPackageArchiveInfo(String p,int f){
 if(f!=(GET_SIGNING_CERTIFICATES|GET_SIGNATURES))throw new AssertionError("both signing flags required");return archive;} }''',
 'org/json/JSONArray.java': '''package org.json; import java.util.*; public class JSONArray {
 public static List<JSONObject> next=new ArrayList<>();private final List<JSONObject> items;
 public JSONArray(String s){items=next;}public JSONArray(List<JSONObject> v){items=v;}
 public int length(){return items.size();}public JSONObject getJSONObject(int n){return items.get(n);} }''',
 'org/json/JSONObject.java': '''package org.json; import java.util.*; public class JSONObject {
 public static String version="1.9.3";public JSONObject(){}public JSONObject(String text){data.put("tag_name","1079");data.put("version",version);data.put("assets",new JSONArray(JSONArray.next));}public final Map<String,Object> data=new HashMap<>();public JSONObject put(String k,Object v){data.put(k,v);return this;}
 public String optString(String k){return data.containsKey(k)?data.get(k).toString():"";}
 public boolean optBoolean(String k){return Boolean.TRUE.equals(data.get(k));}
 public JSONArray optJSONArray(String k){return (JSONArray)data.get(k);} }'''
}

harness = r'''package io.tvos;import io.tvos.version.AppVersions;
import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;
import java.util.concurrent.atomic.*;import android.content.pm.*;

public class DownloadHarness {
 static final ArrayDeque<Response> replies=new ArrayDeque<>();
 static final List<Response> opened=new ArrayList<>();
 static class Response extends HttpURLConnection {
  byte[] bytes;long length;int status=200;String location;boolean closed;
  Response(byte[] bytes,long length)throws Exception{super(new URL("https://test.invalid/file"));this.bytes=bytes;this.length=length;}
  public void connect(){}public void disconnect(){closed=true;}public boolean usingProxy(){return false;}
  public int getResponseCode(){return status;}public long getContentLengthLong(){return length;}
  public String getHeaderField(String name){return location;}
  public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
 }
 interface Attempt {void run()throws Exception;}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void rejects(Attempt work,String why)throws Exception{
  boolean rejected=false;try{work.run();}catch(IOException expected){rejected=true;}
  check(rejected,why);
 }
 static Response reply(byte[] bytes,long size)throws Exception{
  Response r=new Response(bytes,size);replies.add(r);return r;
 }
 static PackageInfo signed(String key){PackageInfo p=new PackageInfo();p.signingInfo.signers=new Signature[]{new Signature(key)};return p;}
 static void githubAssets(String... names)throws Exception {
  java.util.List<org.json.JSONObject> assets=new java.util.ArrayList<>();
  for(String name:names)assets.add(new org.json.JSONObject().put("name",name).put("browser_download_url","https://test.invalid/v99/"+name).put("digest","sha256:"+"a".repeat(64)));
  org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("prerelease",true).put("assets",new org.json.JSONArray(assets)),new org.json.JSONObject().put("assets",new org.json.JSONArray(assets)));
  reply("[]".getBytes(),2);
 }
 public static void main(String[] args)throws Exception{
  URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)?new URLStreamHandler(){
   protected URLConnection openConnection(URL url)throws IOException{
    if(replies.isEmpty())throw new IOException("Unexpected connection: "+url);
    Response r=replies.remove();opened.add(r);return r;
   }}:null);
  check(AppVersions.compare("2.9","2.19")<0,"numeric versions, not filename lexical order");
  check(AppVersions.compare("v1.0","1")==0,"zero padding");
  for(String s:new String[]{"1.0-beta","latest","1.2.3.4.5","9999999999999",""})
   check(AppVersions.compare(s,"1")==null,"ambiguous/overflow version: "+s);
  String[] arm32={"armeabi-v7a"},arm64={"arm64-v8a","armeabi-v7a"},x64={"x86_64"};
  check(AppDownloadCatalog.assetScore("cobalt-arm64.apk",arm32)==Integer.MIN_VALUE,"32-bit TV rejects arm64");
  check(AppDownloadCatalog.assetScore("cobalt-arm.apk",arm64)>AppDownloadCatalog.assetScore("cobalt-arm64.apk",arm64),"supported arm32 preferred");
  check(AppDownloadCatalog.assetScore("debug.apk",arm64)==Integer.MIN_VALUE,"debug asset rejected");
  check(AppDownloadCatalog.assetScore("cobalt-arm.apk",x64)==Integer.MIN_VALUE,"Intel rejects ARM-specific APK");
  githubAssets("app-release.apk");
  AppDownloadCatalog.Source first=AppDownloadCatalog.resolve(AppDownloadCatalog.get(0),false,arm32,()->false);
  check(first.sha256!=null && first.url.contains("/v99/"),"first install discovers new stable release with digest");
  AppDownloadCatalog.Entry zona=AppDownloadCatalog.get(8);
  check("free.zona".equals(zona.packageName)&&zona.reviewedVersionCode==1078,"ZonaFree identity from reviewed APK");
  String zonaName="FreeZona_3.0.79_ultra_aligned.apk",zonaUrl="https://github.com/julay123/mobi.zona/releases/download/1079/"+zonaName;
  for(boolean installed:new boolean[]{false,true}){
   org.json.JSONArray.next=Arrays.asList(
    new org.json.JSONObject().put("name","FreeZona_3.0.79_RU_ultra_aligned.apk"),
    new org.json.JSONObject().put("name","FreeZona_3.0.79_World_ultra_aligned.apk"),
    new org.json.JSONObject().put("name",zonaName).put("browser_download_url",zonaUrl).put("digest","sha256:"+"c".repeat(64)));
   reply("{}".getBytes(),2);
   AppDownloadCatalog.Source selected=AppDownloadCatalog.resolve(zona,installed,arm32,()->false);
   check(selected.url.equals(zonaUrl)&&selected.sha256.equals("c".repeat(64)),"first install/update keeps base ZonaFree distribution");
  }
  for(String url:new String[]{"https://example.test/"+zonaName,zonaUrl.replace("1079/","1078/")}){
   org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("name",zonaName).put("browser_download_url",url).put("digest","sha256:"+"c".repeat(64)));
   reply("{}".getBytes(),2);rejects(()->AppDownloadCatalog.resolve(zona,true,arm32,()->false),"ZonaFree rejects foreign URL or wrong tag");
  }
  for(String digest:new String[]{"","invalid"}){
   org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("name",zonaName).put("browser_download_url",zonaUrl).put("digest",digest));
   reply("{}".getBytes(),2);rejects(()->AppDownloadCatalog.resolve(zona,true,arm32,()->false),"ZonaFree updates require verified digest");
  }
  org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("name","FreeZona_3.0.79_RU_ultra_aligned.apk"));
  reply("{}".getBytes(),2);rejects(()->AppDownloadCatalog.resolve(zona,false,arm32,()->false),"ZonaFree never substitutes another regional variant");
  rejects(()->AppDownloadCatalog.resolve(zona,false,arm32,()->true),"ZonaFree cancellation before request");
  AppDownloadCatalog.Entry happ=AppDownloadCatalog.get(4);
  check("su.happ.proxyutility".equals(happ.packageName),"HAPP row maps to expected package");
  String happUrl="https://github.com/Happ-proxy/happ-android/releases/download/99.0/Happ.apk";
  for(boolean installed:new boolean[]{false,true}) {
   org.json.JSONArray.next=Arrays.asList(
    new org.json.JSONObject().put("name","Happ_beta.apk").put("browser_download_url",happUrl.replace("Happ.apk","Happ_beta.apk")).put("digest","sha256:"+"b".repeat(64)),
    new org.json.JSONObject().put("name","Happ.apk").put("browser_download_url",happUrl).put("digest","sha256:"+"a".repeat(64)));
   reply("{}".getBytes(),2);
   AppDownloadCatalog.Source current=AppDownloadCatalog.resolve(happ,installed,arm32,()->false);
   check(happUrl.equals(current.url)&&"a".repeat(64).equals(current.sha256),"HAPP install/update selects exact stable APK with current hash");
  }
  for(String digest:new String[]{"","bad","sha256:"+"z".repeat(64)}) {
   org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("name","Happ.apk").put("browser_download_url",happUrl).put("digest",digest));
   reply("{}".getBytes(),2);
   rejects(()->AppDownloadCatalog.resolve(happ,false,arm64,()->false),"HAPP rejects absent/malformed hash");
  }
  org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("name","Happ.apk").put("browser_download_url","https://example.test/Happ.apk").put("digest","sha256:"+"a".repeat(64)));
  reply("{}".getBytes(),2);
  rejects(()->AppDownloadCatalog.resolve(happ,true,arm64,()->false),"HAPP rejects foreign asset URL");
  org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("name","Happ_beta.apk"));
  reply("{}".getBytes(),2);
  rejects(()->AppDownloadCatalog.resolve(happ,true,arm64,()->false),"HAPP never falls back to beta");
  rejects(()->AppDownloadCatalog.resolve(happ,false,arm64,()->true),"HAPP cancellation before metadata");
  AppDownloadCatalog.Entry kino=AppDownloadCatalog.get(7);
  PackageInfo oldKino=new PackageInfo(),newKino=new PackageInfo();oldKino.code=newKino.code=169;oldKino.versionName="2.19";newKino.versionName="2.23";
  check(!ApkTrust.alreadyInstalled(kino,oldKino,newKino),"KinoPub reused code accepts newer numeric name");
  check(ApkTrust.alreadyInstalled(kino,newKino,oldKino),"KinoPub reused code rejects older numeric name");
  check(ApkTrust.alreadyInstalled(kino,newKino,newKino),"KinoPub identical version skipped");
  check(ApkTrust.alreadyInstalled(AppDownloadCatalog.get(0),oldKino,newKino),"other apps retain version-code policy");
  newKino.code=168;check(ApkTrust.alreadyInstalled(kino,oldKino,newKino),"lower Android version code still rejected");newKino.code=169;
  newKino.versionName="unknown";check(ApkTrust.alreadyInstalled(kino,oldKino,newKino),"unknown version name cannot bypass equal-code check");

  org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("name","kino-watch-2.19.apk"),new org.json.JSONObject().put("name","kino-watch-2.23.apk"),new org.json.JSONObject().put("name","beta.apk"));
  reply("[]".getBytes(),2);
  AppDownloadCatalog.Source kinoFirst=AppDownloadCatalog.resolve(kino,false,arm32,()->false);
  check(kinoFirst.sha256==null && kino.signerSha256!=null && kinoFirst.url.endsWith("/kino-watch-2.23.apk"),"KinoPub first install discovers latest numeric release with pinned signer");
  check(!ApkTrust.requiresInstalledApp(kino,kinoFirst),"KinoPub rolling first install uses existing package signer pin");
  githubAssets("cobalt-arm.apk","cobalt-arm64.apk");
  rejects(()->AppDownloadCatalog.resolve(AppDownloadCatalog.get(2),false,x64,()->false),"Tizen bootstrap checks CPU");
  githubAssets("cobalt-arm.apk","cobalt-arm64.apk");
  check(AppDownloadCatalog.resolve(AppDownloadCatalog.get(2),false,arm64,()->false).url.endsWith("/cobalt-arm.apk"),"Tizen first install prefers ARM32 on dual-ABI system");
  githubAssets("cobalt-arm.apk","cobalt-arm64.apk");
  check(AppDownloadCatalog.resolve(AppDownloadCatalog.get(2),false,new String[]{"arm64-v8a"},()->false).url.endsWith("/cobalt-arm64.apk"),"Tizen ARM64-only fallback");
  check(AppDownloadCatalog.assetScore("app-x86.apk",new String[]{"x86_64","x86"})>AppDownloadCatalog.assetScore("app-x86_64.apk",new String[]{"x86_64","x86"}),"supported Intel32 preferred");
  org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("assets",new org.json.JSONArray(Arrays.asList(
    new org.json.JSONObject().put("name","cobalt-arm64.apk").put("browser_download_url","https://test.invalid/cobalt-arm64.apk"),
    new org.json.JSONObject().put("name","cobalt-arm.apk").put("browser_download_url","https://test.invalid/cobalt-arm.apk")))));
  reply("[]".getBytes(),2);
  check(AppDownloadCatalog.resolve(AppDownloadCatalog.get(2),true,arm64,()->false).url.endsWith("/cobalt-arm.apk"),"Tizen update also prefers ARM32");
  org.json.JSONArray.next=Arrays.asList(
   new org.json.JSONObject().put("name","kino-watch-2.9.apk"),
   new org.json.JSONObject().put("name","kino-watch-2.19.apk"),
   new org.json.JSONObject().put("name","kino-watch-9999999999999.apk"));
  reply("[]".getBytes(),2);
  check(AppDownloadCatalog.resolve(AppDownloadCatalog.get(7),true,arm32,()->false).url.endsWith("2.19.apk"),"KinoPub numeric selection");

  for(int index:new int[]{0,1,2,6}) {
   githubAssets("universal.apk");AppDownloadCatalog.Source fresh=AppDownloadCatalog.resolve(AppDownloadCatalog.get(index),false,arm32,()->false);
   githubAssets("universal.apk");AppDownloadCatalog.Source update=AppDownloadCatalog.resolve(AppDownloadCatalog.get(index),true,arm32,()->false);
   check(fresh.url.equals(update.url)&&fresh.sha256.equals(update.sha256),"first install and update share stable discovery for "+index);
  }
  org.json.JSONArray.next=Arrays.asList(new org.json.JSONObject().put("assets",new org.json.JSONArray(Arrays.asList(new org.json.JSONObject().put("name","universal.apk").put("browser_download_url","https://test.invalid/app.apk")))));reply("[]".getBytes(),2);
  rejects(()->AppDownloadCatalog.resolve(AppDownloadCatalog.get(0),false,arm32,()->false),"first installation without current release digest fails instead of silently using old APK");
  byte[] bytes=new byte[12000];Arrays.fill(bytes,(byte)7);
  String hash=ApkTransfer.hex(ApkTransfer.digest().digest(bytes));
  AppDownloadCatalog.Source src=new AppDownloadCatalog.Source("https://test.invalid/app.apk",hash);
  reply("metadata".getBytes(),8);
  check(AppDownloadCatalog.resolve(AppDownloadCatalog.get(5),false,arm32,()->false).url.endsWith("Seena-1.9.3-armeabi-v7a.apk"),"Seena first install resolves current official release");
  org.json.JSONObject.version="../../bad";reply("metadata".getBytes(),8);
  check(AppDownloadCatalog.resolve(AppDownloadCatalog.get(5),true,arm32,()->false).url.equals("https://seena.su/d.apk"),"invalid release metadata uses official stable endpoint");
  org.json.JSONObject.version="1.9.3";
  check(!ApkTrust.requiresInstalledApp(AppDownloadCatalog.get(5),new AppDownloadCatalog.Source("https://seena.su/d.apk",null)),"Seena rolling first install authenticated by signer pin");
  check(ApkTrust.requiresInstalledApp(new AppDownloadCatalog.Entry("Mirror","test.app",null,null,null,false,0),new AppDownloadCatalog.Source("https://example.test/update.apk",null)),"unpinned mirror update still requires installed app");
  AppDownloadCatalog.Entry incy=AppDownloadCatalog.get(3);
  org.json.JSONObject asset=new org.json.JSONObject().put("name","Incy.apk").put("browser_download_url",incy.bootstrap.url).put("digest","sha256:"+hash);
  org.json.JSONArray.next=Arrays.asList(asset);
  for(boolean installed:new boolean[]{false,true}){
   reply("metadata".getBytes(),8);
   AppDownloadCatalog.Source current=AppDownloadCatalog.resolve(incy,installed,arm32,()->false);
   check(hash.equals(current.sha256)&&current.url.equals(incy.bootstrap.url),"INCY current release hash for install and update");
  }
  String nextHash=String.join("",Collections.nCopies(64,"a"));asset.put("digest","sha256:"+nextHash);reply("metadata".getBytes(),8);
  check(nextHash.equals(AppDownloadCatalog.resolve(incy,false,arm32,()->false).sha256),"INCY follows replacement digest without catalog update");
  for(String bad:new String[]{"","sha256:bad","sha512:"+hash}){
   asset.put("digest",bad);reply("metadata".getBytes(),8);
   rejects(()->AppDownloadCatalog.resolve(incy,false,arm32,()->false),"INCY missing/malformed digest fails closed");
  }
  asset.put("digest","sha256:"+hash).put("browser_download_url","https://test.invalid/Incy.apk");reply("metadata".getBytes(),8);
  rejects(()->AppDownloadCatalog.resolve(incy,true,arm32,()->false),"INCY foreign URL rejected");
  org.json.JSONArray.next=Collections.emptyList();reply("metadata".getBytes(),8);
  rejects(()->AppDownloadCatalog.resolve(incy,false,arm32,()->false),"INCY missing APK rejected");
  rejects(()->AppDownloadCatalog.resolve(incy,false,arm32,()->true),"INCY cancellation before metadata");
  File tmp=Files.createTempDirectory("app-transfer-").resolve("part.apk").toFile();
  Response ok=reply(bytes,bytes.length);
  ApkTransfer.download(src,tmp,()->false,p->{});
  check(ok.closed && Arrays.equals(bytes,Files.readAllBytes(tmp.toPath())),"verified streaming download");tmp.delete();
  reply(bytes,bytes.length+1);rejects(()->ApkTransfer.download(src,tmp,()->false,p->{}),"truncated response rejected");
  check(!tmp.exists(),"truncated partial cleaned");
  reply(bytes,bytes.length);rejects(()->ApkTransfer.download(new AppDownloadCatalog.Source(src.url,String.join("",Collections.nCopies(64,"0"))),tmp,()->false,p->{}),"tampered hash rejected");
  check(!tmp.exists(),"hash failure cleaned");
  Response huge=reply(bytes,ApkTransfer.MAX_APK_BYTES+1);
  rejects(()->ApkTransfer.download(src,tmp,()->false,p->{}),"oversize header rejected");check(huge.closed,"oversize disconnects");
  reply(bytes,bytes.length);AtomicBoolean cancel=new AtomicBoolean();
  rejects(()->ApkTransfer.download(src,tmp,cancel::get,p->cancel.set(true)),"cancel before publication");
  check(!tmp.exists(),"cancel partial cleaned");
  Response redirect=reply(new byte[0],0);redirect.status=302;redirect.location="http://unsafe.invalid/file";
  rejects(()->ApkTransfer.download(src,tmp,()->false,p->{}),"HTTPS downgrade rejected");check(redirect.closed,"redirect disconnected");
  for(int i=0;i<8;i++){Response loop=reply(new byte[0],0);loop.status=302;loop.location="/again";}
  rejects(()->ApkTransfer.connection(src.url,()->false),"redirect loop bounded");
  byte[] metadata=new byte[1_500_001];reply(metadata,metadata.length);
  rejects(()->ApkTransfer.json(src.url,()->false),"metadata memory bounded");

  PackageManager pm=new PackageManager();pm.archive=signed("publisher");
  String signer=ApkTransfer.hex(ApkTransfer.digest().digest("publisher".getBytes()));
  AppDownloadCatalog.Entry app=new AppDownloadCatalog.Entry("Test","test.app",signer,src,null,false,2);
  ApkTrust.verify(pm,tmp,app,null);
  pm.archive.signatures=new Signature[]{new Signature("publisher")};pm.archive.signingInfo=null;
  ApkTrust.verify(pm,tmp,app,null);
  pm.archive.signingInfo=new SigningInfo();
  ApkTrust.verify(pm,tmp,app,null);
  pm.archive.signingInfo.signers=new Signature[0];
  ApkTrust.verify(pm,tmp,app,null);
  pm.archive.signingInfo=null;
  PackageInfo legacy=signed("publisher");legacy.signatures=new Signature[]{new Signature("publisher")};legacy.signingInfo=null;
  ApkTrust.verify(pm,tmp,app,legacy);
  pm.archive.signatures=new Signature[]{new Signature("attacker")};
  rejects(()->ApkTrust.verify(pm,tmp,app,null),"legacy signer mismatch rejected");
  pm.archive.signatures=null;rejects(()->ApkTrust.verify(pm,tmp,app,null),"missing certificates rejected");
  pm.archive=signed("attacker");pm.archive.signatures=new Signature[]{new Signature("publisher")};
  rejects(()->ApkTrust.verify(pm,tmp,app,null),"legacy data cannot override current signer set");
  pm.archive=signed("attacker");rejects(()->ApkTrust.verify(pm,tmp,app,null),"first install rejects unexpected signer");
  pm.archive=signed("publisher");pm.archive.packageName="other.app";rejects(()->ApkTrust.verify(pm,tmp,app,null),"package substitution rejected");
  pm.archive=signed("publisher");pm.archive.applicationInfo.minSdkVersion=99;rejects(()->ApkTrust.verify(pm,tmp,app,null),"unsupported SDK rejected");
  pm.archive=signed("publisher");pm.archive.signingInfo.signers=new Signature[]{new Signature("publisher"),new Signature("attacker")};
  rejects(()->ApkTrust.verify(pm,tmp,app,null),"extra signer rejected");
  pm.archive=signed("publisher");final PackageInfo different=signed("different");
  rejects(()->ApkTrust.verify(pm,tmp,app,different),"installed signer anchor enforced");
  PackageInfo installed=signed("publisher");ApkTrust.verify(pm,tmp,app,installed);
  AppDownloadCatalog.Entry fixed=new AppDownloadCatalog.Entry("Fixed","test.app",signer,src,null,true,2);
  check(ApkTrust.reviewedVersionInstalled(fixed,installed),"fixed verified version skips redundant download");
  check(!ApkTrust.reviewedVersionInstalled(fixed,different),"foreign installed signer cannot bypass download verification");
  AppDownloadCatalog.Entry mirror=new AppDownloadCatalog.Entry("Mirror","test.app",null,null,null,false,0);
  rejects(()->ApkTrust.verify(pm,tmp,mirror,null),"mirror first install rejected even if resolver bypassed");
  ApkTrust.verify(pm,tmp,mirror,installed);
  pm.archive=signed("attacker");final PackageInfo anchor=installed;
  rejects(()->ApkTrust.verify(pm,tmp,mirror,anchor),"mirror update rejects changed signer");
  check(ApkTrust.installed(pm,"missing")==null,"missing installation represented explicitly");
  for(Response r:opened)check(r.closed,"all HTTP connections closed");
  System.out.println("PASS: APK hashes, signer pins, mirror policy, streaming limits/cancellation, redirects, ABI and versions");
 }
}
'''

with tempfile.TemporaryDirectory(prefix='tvos-app-downloads-') as directory:
    root = Path(directory)
    write_strings(root)
    for name, content in stubs.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    for name in ['AppDownloadCatalog', 'ApkTransfer', 'ApkTrust', 'AppVersions']:
        (root / 'io/tvos' / (name + '.java')).write_text(Path('app/src/io/tvos/version' if name == 'AppVersions' else 'app/src/io/tvos', name + '.java').read_text())
    (root / 'io/tvos/DownloadHarness.java').write_text(harness)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory,
                    *map(str, root.rglob('*.java'))], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.DownloadHarness'], check=True)
