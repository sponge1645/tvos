"""A blocked recommendation request must not delay bundled settings/YouTube artwork."""
from pathlib import Path
import subprocess, tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/DockBackdrop.java').read_text()
methods='\n'.join(block(source,s) for s in ['    private static ExecutorService queue(', '    static String bundled('])
code='''import java.util.concurrent.*;
class Test {
 static final ExecutorService IO=Executors.newSingleThreadExecutor(),LOCAL=Executors.newSingleThreadExecutor();
'''+methods+'''
 public static void main(String[] args)throws Exception {
  CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),ready=new CountDownLatch(2);
  try {
   queue("watch.kino").execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
   if(!blocked.await(2,TimeUnit.SECONDS))throw new AssertionError("network queue did not start");
   queue("io.tvos").execute(ready::countDown);
   queue("com.google.android.youtube.tv").execute(ready::countDown);
   if(!ready.await(2,TimeUnit.SECONDS))throw new AssertionError("local banners blocked by recommendation IO");
   if(queue("io.gh.reisxd.tizentube.cobalt")!=LOCAL||queue("com.seena.app")!=IO)throw new AssertionError("wrong routing");
  } finally {release.countDown();IO.shutdownNow();LOCAL.shutdownNow();}
  System.out.println("PASS: bundled banners remain responsive while recommendation IO is blocked");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'Test.java';p.write_text(code)
 subprocess.run(['javac','--release','8','-Xlint:all','-Werror','-d',d,str(p)],check=True)
 subprocess.run(['java','-cp',d,'Test'],check=True)
