"""Run production remote navigation and Back against active/finished roulette states."""
from pathlib import Path
import subprocess
import tempfile

source = Path('app/src/io/tvos/HomeView.java').read_text()
def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

body = ''.join(method(s) for s in ['    private void move(', '    private void back(', '    private void cancelRouletteWork('])
harness = '''import java.util.*;
public class RouletteFocusHarness {
 static final int Z_SHELF=1,Z_CARDS=2,Z_HERO=3,Z_STATUS=4,Z_DRAWER=5,WIDGETS=6;
 boolean rouletteSpinning,rouletteFinished,rouletteLoading,dockEditing,sheetOpen,searchMode,tilesDirty;
 long rouletteStarted;int zone=Z_CARDS,shelfIndex,statusIndex,widgetIndex,drawerIndex,lightMoves,cancels;
 static class Item {} static class HomeApps {static class Item extends RouletteFocusHarness.Item {}}
 static class RectF {float centerX(){return 0;}}static class LinearGradient {}
 static class Hero {boolean button;} Hero hero;
 List<HomeApps.Item> shelf=new ArrayList<>(),statusItems=new ArrayList<>(),drawerItems=new ArrayList<>();
 RectF[] statusRects={new RectF()},tileRects={new RectF()};LinearGradient[] tileShaders;
 static class Target {void move(){}void invalidate(){}}Target click=new Target(),platter=new Target();
 static class Request {void cancel(){}}Request rouletteRequest;
 static class Handler {Set<Runnable> tasks=new HashSet<>();void removeCallbacks(Runnable r){tasks.remove(r);}}
 Handler handler=new Handler();Runnable rouletteComplete=()->{};
 void shiftDock(int dx){}void moveGrid(int dx,int dy){}
 boolean moveLight(int dx,int dy){lightMoves++;zone=Z_SHELF;return true;}
 int nearest(RectF[] r,float x){return 0;}boolean browse(int dx){return true;}
 void openDrawer(){zone=Z_DRAWER;}void closeDrawer(){}boolean talkBack(){return false;}
 List<Integer> widgets(){return Collections.emptyList();}void retarget(boolean snap){}
 void finishDockEdit(boolean save){}void closeSheet(){}void closeSearch(){}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
''' + body + '''
 public static void main(String[] args){
  RouletteFocusHarness h=new RouletteFocusHarness();h.rouletteSpinning=true;
  for(int repeat=0;repeat<20;repeat++)for(int[] d:new int[][]{{0,1},{0,-1},{1,0},{-1,0}})h.move(d[0],d[1]);
  check(h.zone==Z_CARDS && h.lightMoves==0,"arrows and held-key repeats cannot leave running roulette");
  h.rouletteLoading=true;h.rouletteStarted=123;h.handler.tasks.add(h.rouletteComplete);h.back();
  check(!h.rouletteSpinning && !h.rouletteFinished && !h.rouletteLoading && h.zone==Z_SHELF,
        "Back cancels running roulette and returns focus to Dock");
  check(!h.handler.tasks.contains(h.rouletteComplete) && h.rouletteStarted==0,
        "cancelled completion cannot steal focus after Back");
  h.zone=Z_CARDS;h.rouletteFinished=true;h.move(0,1);
  check(h.zone==Z_SHELF && h.lightMoves==1,"normal navigation resumes after winner is shown");
  h.zone=Z_CARDS;h.rouletteFinished=false;h.move(0,1);
  check(h.zone==Z_SHELF && h.lightMoves==2,"idle roulette remains freely navigable");
  System.out.println("PASS: roulette focus lock, held arrows, Back cancellation, removed completion, unlocked winner/idle");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-roulette-focus-') as directory:
    root = Path(directory)
    (root/'RouletteFocusHarness.java').write_text(harness)
    subprocess.run(['java','com.sun.tools.javac.Main','-d',directory,str(root/'RouletteFocusHarness.java')],check=True)
    subprocess.run(['java','-cp',directory,'RouletteFocusHarness'],check=True)
