"""Production release/APK policy on JVM with deterministic package-manager/JSON stubs."""
from pathlib import Path
import os
import subprocess
import tempfile

source = Path('app/src/io/tvos/SelfUpdate.java').read_text()
def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, cursor = 1, brace + 1
    while depth:
        depth += (source[cursor] == '{') - (source[cursor] == '}')
        cursor += 1
    return source[start:cursor]

header = r'''package io.tvos;
import java.util.*;import java.io.*;
public class UpdatePolicyHarness {
 static class Signature {String value;Signature(String v){value=v;}public boolean equals(Object o){return o instanceof Signature && value.equals(((Signature)o).value);}public int hashCode(){return value.hashCode();}}
 static class SigningInfo {Signature[] signers,history;boolean fail;boolean hasMultipleSigners(){return signers!=null&&signers.length>1;}Signature[] getSigningCertificateHistory(){return history!=null?history:signers;}Signature[] getApkContentsSigners(){if(fail)throw new UnsupportedOperationException();return signers;}}
 static class PackageInfo {String packageName="io.tvos",versionName="1.2";long code=3;Signature[] signatures;SigningInfo signingInfo=new SigningInfo();long getLongVersionCode(){return code;}}
 static class PackageManager {
  static final int GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2;
  static class NameNotFoundException extends Exception {}
  PackageInfo installed,archive;boolean missingSignedArchive,failSigning;
  void flags(int f){if(f!=0 && f!=(GET_SIGNING_CERTIFICATES|GET_SIGNATURES))throw new AssertionError("unexpected flags");}
  PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{flags(f);if(f!=0&&failSigning)throw new NameNotFoundException();return installed;}
  PackageInfo getPackageArchiveInfo(String p,int f){flags(f);if(f!=0&&failSigning)throw new SecurityException();return f!=0&&missingSignedArchive?null:archive;}
 }
 static class Context {PackageManager pm=new PackageManager();PackageManager getPackageManager(){return pm;}String getPackageName(){return "io.tvos";}}
 Context context=new Context();static String text(String r,String e){return e;}
 static Map<String,Object> manifest(){Map<String,Object> m=new HashMap<>();m.put("packageName","io.tvos");m.put("versionName","1.2");m.put("versionCode",3L);m.put("tag","v1.2");m.put("asset","tvOS.apk");m.put("sha256",String.join("",Collections.nCopies(64,"a")));m.put("size",20000L);return m;}
 static UpdateRelease release(Map<String,Object> m)throws Exception{return UpdateRelease.parse(new org.json.JSONObject(m));}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 void rejects(UpdateRelease r)throws Exception {try{verifyApk(new File("test.apk"),r);}catch(IOException expected){return;}throw new AssertionError("unsafe APK accepted");}
'''
tests = r'''
 public static void main(String[] args)throws Exception {
  UpdateRelease r=release(manifest());check(r.apkUrl().equals("https://github.com/sponge1645/tvos/releases/download/v1.2/tvOS.apk"),"pinned tag URL");
  Object[][] invalid={{"packageName","other.app"},{"asset","evil.apk"},{"tag","../bad"},{"tag","v1/evil"},{"tag",".."},{"sha256","abc"},{"versionCode",0L},{"versionCode",2100000001L},{"size",9999L},{"size",268435457L},{"versionName","x/y"}};
  check(UpdateRelease.LATEST.endsWith("/releases/download/ota-v1/update.json"),"bridge uses separate channel");
  for(Object[] bad:invalid){Map<String,Object> m=manifest();m.put((String)bad[0],bad[1]);boolean failed=false;try{release(m);}catch(Exception expected){failed=true;}check(failed,"unsafe manifest accepted: "+bad[0]);}
  UpdatePolicyHarness h=new UpdatePolicyHarness();PackageInfo installed=new PackageInfo(),archive=new PackageInfo();installed.code=2;
  installed.signingInfo.signers=new Signature[]{new Signature("owner")};archive.signingInfo.signers=new Signature[]{new Signature("owner")};h.context.pm.installed=installed;h.context.pm.archive=archive;
  h.verifyApk(new File("test.apk"),r);
  archive.signingInfo.signers=new Signature[]{new Signature("attacker")};h.rejects(r);
  archive.signingInfo.signers=new Signature[]{new Signature("owner")};archive.packageName="other.app";h.rejects(r);archive.packageName="io.tvos";
  archive.code=2;h.rejects(r);archive.code=4;h.rejects(r);archive.code=3;
  archive.versionName="wrong";h.rejects(r);archive.versionName="1.2";
  archive.signingInfo.signers=new Signature[0];h.verifyApk(new File("test.apk"),r);
  archive.signingInfo=null;h.verifyApk(new File("test.apk"),r);
  archive.signatures=new Signature[]{new Signature("owner")};h.verifyApk(new File("test.apk"),r);
  installed.signingInfo=null;installed.signatures=new Signature[]{new Signature("owner")};h.verifyApk(new File("test.apk"),r);
  archive.signatures=new Signature[]{new Signature("attacker")};h.rejects(r);
  archive.signatures=new Signature[]{new Signature("owner"),new Signature("attacker")};h.rejects(r);
  archive.signatures=new Signature[]{null};h.verifyApk(new File("test.apk"),r);
  archive.signatures=new Signature[]{new Signature("owner")};archive.signingInfo=new SigningInfo();
  archive.signingInfo.signers=new Signature[0];h.verifyApk(new File("test.apk"),r);
  archive.signingInfo.signers=new Signature[]{new Signature("attacker")};h.rejects(r);
  archive.signingInfo.signers=null;archive.signatures=null;
  h.verifyApk(new File("test.apk"),r);
  h.context.pm.missingSignedArchive=true;h.verifyApk(new File("test.apk"),r);
  h.context.pm.missingSignedArchive=false;h.context.pm.failSigning=true;h.verifyApk(new File("test.apk"),r);
  archive.packageName="other.app";h.rejects(r);archive.packageName="io.tvos";
  archive.code=4;h.rejects(r);archive.code=3;
  h.context.pm.failSigning=false;
  archive.signingInfo.fail=true;h.verifyApk(new File("test.apk"),r);archive.signingInfo.fail=false;
  installed.signingInfo=new SigningInfo();installed.signingInfo.signers=new Signature[]{new Signature("owner")};installed.signatures=null;
  archive.signingInfo=new SigningInfo();archive.signingInfo.signers=new Signature[]{new Signature("new")};archive.signatures=null;
  archive.signingInfo.history=new Signature[]{new Signature("owner"),new Signature("new")};h.verifyApk(new File("test.apk"),r);
  installed.signatures=installed.signingInfo.signers;SigningInfo keep=installed.signingInfo;installed.signingInfo=null;h.verifyApk(new File("test.apk"),r);installed.signingInfo=keep;installed.signatures=null;
  archive.signingInfo.history=new Signature[]{new Signature("stranger"),new Signature("new")};h.rejects(r);
  archive.signingInfo.history=new Signature[]{new Signature("owner"),new Signature("wrong")};h.rejects(r);
  archive.signingInfo.history=new Signature[]{new Signature("owner"),new Signature("owner"),new Signature("new")};h.rejects(r);
  installed.signingInfo.signers=new Signature[]{new Signature("new")};installed.signingInfo.history=new Signature[]{new Signature("owner"),new Signature("new")};
  archive.signingInfo.signers=new Signature[]{new Signature("owner")};archive.signingInfo.history=new Signature[]{new Signature("owner")};h.rejects(r);
  archive.signingInfo.signers=new Signature[]{new Signature("third")};archive.signingInfo.history=new Signature[]{new Signature("owner"),new Signature("new"),new Signature("third")};h.verifyApk(new File("test.apk"),r);
  archive.signingInfo.history=new Signature[]{new Signature("new"),new Signature("third")};h.rejects(r);
  archive.signingInfo.history=null;h.rejects(r);
  h.context.pm.archive=null;
  try{h.verifyApk(new File("test.apk"),r);throw new AssertionError("missing metadata accepted");}
  catch(IOException expected){check(expected.getMessage().contains("metadata"),"parse error is distinct from signer mismatch");}
  check(sameRelease(r,release(manifest())),"same signed release metadata can reuse ready APK");
  for(Object[] changed:new Object[][]{{"versionCode",4L},{"versionName","1.3"},{"tag","v1.3"},{"size",20001L},{"sha256",String.join("",Collections.nCopies(64,"b"))}}){
   Map<String,Object> m=manifest();m.put((String)changed[0],changed[1]);
   check(!sameRelease(r,release(m)),"changed release cannot reuse stale ready APK: "+changed[0]);
  }
  check(!sameRelease(null,r),"no cached release is not reusable");
  check(hex(new byte[]{0,15,(byte)255}).equals("000fff"),"digest encoding");
  System.out.println("PASS: manifest validation, pinned URL, package/version/signature rejection, platform signer fallback, ready release identity, distinct errors, digest encoding");
 }
}
'''
json_stub = '''package org.json;import java.util.Map;public class JSONObject {private Map<String,Object> map;public JSONObject(Map<String,Object> map){this.map=map;}public String getString(String key){return (String)map.get(key);}public long getLong(String key){return ((Number)map.get(key)).longValue();}}'''
with tempfile.TemporaryDirectory(prefix='tvos-update-policy-') as directory:
    root = Path(directory)
    (root / 'JSONObject.java').write_text(json_stub)
    (root / 'UpdatePolicyHarness.java').write_text(header + ' private static final int SIGNER_FLAGS=PackageManager.GET_SIGNING_CERTIFICATES|PackageManager.GET_SIGNATURES;\n' + method('    private void verifyApk(') + method('    private static Signature[] currentSigners(') + method('    private static boolean compatibleSigners(') + method('    private static Signature[] signingHistory(') + method('    private static boolean sameRelease(') + method('    private static String hex(') + tests)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory, 'app/src/io/tvos/UpdateRelease.java', str(root / 'JSONObject.java'), str(root / 'UpdatePolicyHarness.java')], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.UpdatePolicyHarness'], check=True)
