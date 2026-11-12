"""The actual provider exposes selected APKs by exact digest; arbitrary cache paths remain inaccessible."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
method=block(Path('app/src/io/tvos/ApkShareProvider.java').read_text(),'    private File file(')
harness='''import java.io.*;class Uri{String path;Uri(String p){path=p;}String getPath(){return path;}}class Test{File dir;Test(File d){dir=d;}Test getContext(){return this;}File getCacheDir(){return dir;}
'''+method+'''
 public static void main(String[] args)throws Exception{Test t=new Test(new File(args[0]));String hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";File apk=new File(t.dir,"tizen-"+hash+".apk");if(!apk.createNewFile())throw new AssertionError();if(!t.file(new Uri("/tizen-"+hash+".apk")).equals(apk))throw new AssertionError();for(String path:new String[]{"/../config.properties","/tizen-bad.apk","/tizen-"+hash+".apk/extra","/settings.xml"})try{t.file(new Uri(path));throw new AssertionError("arbitrary path allowed");}catch(FileNotFoundException expected){}System.out.println("PASS: actual selected APK provider digest allowlist and path traversal protection");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);(root/'Test.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,str(root/'Test.java')],check=True)
 subprocess.run(['java','-cp',tmp,'Test',tmp],check=True)
