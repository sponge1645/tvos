"""Run remote menu page swaps through the production settings slide renderer."""
from pathlib import Path
import subprocess,tempfile
from settings_transitions_fixture import write
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/RemoteShortcutSettingsActivity.java').read_text()
methods='\n'.join(block(source,s) for s in ['        void captureOutgoing(', '        void show(String nextTitle', '        void drawTransitionPage('])
harness='''import java.util.*;import android.graphics.*;import io.tvos.settings.*;
class SystemClock {static long now;static long uptimeMillis(){return now;}}
public class RemoteMenuHarness {
 static class Row {String name;Row(String n){name=n;}}
 List<Row> rows=new ArrayList<>(),previousRows=new ArrayList<>();
 String title="",hint="";int focus,offset,depth,screen,previousScreen,previousOffset,previousFocus;
 boolean forward,capturing,previousCapturing,drawLight,reduced,hasDrawn=true,exiting;
 static class Entry {Object bitmap;} Entry entry; final RectF rect=new RectF();
 int getWidth(){return 1920;}int getHeight(){return 1080;}float k=1;long entered,last;
 Paint labelPaint=new Paint();SettingsTextRenderer typography=new SettingsTextRenderer();
 static class R {static class string {static int settings_remote_press;}}
 static class SettingsListRenderer {static int normalInk(boolean light){return 0;}}
 static class Ui {static Object displaySemibold;}
 String text(int id){return "Press";}
 final SettingsTransitions transitions=new SettingsTransitions();
 final java.util.function.IntUnaryOperator parent=page->this.forward?(page==this.screen?this.previousScreen:-1):(page==this.previousScreen?this.screen:-1);
 final Object rowsContent=new Object();final Viewport viewport=new Viewport();
 class Viewport {List<String> drawn=new ArrayList<>();void draw(Canvas c,Object content){drawn.add(rows.isEmpty()?"empty":rows.get(0).name);}}
 static class Spoken {void focusMoved(){}}final Spoken spoken=new Spoken();
 void reveal(){}void fit(){}void setContentDescription(String s){}void requestFocus(){}void invalidate(){}boolean reduced(){return reduced;}
'''+methods+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 void navigate(String name,int nextDepth){captureOutgoing(capturing,nextDepth);show(name,"",new ArrayList<>(Arrays.asList(new Row(name))),0,nextDepth);}
 void render(){viewport.drawn.clear();transitions.drawList(new Canvas(),screen,SystemClock.now,1,1920,1080,parent,this::drawTransitionPage);}
 public static void main(String[] args){
 RemoteMenuHarness m=new RemoteMenuHarness();m.transitions.sync(0,0,true,false,m.parent);
 m.transitions.seedTitle("Settings");
 SystemClock.now=100;m.navigate("root",1);m.render();check(m.viewport.drawn.equals(Arrays.asList("empty","root")));
 m.entry=new Entry();m.render();check(m.viewport.drawn.equals(Arrays.asList("root")));m.entry=null;
 SystemClock.now=400;m.navigate("edit",2);m.render();check(m.viewport.drawn.equals(Arrays.asList("root","edit")));
 SystemClock.now=700;m.navigate("loading apps",4);m.navigate("loaded apps",4);m.render();check(m.viewport.drawn.equals(Arrays.asList("edit","loaded apps")));
 SystemClock.now=1000;m.navigate("edit",2);m.render();check(m.viewport.drawn.equals(Arrays.asList("loaded apps","edit")));check(!m.forward);
 check(m.rows.get(0).name.equals("edit")&&m.focus==0&&m.offset==0);
 SystemClock.now=1300;m.reduced=true;m.navigate("root",1);m.render();check(m.viewport.drawn.equals(Arrays.asList("root"))&&m.transitions.focusReady(1300));
 System.out.println("PASS: remote entry, nested navigation, asynchronous same-page update, multi-level return and reduced motion");
 }
}'''
# Paint's recording adapter needs the color API used by the production adapter.
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);write(root)
 p=root/'android/graphics/RectF.java';p.write_text('package android.graphics;public class RectF {public void set(float a,float b,float c,float d){}}')
 p=root/'android/graphics/Canvas.java';p.write_text(p.read_text().replace('public int depth;', 'public void drawBitmap(Object bitmap,Object source,RectF dest,Object paint){} public int depth;'))
 p=root/'android/graphics/Paint.java';p.write_text(p.read_text().replace('public int getAlpha()', 'public void setColor(int color){} public int getAlpha()'))
 p=root/'RemoteMenuHarness.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'RemoteMenuHarness'],check=True)
