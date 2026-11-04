"""Disk LRU byte/count limits, recent hits and abandoned partial cleanup use real files."""
from pathlib import Path
import subprocess,tempfile
with tempfile.TemporaryDirectory() as d:
 root=Path(d);(root/'ArtworkDiskCache.java').write_text(Path('app/src/io/tvos/artwork/ArtworkDiskCache.java').read_text())
 (root/'Harness.java').write_text('''import io.tvos.artwork.ArtworkDiskCache;import java.io.*;
 class Harness {
 static File file(File dir,String name,long bytes,long time)throws Exception{File f=new File(dir,name);try(RandomAccessFile r=new RandomAccessFile(f,"rw")){r.setLength(bytes);}f.setLastModified(time);return f;}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception{File dir=new File(args[0]);dir.mkdirs();long now=System.currentTimeMillis();
 File old=file(dir,"old",40*1024*1024L,1),recent=file(dir,"recent",40*1024*1024L,now),next=file(dir,"next",40*1024*1024L,now-1);
 File stale=file(dir,"crash.part",1,1),active=file(dir,"active.part",1,now);
 ArtworkDiskCache.trim(dir,96L*1024*1024,96);check(!old.exists()&&recent.exists()&&next.exists()&&!stale.exists()&&active.exists());
 ArtworkDiskCache.trim(dir,96L*1024*1024,1);check(recent.exists()&&!next.exists()&&active.exists());
 System.out.println("PASS: compressed artwork obeys byte/count caps, keeps recent hits and never deletes active partials");
 }}''')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'Harness',str(root/'cache')],check=True)
