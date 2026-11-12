"""Execute the actual downloader worker/finish for verified downgrade preparation and ordinary installs."""
from pathlib import Path
import re,subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/AppDownloads.java').read_text()
methods=block(source,'        @Override public void run()')+block(source,'        private void finish(')+block(source,'    private static String tizenFile(')
keys=sorted(set(re.findall(r'R\.string\.(\w+)',methods)))
r='class R{static class string{'+''.join('static final int '+key+'='+str(n)+';' for n,key in enumerate(keys))+'}}'
harness='''import java.io.*;import java.lang.ref.*;import java.util.concurrent.atomic.*;
class Build{static final String[] SUPPORTED_ABIS={"armeabi-v7a"};}
class Activity{int installs;String uri;boolean isFinishing(){return false;}boolean isDestroyed(){return false;}void startActivity(Intent i){installs++;uri=i.uri;}}
class Application{void unregisterActivityLifecycleCallbacks(Object o){}}
class Context{File dir;Context(File d){dir=d;}File getCacheDir(){return dir;}Object getPackageManager(){return this;}}
class Uri{static String parse(String s){return s;}}
class Intent{static final String ACTION_VIEW="view";static final int FLAG_GRANT_READ_URI_PERMISSION=1;String uri;Intent(String a){}Intent setDataAndType(String u,String m){uri=u;return this;}Intent addFlags(int f){return this;}}
class PackageInfo{long code;PackageInfo(long c){code=c;}long getLongVersionCode(){return code;}}
class AppDownloadCatalog{static class Entry{String name="TizenTube",packageName="tizen";}static class Source{String sha256="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";}static int resolves;static Source resolve(Entry a,boolean b,String[] abis,java.util.function.BooleanSupplier cancel){resolves++;return new Source();}}
class ApkTrust{static PackageInfo installed=new PackageInfo(203),archive=new PackageInfo(106);static boolean fail,verified;static PackageInfo installed(Object m,String p){return installed;}static boolean reviewedVersionInstalled(AppDownloadCatalog.Entry a,PackageInfo i){return false;}static boolean requiresInstalledApp(AppDownloadCatalog.Entry a,AppDownloadCatalog.Source s){return false;}static PackageInfo verify(Object m,File f,AppDownloadCatalog.Entry a,PackageInfo i)throws IOException{if(fail)throw new IOException("signer rejected");verified=true;return archive;}static boolean alreadyInstalled(AppDownloadCatalog.Entry a,PackageInfo i,PackageInfo b){return i!=null&&i.code>=b.code;}}
class ApkTransfer{static void checkCancelled(java.util.function.BooleanSupplier cancel)throws IOException{if(cancel.getAsBoolean())throw new IOException();}static void download(AppDownloadCatalog.Source s,File f,java.util.function.BooleanSupplier cancel,java.util.function.IntConsumer progress)throws IOException{try(FileOutputStream out=new FileOutputStream(f)){out.write(1);}}}
class AppStrings{static String get(int id){return "message";}}
'''+r+'''
class Test implements Runnable{
 static class Ui{void post(Runnable r){r.run();}}static final Ui UI=new Ui();static final AtomicBoolean BUSY=new AtomicBoolean(true);
 final AtomicBoolean cancelled=new AtomicBoolean();final Application application=new Application();Context context;WeakReference<Activity> owner;
 final AppDownloadCatalog.Entry app=new AppDownloadCatalog.Entry();AppDownloadCatalog.Source selectedSource;Runnable prepared;boolean resumed=true;int messages;
 Test(File dir,Activity activity){context=new Context(dir);owner=new WeakReference<>(activity);selectedSource=new AppDownloadCatalog.Source();}
 void dismiss(){}void updateProgress(int percent){}void message(Activity a,String title,String result){messages++;}
'''+methods+'''
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){File dir=new File(args[0]);Activity owner=new Activity();final int[] ready={0};Test t=new Test(dir,owner);t.prepared=()->{check(ApkTrust.verified,"verify before any removal prompt");ready[0]++;};t.run();check(ready[0]==1&&owner.installs==0,"older APK is prepared without installing or deleting current app");check(new File(dir,tizenFile(t.selectedSource)).isFile(),"prepared APK isolated by digest");check(AppDownloadCatalog.resolves==0,"explicit selection never resolves a different latest version");
  ApkTrust.fail=true;ApkTrust.verified=false;t=new Test(dir,owner);t.prepared=()->ready[0]++;t.run();check(ready[0]==1&&t.messages==1&&owner.installs==0,"failed trust never asks to uninstall");
  ApkTrust.fail=false;ApkTrust.installed=new PackageInfo(100);ApkTrust.archive=new PackageInfo(203);t=new Test(dir,owner);t.selectedSource=null;t.run();check(owner.installs==1&&owner.uri.endsWith("/installer.apk")&&AppDownloadCatalog.resolves==1,"ordinary short press keeps latest installer flow");
  System.out.println("PASS: actual APK worker verifies before downgrade consent, preserves explicit release and retains latest-install flow");}
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);(root/'Test.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,str(root/'Test.java')],check=True)
 subprocess.run(['java','-cp',tmp,'Test',tmp],check=True)
