"""Exercise production search re-entry and roulette poster-window selection without Android UI."""
from pathlib import Path
import os
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

body = ''.join(method(s) for s in [
    '    private void beginSearch(',
    '    private void restoreSearchRequest(',
    '    private void setSearchInputOpen(',
    '    private void prepareRoulettePosters(',
])
harness = '''package io.tvos;
import java.util.*;
public class HomeWorkHarness {
 static class Recs {
  static class Pick {final int id;Pick(int id){this.id=id;}}
  List<Pick> window;int windows,loads;
  void setPosterWindow(List<Pick> w){window=w;windows++;}
  void poster(Pick p){loads++;}
 }
 boolean running=true,searchMode,heroDirty;
 String searchQuery="";int cardPage,cardIndex,searchSerial,requests,rouletteCancels;
 Object searchRequest;Runnable pendingSearch;
 List<Recs.Pick> searchResults=new ArrayList<>();
 final List<Recs.Pick> roulette=new ArrayList<>();
 int roulettePosterSlot=Integer.MIN_VALUE,rouletteWinner=7;
 final Recs recs=new Recs();final Spring searchHeadingOpacity=new Spring(.42f,1,0);
 void cancelRouletteWork(){rouletteCancels++;}void wake(){}
 void requestSearch(String q,int serial){requests++;searchRequest=new Object();}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
''' + body + '''
 public static void main(String[] args){
  HomeWorkHarness h=new HomeWorkHarness();h.searchQuery="movie";
  h.beginSearch();h.restoreSearchRequest();
  check(h.requests==1 && h.rouletteCancels==1,"entry requests query and cancels hidden roulette");
  Object active=h.searchRequest;h.beginSearch();h.restoreSearchRequest();
  check(h.requests==1 && h.searchRequest==active,"opening input cannot duplicate active request");
  h.searchRequest=null;h.searchResults.add(new Recs.Pick(1));h.cardPage=2;h.cardIndex=3;
  List<Recs.Pick> result=h.searchResults;h.beginSearch();h.restoreSearchRequest();
  check(h.requests==1 && h.searchResults==result && h.cardPage==2 && h.cardIndex==3,
        "reopen keeps results and page without network");
  h.searchResults.clear();h.pendingSearch=()->{};h.beginSearch();h.restoreSearchRequest();
  check(h.requests==1,"debounced query is not submitted twice");
  h.pendingSearch=null;h.searchQuery="ab";h.restoreSearchRequest();
  check(h.requests==1,"short query is not sent");
  h.searchMode=false;h.cardPage=2;h.cardIndex=3;h.searchHeadingOpacity.snap(1);h.beginSearch();
  check(h.cardPage==0 && h.cardIndex==0 && h.searchHeadingOpacity.value==0,"fresh entry resets navigation and heading");
  for(int i=0;i<40;i++)h.roulette.add(new Recs.Pick(i));
  for(int slot=57;slot>=0;slot--){
   h.prepareRoulettePosters(slot);
   check(h.recs.window.size()==14,"bounded roulette prefetch");
   check(h.recs.window.get(0)==h.roulette.get(h.rouletteWinner),"winning poster loads first and stays resident");
   for(int offset=-3;offset<=3;offset++){
    int id=Math.floorMod(h.rouletteWinner-slot+offset,40);boolean present=false;
    for(Recs.Pick p:h.recs.window)present|=p.id==id;
    check(present,"visible roulette posters stay inside window");
   }
   int count=h.recs.windows;h.prepareRoulettePosters(slot);
   check(h.recs.windows==count,"same strip slot does not rebuild prefetch window");
  }
  h.running=false;int count=h.recs.windows;h.prepareRoulettePosters(20);
  check(h.recs.windows==count,"hidden home does not load roulette posters");
  System.out.println("PASS: search result/page reuse, request deduplication, fresh entry, bounded roulette windows");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-home-work-') as directory:
    root = Path(directory)
    (root / 'HomeWorkHarness.java').write_text(harness)
    (root / 'Spring.java').write_text(Path('app/src/io/tvos/Spring.java').read_text())
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory,
                    str(root / 'Spring.java'), str(root / 'HomeWorkHarness.java')], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.HomeWorkHarness'], check=True)
