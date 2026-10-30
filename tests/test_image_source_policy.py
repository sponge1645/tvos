"""Actual publisher URI policy and Android authority resolution adapter."""
from pathlib import Path
import subprocess,tempfile
files={
'io/tvos/artwork/ImageSourcePolicy.java':Path('app/src/io/tvos/artwork/ImageSourcePolicy.java').read_text(),
'io/tvos/ProviderImages.java':Path('app/src/io/tvos/ProviderImages.java').read_text(),
'android/content/Context.java':'package android.content;public class Context {public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}',
'android/content/pm/ProviderInfo.java':'package android.content.pm;public class ProviderInfo {public String packageName;}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager {public ProviderInfo resolveContentProvider(String authority,int flags){if(authority.equals("missing"))return null;ProviderInfo p=new ProviderInfo();p.packageName=authority.equals("unrelated.authority")||authority.equals("owner_images")?"media.owner":"foreign.app";return p;}}',
'io/tvos/PolicyHarness.java':'''package io.tvos;public class PolicyHarness {static void check(boolean b){if(!b)throw new AssertionError();}public static void main(String[] args){android.content.Context c=new android.content.Context();check(ProviderImages.allowed(c,"content://unrelated.authority/image/1","media.owner"));check(ProviderImages.allowed(c,"content://owner_images/image/1","media.owner"));check(!ProviderImages.allowed(c,"content://media.owner/image/1","media.owner"));check(!ProviderImages.allowed(c,"content://missing/image/1","media.owner"));check(ProviderImages.allowed(c,"android.resource://media.owner/123","media.owner"));check(!ProviderImages.allowed(c,"android.resource://foreign.app/123","media.owner"));check(ProviderImages.allowed(c,"https://cdn.example/poster.jpg","media.owner"));for(String uri:new String[]{"file:///data/private","http://cdn.example/a","content://user@unrelated.authority/a","https://user:password@cdn.example/a","garbage","//cdn.example/a"})check(!ProviderImages.allowed(c,uri,"media.owner"));System.out.println("PASS: image authorities resolved to publisher packages; foreign providers/resources and unsupported schemes rejected");}}'''}
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 for name,code in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.PolicyHarness'],check=True,timeout=10)
