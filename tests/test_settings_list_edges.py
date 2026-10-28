"""Check continuous row geometry and coverage of both fade bands in a long list."""
from pathlib import Path
import os
import subprocess
import tempfile

source = Path('app/src/io/tvos/SettingsActivity.java').read_text()

def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

body = ''.join(method(s) for s in [
    '        private void stepMainScroll(',
    '        private int scrollingFirstRow(',
    '        private int scrollingEndRow(',
    '        private void setScrollingRowRect(',
    '        private void setApprovedRowRect(',
])
harness = '''package io.tvos;
public class ListEdgesHarness {
 static final int PICK_VISIBLE=10, MAIN=0;
 int screen,focus,invalidations;boolean reduced;final Spring mainScroll=new Spring(.30f,1f,0);
 boolean localFlag(String key){return reduced;}void postInvalidateOnAnimation(){invalidations++;}
 static final float LIST_TOP=186, ROW_H=62, ABOUT_ROW_GAP=22,
  ABOUT_NAME_Y_OFFSET=3, LIST_X=1000, LIST_R=1824, ABOUT_NAME_RIGHT_EXTENSION=6;
 static class Box {
  float left,top,right,bottom;
  void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
  void offset(float x,float y){left+=x;right+=x;top+=y;bottom+=y;}
 }
 final Box rowRect=new Box(); final Spring listScroll=new Spring(0.30f,1f,0);
 int pickScroll, size=35; float k=1;
 int listSize(){return size;}
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
''' + body + '''
 public static void main(String[] args){
  ListEdgesHarness h=new ListEdgesHarness();
  h.focus=10;h.stepMainScroll(1f/60);check(h.mainScroll.value>0&&h.mainScroll.value<1&&h.invalidations>0,"root final row scrolls smoothly");
  h.reduced=true;h.stepMainScroll(1f/60);check(h.mainScroll.value==1,"reduced motion exposes last root row immediately");
  h.focus=0;h.stepMainScroll(1f/60);check(h.mainScroll.value==0,"first root row returns to top");
  check(h.scrollingEndRow()>PICK_VISIBLE,"draw past the old ten-row cutoff");
  h.setScrollingRowRect(10);
  check(h.rowRect.top<1080 && h.rowRect.bottom>1016,"next row intersects bottom fade");
  h.listScroll.snap(1); h.pickScroll=1;
  check(h.scrollingFirstRow()==0,"retain previous row past logical window start");
  h.setScrollingRowRect(0);
  check(h.rowRect.top<186 && h.rowRect.bottom>148,"previous row intersects top fade");
  h.listScroll.snap(0);h.pickScroll=0;h.setScrollingRowRect(9);float before=h.rowRect.top;
  h.pickScroll=1;h.setScrollingRowRect(9);
  check(h.rowRect.top==before,"changing logical window cannot jump existing rows");
  h.listScroll.target=1;h.listScroll.step(1f/60);h.setScrollingRowRect(9);
  check(h.rowRect.top<before && h.rowRect.top>before-84,"row moves fractionally, not a full step");
  for(int size=0;size<100;size++){
   h.size=size;
   for(int offset=0;offset<=Math.max(0,size-PICK_VISIBLE);offset++){
    h.listScroll.snap(offset);
    check(h.scrollingFirstRow()>=0 && h.scrollingEndRow()<=size,"bounded draw range");
   }
  }
  System.out.println("PASS: top/bottom fade coverage, continuous scrolling, bounded buffered rows");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-list-edges-') as directory:
    root = Path(directory)
    (root / 'ListEdgesHarness.java').write_text(harness)
    (root / 'Spring.java').write_text(Path('app/src/io/tvos/Spring.java').read_text())
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory,
                    str(root / 'Spring.java'), str(root / 'ListEdgesHarness.java')], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.ListEdgesHarness'], check=True)
