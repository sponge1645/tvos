"""Execute the real page-refresh logic when history shrinks under the focused poster."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
source='''package io.tvos;import io.tvos.home.HomePosterWindow;import java.util.*;
public class RefreshTest {
 static final int Z_CARDS=5,Z_SHELF=1;int zone=Z_CARDS,cardPage,cardIndex=4,tab=2;boolean running=true,heroDirty;Object lTab=new Object();
 static class Prefs {static boolean collection=true;static boolean homeCollection(){return collection;}static int firstCategory(){return 2;}}
 static class Recs {static class Pick {int id=1;String watchingKey="history";}int windows,requests;Backdrop ready;void setPosterWindow(List<Pick> p){windows++;}void poster(Pick p){requests++;}void requestDuration(Pick p){}Backdrop backdrop(Pick p){return ready;}}
 static class Backdrop {}static class Background {Backdrop shown=new Backdrop();int calls;void show(Backdrop b){calls++;shown=b;}}
 Recs recs=new Recs();Background background=new Background();List<Recs.Pick> catalog=new ArrayList<>(),cards=new ArrayList<>();
 Spring[] cardFocus={new Spring(.3f,1,0),new Spring(.3f,1,0),new Spring(.3f,1,0),new Spring(.3f,1,0),new Spring(.3f,1,1)};
 static class Roulette {boolean spinning(){return false;}boolean finished(){return false;}void invalidatePosters(){}}Roulette rouletteWork=new Roulette();
 static class Spoken {void focusMoved(){}}Spoken spoken=new Spoken();
 List<Recs.Pick> currentCatalog(){return catalog;}
 void retarget(boolean snap){for(int i=0;i<cardFocus.length;i++)cardFocus[i].target=zone==Z_CARDS&&i==cardIndex?1:0;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+block(home,'    private void updateCards(')+'''
 public static void main(String[] args){
  RefreshTest t=new RefreshTest();for(int i=0;i<5;i++)t.catalog.add(new Recs.Pick());t.cards=new ArrayList<>(t.catalog);
  t.catalog=new ArrayList<>(t.catalog.subList(0,2));t.updateCards();
  check(t.cardIndex==1&&t.zone==Z_CARDS,"shrunken history clamps actionable selection");check(t.cardFocus[1].target==1&&t.cardFocus[4].target==0,"highlight follows selection without a key event");
  t.catalog.clear();t.updateCards();check(t.zone==Z_SHELF,"empty history returns to Dock");for(Spring s:t.cardFocus)check(s.target==0,"removed posters lose highlight");
  t=new RefreshTest();t.catalog.add(new Recs.Pick());t.updateCards();t.catalog.clear();t.updateCards();check(t.cardIndex==0&&t.cards.isEmpty(),"empty category retains safe selection index");
  t=new RefreshTest();t.cardPage=2;t.catalog.add(new Recs.Pick());t.updateCards();check(t.cardPage==0&&t.cardIndex==0&&t.cardFocus[0].target==1,"deleted page restores first remaining poster and highlight");
  for(int fixture=0;fixture<2;fixture++){
   Prefs.collection=false;t=new RefreshTest();t.zone=4;
   Backdrop kept=new Backdrop();t.background.shown=kept; // Either a movie poster or an app placeholder.
   t.recs.ready=new Backdrop();t.catalog.add(new Recs.Pick());
   for(int callback=0;callback<3;callback++)t.updateCards();
   check(t.background.shown==kept&&t.background.calls==0,"late catalog/image callbacks cannot replace Dock background on upper controls");
   check(t.cards.isEmpty()&&t.recs.requests==0,"tvOS view does not prefetch hidden collection cards");
   t.catalog.clear();t.updateCards();
   check(t.background.shown==kept&&t.background.calls==0,"empty delayed catalog cannot clear Dock background");
  }
  Prefs.collection=true;t=new RefreshTest();t.catalog.add(new Recs.Pick());t.recs.ready=new Backdrop();t.updateCards();
  check(t.background.shown==t.recs.ready&&t.background.calls==1,"collection view still updates its own poster background");
  System.out.println("PASS: history shrink, removed page, empty history/categories and focus animation stay synchronized");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'HomePosterWindow.java').write_text(Path('app/src/io/tvos/home/HomePosterWindow.java').read_text());(p/'RefreshTest.java').write_text(source);(p/'Spring.java').write_text(Path('app/src/io/tvos/Spring.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,p.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RefreshTest'],check=True)
