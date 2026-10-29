"""Actual TMDB matching/fallback and banner rendering; no live network or API key."""
from pathlib import Path
import subprocess,tempfile
from home_renderers_fixture import files
from settings_strings_fixture import write
f=files()
f['android/text/TextPaint.java']=f['android/text/TextPaint.java'].replace('public TextPaint(){}','public TextPaint(){}public TextPaint(int flags){super(flags);}')
f['io/tvos/DockBackdrop.java']='''package io.tvos;import android.graphics.Bitmap;class DockBackdrop {static class Artwork {final Backdrop background;final Bitmap logo;final String title;Artwork(Backdrop b,String t,Bitmap l){background=b;title=t;logo=l;}}}'''
f['io/tvos/AppLog.java']='package io.tvos;class AppLog {static void w(String s){}}'
f['io/tvos/TmdbClient.java']='''package io.tvos;import org.json.*;class TmdbClient {static boolean key=true,failLogo;static JSONObject search,images;static boolean hasKey(){return key;}static JSONObject get(String path)throws java.io.IOException,org.json.JSONException {if(path.startsWith("/search/"))return search;if(failLogo)throw new java.io.IOException();return images;}}'''
for name in ['DockArtwork','DockBanner']:f['io/tvos/'+name+'.java']=Path('app/src/io/tvos/'+name+'.java').read_text()
f['io/tvos/DockArtworkHarness.java']='''package io.tvos;import org.json.*;import android.graphics.*;import android.os.SystemClock;
public class DockArtworkHarness {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}static JSONObject movie(int id,String poster,String year){return new JSONObject().put("id",id).put("media_type","movie").put("title","Film").put("release_date",year+"-01-01").put("poster_path",poster).put("backdrop_path","/wide.jpg");}
 public static void main(String[] args)throws Exception {AppLanguage.select("ru");
 JSONObject old=movie(1,"/a.jpg","2020"),fresh=movie(2,"/b.jpg","2026");JSONArray items=new JSONArray().put(old).put(fresh);
 check(DockArtwork.match(items,"Film","https://mirror/b.jpg?token=x","")==fresh,"poster filename identifies exact title across mirrors");check(DockArtwork.match(items,"Film","","2026")==fresh,"release year disambiguates remakes");check(DockArtwork.match(items,"Film","","")==null,"ambiguous title never chooses arbitrary film");check(DockArtwork.match(items,"Other","","")==null,"unrelated result rejected");
 JSONArray logos=new JSONArray().put(new JSONObject().put("file_path","/en.png").put("iso_639_1","en")).put(new JSONObject().put("file_path","/ru.png").put("iso_639_1","ru")).put(new JSONObject().put("file_path","/bad.svg").put("iso_639_1","ru"));check(DockArtwork.logo(logos,"ru").equals("/ru.png"),"localized PNG logo preferred");check(DockArtwork.logo(logos,"de").equals("/en.png"),"English logo fallback");check(DockArtwork.image("//../bad","w500").isEmpty(),"invalid image path rejected");
 TmdbClient.search=new JSONObject().put("results",items);TmdbClient.images=new JSONObject().put("logos",logos);DockArtwork found=DockArtwork.find("Film","https://mirror/b.jpg","2026");check(found.backdrop.endsWith("/w1280/wide.jpg")&&found.logo.endsWith("/w500/ru.png"),"portrait-only publisher gets matched landscape and logo");TmdbClient.failLogo=true;found=DockArtwork.find("Film","","2026");check(!found.backdrop.isEmpty()&&found.logo.isEmpty(),"logo endpoint failure preserves Seena backdrop");TmdbClient.key=false;check(DockArtwork.find("Film","","").backdrop.isEmpty(),"no key leaves published artwork usable");
 DockBanner b=new DockBanner();Backdrop backdrop=new Backdrop(1);b.show(new DockBackdrop.Artwork(backdrop,"Film",null),false);Canvas c=new Canvas();b.draw(c,1,1920);check(c.entries.size()==1&&c.entries.get(0).kind.equals("text")&&c.entries.get(0).r==0&&c.entries.get(0).color==0xFFFFFFFF,"title fallback uses white text only, no vignette/panel");check(c.entries.get(0).l==1880-4*18,"title right aligned at common screen inset");Bitmap logo=new Bitmap(7);logo.width=1000;logo.height=200;b.show(new DockBackdrop.Artwork(backdrop,"Film",logo),true);c=new Canvas();b.draw(c,1,1920);check(c.entries.size()==1&&c.entries.get(0).kind.equals("bitmap")&&c.entries.get(0).r==1880&&c.entries.get(0).r-c.entries.get(0).l<=420,"logo fits right-side bounds without stretching");b.show(null,false);c=new Canvas();b.draw(c,1,1920);check(c.entries.isEmpty(),"clearing artwork clears caption");System.out.println("PASS: exact artwork matching, ambiguous-title rejection, portrait fallback, logo language/failure, title and logo geometry");}
}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d);write(root)
 for name,text in f.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.DockArtworkHarness'],check=True,timeout=20)
