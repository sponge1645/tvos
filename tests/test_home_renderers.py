"""Whole-class regression for remaining home rendering responsibilities and their owned caches."""
from pathlib import Path
import subprocess,tempfile
from settings_strings_fixture import write as write_strings
from home_renderers_fixture import files
from tmdb_metadata_fixture import block
harness='''package io.tvos;
import android.graphics.*;import android.os.*;import android.content.*;import java.util.*;import org.json.*;
public class HomeRenderHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void near(float a,float b,String why){check(Math.abs(a-b)<.02f,why+" "+a+" != "+b);}
 static List<Canvas.Entry> entries(Canvas c,String kind){List<Canvas.Entry> out=new ArrayList<>();for(Canvas.Entry e:c.entries)if(e.kind.equals(kind))out.add(e);return out;}
 static Canvas.Entry text(Canvas c,String value){for(Canvas.Entry e:entries(c,"text"))if(e.text.equals(value))return e;throw new AssertionError("missing text "+value);}
 static Object field(Object o,String name)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static Paint paint(float size){return Ui.text(Ui.medium,size,0xFF1D1D1F);}
 static void background(){
  Scene scene=new Scene();int[] changes={0};HomeBackground b=new HomeBackground(scene,()->changes[0]++);b.resize(1920,1080);SystemClock.now=100;
  Canvas c=new Canvas();b.draw(c,0);check(entries(c,"rect").size()==1,"day gradient without photo");int gradients=LinearGradient.allocations;b.draw(new Canvas(),0);check(LinearGradient.allocations==gradients,"gradient reused");
  Backdrop a=new Backdrop(10),next=new Backdrop(20);b.show(a);check(scene.glass==a.lightGlass&&scene.frost==a.frost,"first photo populates glass immediately");check(b.tickFade(900,true),"first transition completes");
  SystemClock.now=1000;b.show(next);check(scene.from==a.lightGlass&&scene.to==next.lightGlass,"photo crossfade pair");b.tickFade(1400,true);near(scene.mix,.5f,"halfway glass mix");check(scene.frost[0]==0xFFCCCCCC,"frost pixels blended");
  c=new Canvas();SystemClock.now=1400;b.draw(c,1);check(entries(c,"bitmap").size()==2,"both photos in transition");check(entries(c,"bitmap").get(0).filter instanceof ColorMatrixColorFilter,"drawer veil applied in bitmap drawing");
  b.tickFade(1800,true);check(scene.glass==next.lightGlass&&!b.tickFade(1801,true),"settled target no longer retains active transition");
  b.checkNight(true,false);check(b.night()==1&&scene.glass==next.nightGlass&&changes[0]==1,"immediate night updates glass");
  c=new Canvas();b.draw(c,0);check(entries(c,"bitmap").size()==1&&entries(c,"bitmap").get(0).text.equals("21"),"night image");check(b.profilePixels()==next.nightFrost,"profile uses existing tiny night pixels");
  SystemClock.now=2000;b.checkNight(false,true);
  check(b.tickNight(2500),"reverse transition starts");near(b.night(),.84375f,"quarter transition is smooth");int pairs=scene.pairs;
  b.tickNight(3000);near(b.night(),.5f,"half transition");check(scene.frost[0]==0xFF777777,"midpoint glass frost uses mixed pixels");
  b.tickNight(3500);near(b.night(),.15625f,"three-quarter transition");check(scene.pairs==pairs,"glass pair reused through transition");
  check(b.tickNight(4000)&&b.night()==0&&!b.tickNight(4001),"day transition settles and stops ticking");
  SystemClock.now=4100;b.show(null);b.tickFade(4900,true);check(scene.glass!=null&&b.profilePixels()==null,"gradient restores neutral glass");
  Backdrop hero=new Backdrop(30);hero.image=new Bitmap(99);hero.lightImage=null;hero.nightImage=null;
  b.show(hero,false);c=new Canvas();b.draw(c,0);check(entries(c,"bitmap").size()==1&&entries(c,"bitmap").get(0).text.equals("99"),"Dock hero uses full photo instead of blurred backdrop");check(!b.tickFade(5000,true),"reduced motion skips photo crossfade");
  Backdrop moving=new Backdrop(40);moving.image=new Bitmap(100);moving.lightImage=moving.nightImage=null;
  SystemClock.now=6000;b.show(moving,true,1);SystemClock.now=6200;c=new Canvas();b.draw(c,0);Canvas.Entry incoming=entries(c,"bitmap").get(1);float startCenter=(incoming.l+incoming.r)/2;check(startCenter>960&&incoming.l<=0&&incoming.r>=1920&&incoming.t<=0&&incoming.b>=1080,"rightward Dock scroll starts image to the right with all edges covered");
  b.show(moving,true,-1);SystemClock.now=6400;c=new Canvas();b.draw(c,0);incoming=entries(c,"bitmap").get(1);near((incoming.l+incoming.r)/2,976,"incoming photo moves left during original fade, enrichment does not restart it");check((incoming.l+incoming.r)/2<startCenter,"opposing movement approaches center");
  Backdrop reverse=new Backdrop(50);reverse.image=new Bitmap(101);reverse.lightImage=reverse.nightImage=null;b.show(reverse,true,-1);c=new Canvas();b.draw(c,0);Canvas.Entry outgoing=entries(c,"bitmap").get(0);near((outgoing.l+outgoing.r)/2,976,"interrupted fade preserves outgoing image pose");
  SystemClock.now=6600;c=new Canvas();b.draw(c,0);incoming=entries(c,"bitmap").get(1);check((incoming.l+incoming.r)/2<960&&incoming.l<=0&&incoming.r>=1920,"reverse Dock scroll starts image to the left without exposed edges");SystemClock.now=6800;c=new Canvas();b.draw(c,0);incoming=entries(c,"bitmap").get(1);near((incoming.l+incoming.r)/2,944,"reverse image moves right toward center");
  b.tickFade(7200,true);SystemClock.now=7200;c=new Canvas();b.draw(c,0);incoming=entries(c,"bitmap").get(0);near(incoming.l,0,"settled image restores original left edge");near(incoming.r,1920,"settled image restores full composition without persistent crop");
  SystemClock.now=7300;b.show(moving,false,1);c=new Canvas();b.draw(c,0);incoming=entries(c,"bitmap").get(0);check(incoming.l==0&&incoming.r==1920&&!b.tickFade(7400,true),"Reduced Motion skips both pan and fade");
  b.show(null,false);
  int count=LinearGradient.allocations;b.resize(1280,720);b.draw(new Canvas(),0);check(LinearGradient.allocations==count+2,"gradients rebuilt for resize");
 }
 static void images()throws Exception{
  RoundedImages images=new RoundedImages();Bitmap bitmap=new Bitmap(1);RectF rect=new RectF(10,20,110,170);Ui.LEGACY=false;Canvas c=new Canvas();images.draw(c,bitmap,rect,12);check(entries(c,"bitmap").size()==1&&c.depth==0,"modern clipped bitmap path");
  Ui.LEGACY=true;c=new Canvas();images.draw(c,bitmap,rect,12);int count=BitmapShader.created;check(entries(c,"round").size()==1,"legacy shader path");images.draw(new Canvas(),bitmap,rect,12);check(BitmapShader.created==count,"shader reused");images.clear();images.draw(new Canvas(),bitmap,rect,12);check(BitmapShader.created==count+1,"pause releases shader cache");
  check(((Map<?,?>)field(images,"imageShaders")).size()==1,"one live bitmap entry");Ui.LEGACY=false;
  RectF dock=new RectF(0,0,250,150);c=new Canvas();images.drawCropped(c,bitmap,dock,22);Canvas.Entry crop=entries(c,"bitmap").get(0);near((crop.r-crop.l)/(crop.b-crop.t),2f/3,"modern Dock crop keeps source image aspect");near((crop.l+crop.r)/2,125,"crop horizontal center");near((crop.t+crop.b)/2,75,"crop vertical center");check(c.depth==0,"crop clip restored");
  Ui.LEGACY=true;c=new Canvas();images.drawCropped(c,bitmap,dock,22);BitmapShader shader=(BitmapShader)entries(c,"round").get(0).shader;near(shader.matrix.sx,shader.matrix.sy,"legacy Dock crop keeps equal scale on both axes");Ui.LEGACY=false;
 }
 static void posters()throws Exception{
  Recs recs=new Recs();PosterRenderer p=new PosterRenderer(recs,new Shadows(),new RoundedImages(),8);Paint title=paint(31),meta=paint(25);RectF base=new RectF(80,262,318,619);Recs.Pick pick=new Recs.Pick();pick.id=42;pick.title="A very long title that should scroll across the card";pick.kind="movie";pick.movieOrigin="2024, US";pick.duration="120 min";pick.meta="Drama";pick.rating=8;
  p.focus(0,0,1,100);p.begin(1,0,1,0,false,true,title,meta);SystemClock.now=100;Canvas c=new Canvas();p.draw(c,pick,base,1,0);check(entries(c,"bitmap").size()==1&&c.depth==0,"poster and balanced layer");text(c,"8.0");check(!p.marqueeActive(1099)&&p.nextDelay(100)==1000,"one-second marquee dwell");
  SystemClock.now=1600;p.begin(1,0,1,0,false,true,title,meta);c=new Canvas();p.draw(c,pick,base,1,0);check(p.marqueeActive(1600)&&p.nextDelay(1600)==50,"marquee wakes at existing cadence");check(entries(c,"rect").stream().anyMatch(e->e.mode!=null),"letter edge mask");
  p.focus(-1,0,1,1700);p.begin(1,1,1,0,false,false,title,meta);c=new Canvas();p.draw(c,pick,base,0,0);check(!p.marqueeActive(9999)&&p.nextDelay(9999)==1000,"losing focus stops marquee");
  recs.missing=true;p.begin(.5f,1,0,0,false,false,title,meta);c=new Canvas();p.draw(c,pick,base,0,0);check(entries(c,"bitmap").isEmpty()&&entries(c,"round").size()>0,"missing poster placeholder");
  Recs.Pick question=new Recs.Pick();question.id=-1;question.kind="movie";question.title="Random Movie";c=new Canvas();p.draw(c,question,base,0,0);text(c,"?");text(c,"Random Movie");
  pick.watchingKey="kinopub:42";pick.meta="16 min left";c=new Canvas();p.draw(c,pick,base,0,0);
  check(entries(c,"text").stream().anyMatch(e->e.text.equals("16 min left"))&&!entries(c,"text").stream().anyMatch(e->e.text.equals("8.0")),"Watch Next caption bypasses TMDB metadata and rating badge");
  p.clear();check(((Map<?,?>)field(field(p,"posterText"),"entries")).isEmpty(),"poster text cache released");check(title.getTextSize()==31&&meta.getTextSize()==25,"caller fonts unmodified");
 }
 static HomeAppsRenderer.Frame appsFrame(){
  HomeAppsRenderer.Frame f=new HomeAppsRenderer.Frame();f.k=1;f.w=1920;f.h=1080;f.dockLiftIndex=-1;f.platterRect=new RectF(40,780,700,980);f.weatherCard=new RectF(80,708,1840,1008);f.focusedCell=new RectF();
  HomeApps.Item a=new HomeApps.Item("watch.kino"),b=new HomeApps.Item("com.seena.app");a.logo=new Bitmap(10);b.logo=new Bitmap(11);f.shelf=new ArrayList<>(Arrays.asList(a,b));f.tileRects=new RectF[]{new RectF(80,814,304,940),new RectF(352,814,576,940)};f.tileFocus=new Spring[]{new Spring(.3f,1,0),new Spring(.3f,1,1)};f.dockSlide=new Spring[]{new Spring(.3f,1,0),new Spring(.3f,1,0)};
  HomeApps.Item input=new HomeApps.Item("HDMI");input.kind=HomeApps.INPUT;input.connected=false;f.drawerItems=Arrays.asList(input);f.drawerInputs=1;f.drawerRects=new RectF[]{new RectF(80,540,250,666)};f.drawerFocus=new Spring[]{new Spring(.3f,1,0)};f.weatherBottom=true;f.others=Arrays.asList(a,b);f.gridFocus=new Spring[]{new Spring(.3f,1,0),new Spring(.3f,1,1)};f.sheetOpen=true;f.gridIndex=1;return f;
 }
 static void apps()throws Exception{
  HomeWidgets weather=new HomeWidgets();HomeAppsRenderer a=new HomeAppsRenderer(new Context(),new Scene(),new Shadows(),weather,new RoundedImages());a.resize(1);HomeAppsRenderer.Frame f=appsFrame();Canvas c=new Canvas();a.drawTiles(c,f);check(entries(c,"logo").size()==2&&entries(c,"logo").get(1).text.equals("com.seena.app"),"focused Dock item drawn last");check(entries(c,"path").size()==4&&c.depth==0,"two highlight strokes per tile");
  near(entries(c,"round").get(0).radius,30,"resting Dock tile radius");
  Object[] gradients=(Object[])field(a,"tileShaders");Object first=gradients[0],second=gradients[1];a.swapDock(0,1,2);check(gradients[1]==first,"gradient follows Dock item");a.restoreDock(f.shelf,Arrays.asList(f.shelf.get(1),f.shelf.get(0)));check(((Object[])field(a,"tileShaders"))[0]==first,"rollback restores gradient identity");
  c=new Canvas();a.drawPlatter(c,f);check(entries(c,"glass").size()==1,"Dock platter glass");c=new Canvas();a.drawDrawer(c,f);check(weather.draws==1&&entries(c,"symbol").get(0).color==0xFF8E8E93,"disconnected input dim and weather drawn");
  c=new Canvas();a.drawSheet(c,f);text(c,"All Apps");check(entries(c,"bitmap").size()==2&&!f.focusedCell.isEmpty(),"app sheet geometry and focused launch rect");
  near(entries(c,"shadow").get(0).radius,32.4f,"focused All Apps radius scales with tile");
  Ui.LEGACY=true;c=new Canvas();a.drawSheet(c,f);List<Canvas.Entry> appImages=new ArrayList<>();for(Canvas.Entry entry:entries(c,"round"))if(entry.shader instanceof BitmapShader)appImages.add(entry);near(appImages.get(0).radius,30,"resting All Apps image radius");near(appImages.get(1).radius,32.4f,"focused All Apps image radius");Ui.LEGACY=false;
  f.dockLift=1;f.dockLiftIndex=0;f.dockWiggleAt=0;SystemClock.now=100;c=new Canvas();a.drawTiles(c,f);check(entries(c,"rotate").size()==1&&entries(c,"logo").get(1).text.equals("watch.kino"),"lifted Dock item draws last with wiggle");
  a.invalidateLabels();a.resize(.5f);a.clear();check(field(a,"frame")==null,"renderer releases borrowed frame");a.invalidateDock();check(((Object[])field(a,"tileShaders")).length==0,"list reload invalidates Dock gradients");
 }
 static void header(){
  HomeHeaderRenderer h=new HomeHeaderRenderer(new Context(),new HomeData(),new ActionSurface(new Scene(),new Shadows()),new Shadows());h.resize(1);HomeHeaderRenderer.Frame f=new HomeHeaderRenderer.Frame();f.k=1;f.tab=0;f.clockText="12:34";f.dateText="monday";f.searchQuery="Film";f.showDate=f.weatherTop=true;f.lDate=paint(34);f.lTab=paint(30);f.lTabOn=paint(30);f.tabs=new String[]{"New","Movies","","Shows","Dice"};f.topRects=new RectF[11];f.topFocus=new Spring[11];for(int i=0;i<11;i++){f.topRects[i]=new RectF(80+i*120,46,180+i*120,114);f.topFocus[i]=new Spring(.3f,1,0);}f.lightButtons=Arrays.asList(new HomeApps.Item("Search"));f.networkState=1;
  h.resume();Canvas c=new Canvas();h.drawDateWeather(c,f);near(text(c,"Monday").t-Ui.cap(f.lDate)*29/34,50,"date ink aligns to button top inset");near(text(c,"10°").t-27*.7f,50,"weather text aligns to button top inset");near(entries(c,"symbol").get(0).t-entries(c,"symbol").get(0).size/2,50,"weather icon aligns to button top inset");check(entries(c,"symbol").get(0).text.equals("sun"),"date and weather header");
  f.k=2;c=new Canvas();h.drawDateWeather(c,f);near(text(c,"Monday").t-29*2*.7f,100,"date top inset scales to 4K");near(entries(c,"symbol").get(0).t-entries(c,"symbol").get(0).size/2,100,"weather top inset scales to 4K");f.k=1;
  c=new Canvas();h.drawButtons(c,f);near(text(c,"12:34").size,24.3f,"clock text shrinks 10 percent");near(entries(c,"symbol").get(0).size,27,"network icon shrinks 10 percent");near(entries(c,"symbol").get(1).size,27.945f,"action icon shrinks 10 percent");check(entries(c,"symbol").stream().anyMatch(e->e.text.equals("wifi")),"Wi-Fi state");
  f.networkState=2;c=new Canvas();h.drawButtons(c,f);check(entries(c,"symbol").stream().anyMatch(e->e.text.equals("ethernet")),"Ethernet state");
  ProfileAvatar.selected=3;h.resume();c=new Canvas();h.drawButtons(c,f);check(entries(c,"avatar").size()==1&&ProfileAvatar.clears==1,"avatar invalidates only when selection changes");h.resume();check(ProfileAvatar.clears==1,"unchanged avatar reused");
  f.searchMode=true;f.headingOpacity=1;c=new Canvas();h.drawTabs(c,f);check(entries(c,"text").size()==1,"search replaces category titles");
  f.searchMode=false;Prefs.categories[2]=false;c=new Canvas();h.drawTabs(c,f);check(entries(c,"text").size()==4,"disabled category skipped");Prefs.categories[2]=true;h.clearFrame();
 }
 static void notices()throws Exception{
  Context ctx=new Context();Handler handler=new Handler();List<Runnable> worker=new ArrayList<>();int[] invalidates={0},wakes={0},announces={0};HomeNotices n=new HomeNotices(ctx,new Scene(),worker::add,handler,()->invalidates[0]++,()->wakes[0]++,text->announces[0]++);n.resize(1,1920);n.add("First");n.add("Second");check(n.busy()&&!n.visible()&&announces[0]==2&&wakes[0]==2,"queue announcement and wake");SystemClock.now=100;check(n.tick(100)&&n.visible(),"initial fade begins");Canvas c=new Canvas();SystemClock.now=500;n.draw(c);text(c,"First");check(c.depth==0,"toast layers balanced");check(!n.tick(1000),"stable toast no fade invalidation");n.tick(2601);c=new Canvas();SystemClock.now=3100;n.draw(c);text(c,"Second");n.tick(5102);check(!n.busy(),"queue drains in order");
  SystemClock.now=10000;HomeNotices quiet=new HomeNotices(ctx,new Scene(),worker::add,handler,()->{},()->{},text->{});quiet.resize(1,1920);quiet.hint("Dock");quiet.hint("Dock");quiet.add("Dock");check(((java.util.Collection<?>)field(quiet,"toasts")).size()==1,"repeated hints do not queue");
  quiet.tick(10000);quiet.add("Dock");quiet.tick(12501);quiet.add("Dock");check(!quiet.busy(),"duplicates suppressed during active toast and cooldown");
  SystemClock.now=30000;quiet.hint("Dock");check(!quiet.busy(),"Dock hint remains once per session");
  for(int i=0;i<30;i++)quiet.add("message"+i);check(((java.util.Collection<?>)field(quiet,"toasts")).size()==3&&((Map<?,?>)field(quiet,"recent")).size()==16,"queue and duplicate memory bounded");
  long now=System.currentTimeMillis();HomeMode.state=new JSONObject().put("notices",new JSONArray().put(new JSONObject().put("at",now).put("text","Newest")).put(new JSONObject().put("at",now-1000).put("text","Older")).put(new JSONObject().put("at",now-100_000_000).put("text","Expired")));
  n.check();worker.remove(0).run();check(ctx.prefs.getLong("seenNotice",0)==now&&!n.busy(),"worker persists cursor before UI delivery");handler.drain();check(n.busy(),"fresh notices delivered on main handler");n.check();worker.remove(0).run();check(handler.work.isEmpty(),"seen cursor avoids duplicate delivery");
  List<String> spoken=new ArrayList<>();Context translatedContext=new Context();Handler translatedHandler=new Handler();List<Runnable> translatedWorker=new ArrayList<>();
  HomeNotices translated=new HomeNotices(translatedContext,new Scene(),translatedWorker::add,translatedHandler,()->{},()->{},spoken::add);
  translated.resize(1,1920);AppLanguage.select("en");JSONObject message=new JSONObject().put("at",now).put("text","stale English fallback").put("message",NoticeText.message(R.string.runtime_mode_enabled,7));
  HomeMode.state=new JSONObject().put("notices",new JSONArray().put(message));translated.check();translatedWorker.remove(0).run();AppLanguage.select("ru");translatedHandler.drain();
  check(spoken.size()==1&&spoken.get(0).equals("tvOS включён: новый главный экран и заставка; отключено приложений: 7."),"home notice follows language at UI delivery instead of stored fallback");
  translated.tick(100);SystemClock.now=500;Canvas localized=new Canvas();translated.draw(localized);text(localized,spoken.get(0));AppLanguage.select("en");
 }
 public static void main(String[] args)throws Exception{AppLanguage.select("en");background();images();posters();apps();header();notices();System.out.println("PASS: whole background/images/poster/apps/header/notices classes, drawing geometry, focus, cache identities, theme/resize, marquee timing, queue and persisted notice cursor");}
}'''

