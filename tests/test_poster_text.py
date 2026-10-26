"""Run the real poster-text cache with production Pick fields and explicit display expectations."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block

view=Path('app/src/io/tvos/HomeView.java').read_text()
assert 'PosterText.Entry text = posterText.get(p);' in view
assert 'String detail = text.detail(tab == 0 && !searchMode);' in view
assert 'String rating = text.rating;' in view
assert 'badgeFill.setColor(text.badgeColor);' in view
pause=block(view,'    void pause(')
assert 'posterText.clear();' in pause
pick=block(Path('app/src/io/tvos/Recs.java').read_text(),'    static final class Pick {')

harness='''package io.tvos;
import java.util.*;import java.lang.reflect.*;
public class PosterTextHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static Recs.Pick pick(String kind,String origin,String runtime,String meta,double rating){
  Recs.Pick p=new Recs.Pick();p.kind=kind;p.movieOrigin=origin;p.tvOrigin=origin;p.duration=runtime;p.meta=meta;p.rating=rating;return p;
 }
 static void display(PosterText cache,Recs.Pick p,String detail,String fallback,String rating,int color){
  PosterText.Entry e=cache.get(p);
  check(e.detail(false).equals(detail),"normal detail: "+e.detail(false));
  check(e.detail(true).equals(fallback),"New releases fallback: "+e.detail(true));
  check(e.rating.equals(rating),"rating text: "+e.rating);
  check(e.badgeColor==color,"rounded rating color");
 }
 public static void main(String[] args)throws Exception{
  Locale.setDefault(Locale.FRANCE);
  PosterText cache=new PosterText();
  display(cache,pick("movie","2026 г., США","125 мин","Drama",8.999),"2026 · США · 125 мин","2026 · США · 125 мин","9.0",0xFF186A3A);
  display(cache,pick("tv","2019 г, UK","ignored","Genre · 2 seasons",9.799),"2019 · UK","2019 · UK","9.8",0xFF1EA1F2);
  display(cache,pick("movie",null,"90 min","Fallback",4.099),"90 min","90 min","4.1",0xFFE74C3C);
  display(cache,pick("movie","2020",null,"Fallback",4.0),"2020","2020","4.0",0xFF633974);
  display(cache,pick("movie",null,null,"2010, Drama",0),"","2010, Drama","",0);
  display(cache,pick("tv",null,"ignored","Raw cached fallback",-1),"","Raw cached fallback","",0);
  display(cache,pick(null,"ignored","ignored",null,Double.NaN),"","","",0);
  display(cache,pick("other","ignored","ignored","Other fallback",0.01),"","Other fallback","0.0",0xFF633974);
  display(cache,pick("movie"," ","","",7)," "," ","7.0",0xFFF4D03F);
  display(cache,pick("movie","2024","120 min","",Double.POSITIVE_INFINITY),"2024 · 120 min","2024 · 120 min","Infinity",0xFF1EA1F2);
  int[] colors={0xFF633974,0xFFE74C3C,0xFFF39C13,0xFFF4D03F,0xFF27B463,0xFF186A3A,0xFF1EA1F2};
  double[] ratings={4.0,4.1,6,7,8,9,9.8};
  for(int i=0;i<ratings.length;i++)check(cache.get(pick("movie","","","",ratings[i])).badgeColor==colors[i],"color boundary "+ratings[i]);

  Recs.Pick p=pick("movie","2026 г., USA","120 min","Fallback",8.2);
  PosterText.Entry first=cache.get(p);String detail=first.detail(false),rating=first.rating;
  for(int i=0;i<10000;i++){
   PosterText.Entry hit=cache.get(p);
   check(hit==first&&hit.detail(false)==detail&&hit.rating==rating,"steady frames reuse entry and strings");
  }
  p.title="A different title";check(cache.get(p)==first,"independently drawn title does not invalidate metadata");
  p.movieOrigin=new String(p.movieOrigin);check(cache.get(p)==first,"equal refreshed strings do not invalidate");
  p.movieOrigin="2027 · USA";PosterText.Entry changed=cache.get(p);
  check(changed!=first&&changed.detail(false).equals("2027 · USA · 120 min"),"origin update invalidates");
  p.duration="121 min";PosterText.Entry duration=cache.get(p);
  check(duration!=changed&&duration.detail(false).endsWith("121 min"),"duration update invalidates");
  p.rating=8.3;PosterText.Entry updatedRating=cache.get(p);
  check(updatedRating!=duration&&updatedRating.rating.equals("8.3"),"rating update invalidates");
  p.kind="tv";p.tvOrigin="2011, UK";PosterText.Entry series=cache.get(p);
  check(series!=updatedRating&&series.detail(false).equals("2011 · UK"),"kind/series origin updates invalidate");
  p.tvOrigin="2012 г., UK";PosterText.Entry series2=cache.get(p);
  check(series2!=series&&series2.detail(false).equals("2012 · UK"),"series metadata update invalidates");
  p.tvOrigin="";p.meta="First fallback";PosterText.Entry fallback=cache.get(p);
  check(fallback.detail(false).isEmpty()&&fallback.detail(true).equals("First fallback"),"fallback restricted by view context");
  p.meta="Updated fallback";PosterText.Entry fallback2=cache.get(p);
  check(fallback2!=fallback&&fallback2.detail(true).equals("Updated fallback"),"fallback update invalidates");
  p.tvOrigin="2025 · Россия";check(cache.get(p).detail(false).equals("2025 · Россия"),"late localized metadata is reflected");
  Recs.Pick second=pick("tv","2025 · Россия","","Updated fallback",8.3);
  check(cache.get(second)!=cache.get(p),"different Pick lifetimes do not share mutable snapshots");
  PosterText.Entry beforeClear=cache.get(p);cache.clear();check(cache.get(p)!=beforeClear,"pause drops prepared text");

  Field entries=PosterText.class.getDeclaredField("entries");entries.setAccessible(true);
  List<Recs.Pick> live=new ArrayList<>();
  for(int i=0;i<PosterText.MAX_ENTRIES*3;i++){
   live.add(pick("movie",Integer.toString(i),"","",5));
   cache.get(live.get(i));
   check(((Map<?,?>)entries.get(cache)).size()<=PosterText.MAX_ENTRIES,"live catalog cannot exceed text cache bound");
  }
  check(Modifier.isStatic(PosterText.Entry.class.getModifiers()),"snapshot cannot retain cache owner");
  for(Field f:PosterText.Entry.class.getDeclaredFields())
   check(f.getType().isPrimitive()||f.getType()==String.class,"weak-key value cannot retain Pick or Android objects");
  cache.clear();check(((Map<?,?>)entries.get(cache)).isEmpty(),"cache clear releases entries");
  System.out.println("PASS: unchanged poster metadata/rounding, 10000 cache hits, late data/language updates, context fallback and bounded weak storage");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-poster-text-') as tmp:
 root=Path(tmp)
 (root/'Recs.java').write_text('package io.tvos;final class Recs {'+pick+'}')
 (root/'PosterText.java').write_text(Path('app/src/io/tvos/PosterText.java').read_text())
 (root/'PosterTextHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.PosterTextHarness'],check=True)
