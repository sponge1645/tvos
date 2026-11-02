"""Execute the shared Home menu host across movie/series/service eligibility and feedback."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
for retired in ['labelPaint','resumedAt','lastStep']:
 assert retired not in home
assert 'reveal.delay(' not in home
assert 'menuHost(category)' in block(home,'    private HomeGenres createGenres(')
assert 'menuHost(2)' in block(home,'    private HomeGenres createWatchingMenu(')
assert 'return nearest(rects, rects.length, x);' in block(home,'    private static int nearest(RectF[] rects, float x)')
assert 'new RectF(' not in block(home,'    private void drawSearchGlass(')
harness='''public class MenuHostTest {
 static final int Z_TOP=4;boolean running=true,okDown=true,heroDirty,statusDirty;int tab=1,topIndex=1,zone=Z_TOP,cardPage=3,cardIndex=4,updates,targets,layouts,wakes;
 static class Session {boolean active;}final Session search=new Session();static class DrawerState {boolean open;}final DrawerState drawer=new DrawerState();
 static class Grid {boolean open;}final Grid grid=new Grid();
 static class RectF {}static class Paint {}RectF[] topRects={new RectF(),new RectF(),new RectF(),new RectF()};Paint lTabOn=new Paint();
 static class Editor {boolean editing;int cancels;boolean editing(){return editing;}void cancelHold(){cancels++;}}Editor dockEditor=new Editor();
 static class Click {int opens,moves;void open(){opens++;}void move(){moves++;}}Click click=new Click();
 static class Layer {int invalidates;void invalidate(){invalidates++;}}Layer heroLayer=new Layer();String message;
 void wake(){wakes++;}void showToast(String text){message=text;}void layoutLight(){layouts++;}void updateCards(){updates++;}void retarget(boolean snap){targets++;}
 static class HomeGenres {interface Host {String kind();boolean eligible();RectF anchor();Paint anchorPaint();void visibilityChanged();void opened();void moved();void changed();void message(String text);}}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+block(home,'    private HomeGenres.Host menuHost(')+'''
 public static void main(String[] args){
  for(int category:new int[]{1,2,3}){
   MenuHostTest t=new MenuHostTest();t.tab=t.topIndex=category;HomeGenres.Host host=t.menuHost(category);
   check(host.eligible()&&host.anchor()==t.topRects[category]&&host.anchorPaint()==t.lTabOn,"each menu uses its own anchor and eligibility");check(host.kind().equals(category==3?"tv":"movie"),"movie/series query type preserved");
   for(int guard=0;guard<8;guard++){
    t.running=guard!=0;t.okDown=guard!=1;t.search.active=guard==2;t.grid.open=guard==3;t.drawer.open=guard==4;t.dockEditor.editing=guard==5;t.zone=guard==6?0:Z_TOP;t.topIndex=guard==7?10:category;
    check(!host.eligible(),"hold rejected outside active menu context");
   }
   t.running=t.okDown=true;t.search.active=t.grid.open=t.drawer.open=t.dockEditor.editing=false;t.zone=Z_TOP;t.topIndex=category;
   host.opened();check(!t.okDown&&t.dockEditor.cancels==1&&t.click.opens==1,"hold release is consumed and sound plays once");host.moved();check(t.click.moves==1,"source and genre rows share navigation sound");
   host.visibilityChanged();check(t.heroDirty&&t.heroLayer.invalidates==1,"visibility invalidates hero");host.changed();check(t.cardPage==0&&t.cardIndex==0&&t.updates==1&&t.targets==1,"selection refreshes focused page");check(t.layouts==(category==2?0:1)&&t.statusDirty==(category!=2),"service menu avoids genre-only layout/status work");
   host.message("test");check(t.message.equals("test"),"message forwarded");
  }
  MenuHostTest t=new MenuHostTest();t.tab=0;t.menuHost(1).changed();check(t.updates==0&&t.layouts==1,"inactive genre refresh cannot replace another category");
  System.out.println("PASS: shared menu host eligibility, anchors, kinds, focus reset, source/genre feedback, visibility and inactive-genre guards");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'MenuHostTest.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'MenuHostTest'],check=True)