focus=block(Path('app/src/io/tvos/HomeView.java').read_text(),'    private int focusedPoster()')
harness=harness.replace(' static void posters()throws Exception{', ''' static class FocusHarness {
 boolean sheetOpen,searchMode;int tab,zone=2,cardIndex;static final int Z_CARDS=2;
 List<Recs.Pick> cards=new ArrayList<>(Arrays.asList(new Recs.Pick()));boolean picksOn(){return true;}
 '''+focus+''' }
 static void posters()throws Exception{''')
harness=harness.replace('  recs.missing=true;', '''  FocusHarness focus=new FocusHarness();
  for(int tab:new int[]{0,1,3,4}){
   focus.tab=tab;focus.searchMode=true;long start=10000+tab*2000;
   p.focus(focus.focusedPoster(),0,tab,start);p.begin(1,0,tab,0,true,true,title,meta);SystemClock.now=start;p.draw(new Canvas(),pick,base,1,0);
   check(!p.marqueeActive(start+999),"search keeps normal one-second dwell");
   SystemClock.now=start+1500;p.begin(1,0,tab,0,true,true,title,meta);Canvas search=new Canvas();p.draw(search,pick,base,1,0);
   check(p.marqueeActive(start+1500)&&text(search,pick.title).l<base.left&&entries(search,"rect").stream().anyMatch(e->e.mode!=null),"search scrolls full long title with edge mask from every tab, including roulette");
  }
  focus.searchMode=false;focus.tab=4;check(focus.focusedPoster()==-1,"roulette itself still has no poster marquee");
  focus.searchMode=true;focus.sheetOpen=true;check(focus.focusedPoster()==-1,"sheet blocks underlying marquee");focus.sheetOpen=false;focus.zone=1;check(focus.focusedPoster()==-1,"top focus stops poster marquee");
  focus.zone=2;focus.cards.clear();check(focus.focusedPoster()==-1,"empty results have no marquee");
  recs.missing=true;''')
with tempfile.TemporaryDirectory(prefix='tvos-home-renderers-') as tmp:
 root=Path(tmp);write_strings(root);sources=files();sources['io/tvos/HomeRenderHarness.java']=harness
 for name,value in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.HomeRenderHarness'],check=True)
