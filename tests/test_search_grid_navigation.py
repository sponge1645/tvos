"""Execute the production search grid's row and column policy."""
from pathlib import Path
import subprocess, tempfile
with tempfile.TemporaryDirectory() as d:
 root=Path(d);(root/'SearchGridNavigation.java').write_text(Path('app/src/io/tvos/SearchGridNavigation.java').read_text())
 (root/'Harness.java').write_text('''package io.tvos;class Harness {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(int count:new int[]{1,6,9,15,40})for(int i=0;i<count;i++){
   int down=SearchGridNavigation.vertical(i,count,i%6,1);
   check(down>=0&&down<count);
   check(i/6==0?SearchGridNavigation.vertical(i,count,i%6,-1)==-1:SearchGridNavigation.vertical(i,count,i%6,-1)==i-6);
   if(i%6==0)check(SearchGridNavigation.horizontal(i,count,-1)==i);
   if(i%6==5)check(SearchGridNavigation.horizontal(i,count,1)==i);
  }
  float second=SearchGridNavigation.reveal(990,1465,1080,0);
  check(second==417); // Both complete rows remain visible with focus padding.
  check(480-second>=32 && 1465-second<=1080-32);
  check(SearchGridNavigation.reveal(990,1465,1080,second)==second);
  check(SearchGridNavigation.reveal(480,955,1080,second)==second);
  check(SearchGridNavigation.reveal(1500,1975,1080,second)==927);
  check(SearchGridNavigation.reveal(480,955,1080,927)==448);
  check(SearchGridNavigation.reveal(990,1465,720,0)==777);
  check(SearchGridNavigation.reveal(480,955,1080,0)==0);
  check(SearchGridNavigation.vertical(11,15,5,1)==14);
  check(SearchGridNavigation.vertical(14,15,5,-1)==11);
  check(SearchGridNavigation.vertical(14,15,5,1)==14);
  System.out.println("PASS: rows, partial last rows, retained column, boundaries and one-step return to search");
 }}''')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.Harness'],check=True)
