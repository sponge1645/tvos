"""Execute the real page-refresh logic when history shrinks under the focused poster."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
source='''package io.tvos;import java.util.*;
public class RefreshTest {
 static final int Z_CARDS=5,Z_SHELF=1;int zone=Z_CARDS,cardPage,cardIndex=4,tab=2;boolean searchMode,running=true,heroDirty;Object labelPaint=new Object();
 static class Prefs {static boolean homeContinue(){return false;}static int firstHomeCategory(){return firstCategory();}static int firstCategory(){return 2;}}
 static class Recs {static class Pick {int id=1;String watchingKey="history";}void setPosterWindow(List<Pick> p){}void poster(Pick p){}void requestDuration(Pick p){}Backdrop backdrop(Pick p){return null;}}
 static class Backdrop {}static class Background {void show(Backdrop b){}}
 Recs recs=new Recs();Background background=new Background();List<Recs.Pick> catalog=new ArrayList<>(),cards=new ArrayList<>();
 Spring[] cardFocus={new Spring(.3f,1,0),new Spring(.3f,1,0),new Spring(.3f,1,0),new Spring(.3f,1,0),new Spring(.3f,1,1)};
 static class Roulette {boolean spinning(){return false;}boolean finished(){return false;}void invalidatePosters(){}}Roulette rouletteWork=new Roulette();
 static class Spoken {void focusMoved(){}}Spoken spoken=new Spoken();
 List<Recs.Pick> currentCatalog(){return catalog;}
 void retarget(boolean snap){for(int i=0;i<cardFocus.length;i++)cardFocus[i].target=zone==Z_CARDS&&i==cardIndex?1:0;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+block(home,'    private static boolean samePicks(')+block(home,'    private void updateCards(')+'''
 public static void main(String[] args){
  RefreshTest t=new RefreshTest();for(int i=0;i<5;i++)t.catalog.add(new Recs.Pick());t.cards=new ArrayList<>(t.catalog);
  t.catalog=new ArrayList<>(t.catalog.subList(0,2));t.updateCards();
  check(t.cardIndex==1&&t.zone==Z_CARDS,"shrunken history clamps actionable selection");check(t.cardFocus[1].target==1&&t.cardFocus[4].target==0,"highlight follows selection without a key event");
  t.catalog.clear();t.updateCards();check(t.zone==Z_SHELF,"empty history returns to Dock");for(Spring s:t.cardFocus)check(s.target==0,"removed posters lose highlight");
  t=new RefreshTest();t.searchMode=true;t.catalog.add(new Recs.Pick());t.updateCards();t.catalog.clear();t.updateCards();check(t.cardIndex==0&&t.cards.isEmpty(),"empty search retains safe selection index");
  t=new RefreshTest();t.cardPage=2;t.catalog.add(new Recs.Pick());t.updateCards();check(t.cardPage==0&&t.cardIndex==0&&t.cardFocus[0].target==1,"deleted page restores first remaining poster and highlight");
  System.out.println("PASS: history shrink, removed page, empty history/search and focus animation stay synchronized");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'RefreshTest.java').write_text(source);(p/'Spring.java').write_text(Path('app/src/io/tvos/Spring.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,p.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RefreshTest'],check=True)
