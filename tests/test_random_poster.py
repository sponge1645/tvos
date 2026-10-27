"""Execute the real random-slot controller and HomeView catalog wiring with deterministic requests."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
home=Path('app/src/io/tvos/HomeView.java').read_text()
sources={
'io/tvos/Recs.java':'''package io.tvos;import java.util.*;class Recs {static class Pick {int id;String kind,title;double rating;}List<Pick> picks=new ArrayList<>();List<Pick> catalog(String kind){return picks;}}''',
'io/tvos/AppLanguage.java':'package io.tvos;class AppLanguage {static AppLanguage current(){return new AppLanguage();}}',
'io/tvos/Ui.java':'package io.tvos;class Ui {static String language="en";}',
'io/tvos/R.java':'package io.tvos;class R {static class string {static final int home_genre_loading=1,home_random_series=2,home_random_movie=3;}}',
'io/tvos/AppStrings.java':'package io.tvos;class AppStrings {static String get(int id){return id==1?"Loading":id==2?"Random Series":"Random Movie";}}',
'io/tvos/UiRequest.java':'package io.tvos;class UiRequest {boolean cancelled;void cancel(){cancelled=true;}}',
'io/tvos/GenreCatalog.java':'''package io.tvos;import java.util.function.Consumer;class GenreCatalog {int calls,genre;String kind;UiRequest pending;Consumer<Recs.Pick> ready;UiRequest random(String k,int g,AppLanguage l,Consumer<Recs.Pick> c){calls++;kind=k;genre=g;ready=c;return pending=new UiRequest();}}''',
'io/tvos/RandomPoster.java':Path('app/src/io/tvos/RandomPoster.java').read_text(),
'io/tvos/RandomHarness.java':'''package io.tvos;import java.util.*;public class RandomHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static Recs.Pick pick(int id,double rating){Recs.Pick p=new Recs.Pick();p.id=id;p.rating=rating;p.kind="movie";return p;}
 final Recs recs=new Recs();final RandomPoster randomPoster=new RandomPoster();int tab=1;boolean searchMode;List<Recs.Pick> searchResults=new ArrayList<>();
 static final String[] TAB_KIND={null,"movie",null,"tv",null};
 static class Genres {int id;List<Recs.Pick> selected;int selectedId(){return id;}List<Recs.Pick> movies(){return selected;}}final Genres genres=new Genres(),tvGenres=new Genres();
'''+''.join(block(home,s) for s in ['    private List<Recs.Pick> baseCatalog()', '    private int randomGenre()', '    private List<Recs.Pick> currentCatalog()'])+'''
 public static void main(String[] args){
  RandomHarness h=new RandomHarness();h.recs.picks.add(pick(1,8));
  for(int tab:new int[]{0,1,3}){h.tab=tab;List<Recs.Pick> all=h.currentCatalog();check(all.size()==2&&all.get(1).id==-1,"one tail for each ordinary category");}
  for(int i=2;i<=25;i++)h.recs.picks.add(pick(i,8));h.tab=1;List<Recs.Pick> capped=h.currentCatalog();check(capped.size()==25&&capped.get(24).id==-1&&h.recs.picks.size()==25,"24 original posters plus question at position 25 without changing source catalog");
  h.searchMode=true;check(h.currentCatalog()==h.searchResults,"search has no synthetic tail");h.searchMode=false;h.tab=4;check(h.currentCatalog().isEmpty(),"roulette has no tail");
  RandomPoster r=new RandomPoster();GenreCatalog c=new GenreCatalog();int[] changes={0},errors={0};Runnable changed=()->changes[0]++,failed=()->errors[0]++;
  Recs.Pick placeholder=r.tail("tv:5","tv");check(r.placeholder(placeholder)&&placeholder.title.equals("Random Series"),"TV placeholder");
  r.load(c,"tv",5,null,changed,failed);r.load(c,"tv",5,null,changed,failed);check(c.calls==1&&c.genre==5&&c.kind.equals("tv"),"one request with genre/type");
  check(r.tail("tv:5","tv").title.equals("Loading"),"loading label");
  Recs.Pick selected=pick(42,7);c.ready.accept(selected);check(r.tail("tv:5","tv")==selected&&!r.placeholder(selected),"resolved tail is ordinary program-choice poster");
  check(!r.resetAway(21,25),"four positions keeps selection");check(r.resetAway(20,25)&&r.tail("tv:5","tv")==placeholder,"five positions resets question");
  r.load(c,"tv",5,null,changed,failed);java.util.function.Consumer<Recs.Pick> stale=c.ready;UiRequest request=c.pending;r.clear();stale.accept(selected);check(request.cancelled&&r.tail("movie","movie")!=selected,"pause cancels late result");
  r.load(c,"movie",0,Arrays.asList(pick(1,6.5),pick(2,6.6)),changed,failed);check(r.tail("movie","movie").id==2,"recent pool uses strictly greater than 6.5");
  r.clear();r.tail("empty","movie");r.load(c,"movie",0,Arrays.asList(pick(1,6.5)),changed,failed);check(errors[0]==1,"empty eligible pool reports failure");
  r.tail("new","movie");r.load(c,"movie",0,null,changed,failed);request=c.pending;r.tail("otherGenre","movie");check(request.cancelled,"genre change cancels request");
  System.out.println("PASS: random tail catalogs/search/roulette, genre routing, loading, duplicate input, five-position reset, strict rating and stale cancellation");
 }
}'''
}
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for n,s in sources.items():
  p=root/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.RandomHarness'],check=True)
