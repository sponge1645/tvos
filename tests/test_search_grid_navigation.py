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
  check(SearchGridNavigation.vertical(11,15,5,1)==14);
  check(SearchGridNavigation.vertical(14,15,5,-1)==11);
  check(SearchGridNavigation.vertical(14,15,5,1)==14);
  System.out.println("PASS: rows, partial last rows, retained column, boundaries and one-step return to search");
 }}''')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.Harness'],check=True)
