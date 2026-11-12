"""Group navigation keeps original catalog indices and reaches every app once."""
from pathlib import Path
import subprocess,tempfile
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'io/tvos';p.mkdir(parents=True)
 (p/'DownloadGroups.java').write_text(Path('app/src/io/tvos/DownloadGroups.java').read_text())
 (p/'Test.java').write_text('''package io.tvos;
class Test { public static void main(String[] args) {
 int[] expected={0,3,4,1,2,5,6,7,8};int focus=0;
 for(int n=0;n<expected.length;n++){
  if(focus!=expected[n])throw new AssertionError("group ordering");
  if(n>0&&DownloadGroups.position(focus)<=DownloadGroups.position(expected[n-1]))throw new AssertionError("row overlap");
  focus=DownloadGroups.move(focus,1);
 }
 if(focus!=8||DownloadGroups.move(0,-1)!=0)throw new AssertionError("edge bounds");
 for(int n=expected.length-1;n>=0;n--){if(focus!=expected[n])throw new AssertionError("reverse navigation");focus=DownloadGroups.move(focus,-1);}
 System.out.println("PASS: grouped downloads preserve catalog/action identity and bidirectional navigation");
}}''')
 subprocess.run(['javac','--release','8','-Xlint:all','-Werror','-d',d,*map(str,p.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.Test'],check=True)
