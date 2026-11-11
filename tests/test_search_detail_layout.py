"""Exercise the actual detail builder: one Watch action, stable focus and bounded hero text."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SearchActivity.java').read_text()
h='''package io.tvos;import java.util.*;
class HeroHarness {
 static class Recs {static class Pick {String title="A very long title",kind="tv",tvOrigin="2026",movieOrigin="";double rating=8.5;}}
 static class SearchData {static class Detail {String logo="",genres="",overview="";int minutes;}}
 static class StaticLayout {final int height;StaticLayout(int h){height=h;}int getHeight(){return height;}}
 static class Ui {static Object displayBold;static Locale locale(){return Locale.US;}}
 static class AppLanguage {static AppLanguage current(){return new AppLanguage();}}
 static class R {static class string {static final int search_series=1,search_movie=2,search_runtime=3,search_loading=4,search_no_overview=5,search_watch=6;}}
 String text(int id){return id==6?"Watch":id==3?"%d min":"text";}
 StaticLayout paragraph(String v,float size,int color,int width,int lines,Object... font){return new StaticLayout((int)(Math.min(lines,Math.max(1,(v.length()+39)/40))*size*1.2f));}
 static class Spring{void snap(int v){}}static class Target{Runnable action;Target(Runnable r){action=r;}}
 final List<Target> targets=new ArrayList<>();final List<Integer> posterTargets=new ArrayList<>();final List<Spring> focus=new ArrayList<>();
 final Recs.Pick detail=new Recs.Pick();String detailLogo="";StaticLayout detailTitle,detailOverview,detailMeta;float detailActionsTop,detailMetaTop,detailOverviewTop,contentHeight;int selected=-1;
 static class Page{void invalidate(){}}final Page page=new Page();int asks;class Launcher{void chooseService(Recs.Pick p){if(p!=detail)throw new AssertionError();asks++;}}final Launcher launcher=new Launcher();
 void target(float x,float y,float w,float h,String name,int poster,Runnable r){if(x!=80||y!=936||w!=270||h!=76||!name.contains("Watch"))throw new AssertionError("Watch geometry/label");targets.add(new Target(r));focus.add(new Spring());}
 void refreshImages(){}
'''+block(source,'    private void buildDetail(')+'''
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[] args){HeroHarness h=new HeroHarness();
 for(boolean longText:new boolean[]{false,true})for(String logo:new String[]{"","https://image.tmdb.org/t/p/w500/logo.png"}){
  SearchData.Detail d=new SearchData.Detail();d.logo=logo;d.genres=longText?"Genre ".repeat(80):"Drama";d.overview=longText?"Synopsis ".repeat(300):"Short synopsis";h.buildDetail(d);
  check(h.targets.size()==1&&h.selected==0&&h.posterTargets.isEmpty(),"one focused action without inline providers");
  check(h.contentHeight==1080&&h.detailActionsTop+76<1080,"Watch fits screen without scrolling");
  check(h.detailOverviewTop+h.detailOverview.getHeight()<h.detailActionsTop&&h.detailMetaTop+h.detailMeta.getHeight()<h.detailOverviewTop,"text blocks cannot overlap Watch or each other");
  check(h.detailMetaTop-28-h.detailTitle.getHeight()>80,"fallback title remains on-screen");
  check(h.detailLogo.equals(logo),"logo belongs to current title");h.targets.get(0).action.run();
 }
 h.buildDetail(null);check(h.detailLogo.isEmpty()&&h.targets.size()==1,"next title loading clears old logo and actions");
 check(h.asks==4,"Watch always invokes chooser");System.out.println("PASS: hero title/logo fallback, bounded text, Watch geometry, single focus and chooser action");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'HeroHarness.java';p.write_text(h)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.HeroHarness'],check=True)
