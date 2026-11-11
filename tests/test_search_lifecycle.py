"""Actual detail request and Activity callbacks reject paused results and resume unfinished work."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SearchActivity.java').read_text()
h='''import java.util.*;import java.util.concurrent.*;
class Base {protected void onPause(){}protected void onResume(){}}
public class SearchLifecycle extends Base {
 static class Recs {static class Pick {String backdrop="old";}}
 static class View {static final int INVISIBLE=4;void setVisibility(int n){}String getText(){return "query";}}View query=new View();
 static class Search {void cancel(){}void pause(boolean b){}void resume(String s){}void restore(String s,boolean b){}}Search search=new Search();static class Shortcuts{void cancel(){}void reload(){}}Shortcuts shortcuts=new Shortcuts();
 static class Animation {void cancel(){}}Animation scrolling;void resetPageTransition(){}
 static class SearchData {static int calls;static class Detail {String backdrop="new",overview="";}static Detail detail(Recs.Pick p)throws java.io.IOException,org.json.JSONException{calls++;return new Detail();}}
 static class R {static class string {static final int search_detail_unavailable=1;}}
 List<Runnable> posted=Collections.synchronizedList(new ArrayList<>());class Main{void post(Runnable r){posted.add(r);}}Main main=new Main();
 ExecutorService metadataIo=Executors.newSingleThreadExecutor();Future<?> detailWork;Recs.Pick detail;int generation,selected=0,savedSelected,builds,refreshes;float scroll,searchScroll;boolean closed,running=true,detailNeedsLoad;String detailArt,detailLogo,detailOverview;
 List<Object> matches=new ArrayList<>();static class Cache{boolean empty;void setWindow(Set<String> s){empty=s.isEmpty();}}Cache images=new Cache();static class Renderer{void clear(){}}Renderer rounded=new Renderer(),posters=new Renderer();
 void remember(){}void hideKeyboard(){}String wide(String v){return v;}void buildDetail(SearchData.Detail d){builds++;}void scrollTo(float v,boolean a){}void select(int i,boolean b){}void refreshImages(){refreshes++;images.empty=false;}String text(int n){return "error";}String paragraph(String s,int a,int b,int c,int d){return s;}static class Page{void invalidate(){}}Page page=new Page();
 void drain(){for(Runnable r:new ArrayList<>(posted))r.run();posted.clear();}static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
'''+''.join(block(source,s) for s in ['    private void showDetail(', '    private void loadDetail(', '    @Override protected void onPause(', '    @Override protected void onResume('])+'''
 public static void main(String[] args)throws Exception{SearchLifecycle h=new SearchLifecycle();try{
  h.showDetail(new Recs.Pick());h.detailWork.get();int before=h.builds;h.onPause();h.drain();check(h.builds==before&&h.refreshes==0&&h.images.empty&&h.detailNeedsLoad,"queued result cannot rebuild or refill cache after pause");
  h.scroll=200;h.selected=3;h.onResume();h.detailWork.get();h.drain();check(h.builds==before+1&&!h.detailNeedsLoad&&!h.images.empty&&h.scroll==200,"unfinished detail reloads on resume without resetting scroll");
  int calls=SearchData.calls;h.onPause();h.onResume();check(SearchData.calls==calls,"completed detail does not redownload on every resume");
  h.detailNeedsLoad=true;h.loadDetail();h.detailWork.get();h.onPause();h.onResume();h.detailWork.get();h.drain();check(!h.detailNeedsLoad,"old-visit result rejected after rapid pause/resume");
  System.out.println("PASS: actual search pause/resume, stale detail result, cache release, unfinished retry and retained scroll");
 }finally{h.metadataIo.shutdownNow();}}
}'''
with tempfile.TemporaryDirectory() as tmp:
 r=Path(tmp);(r/'SearchLifecycle.java').write_text(h);p=r/'org/json/JSONException.java';p.parent.mkdir(parents=True);p.write_text('package org.json;public class JSONException extends Exception {}')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,r.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'SearchLifecycle'],check=True)
