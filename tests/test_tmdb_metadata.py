"""Exercise production TMDB response mapping independently of workers, caches and Android."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import files
harness='''package io.tvos;
import java.util.*;import org.json.*;import io.tvos.Recs.Pick;
public class MetadataHarness {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static Pick pick(String kind){Pick p=new Pick();p.id=42;p.kind=kind;p.title="Title";p.backdrop="/old.jpg";p.poster="/poster.jpg";p.rating=7.2;return p;}
 static JSONObject country(String code){return new JSONObject().put("iso_3166_1",code);}
 static JSONObject image(String path,int width,Object language,double rating){return new JSONObject().put("file_path",path).put("width",width).put("iso_639_1",language).put("vote_average",rating);}
 static JSONObject details(){return new JSONObject().put("release_date","2020-01-02").put("runtime",125).put("genres",new JSONArray().put(new JSONObject().put("name","Drama"))).put("production_countries",new JSONArray().put(country("US")));}
 static JSONObject provider(String name){return new JSONObject().put("provider_name",name);}
 public static void main(String[] args)throws Exception{
  Locale.setDefault(new Locale("ru","RU"));Ui.language("en");
  List<Pick> catalog=new ArrayList<>();
  for(String path:new String[]{"","null"})TmdbMetadata.add(catalog,new JSONObject().put("backdrop_path",path),"movie");
  check(catalog.isEmpty(),"catalog rejects missing and literal-null backdrop");
  JSONObject item=new JSONObject().put("id",42).put("media_type","movie").put("title","Localized").put("original_title","Original").put("poster_path","/p.jpg").put("release_date","2020-01-02");
  Pick search=TmdbMetadata.search(item);
  check(search!=null&&search.originalTitle.equals("Original")&&search.backdrop.isEmpty()&&search.movieOrigin.equals("2020"),"search allows no backdrop and retains original title/year");
  TmdbMetadata.add(catalog,item,"movie");check(catalog.isEmpty(),"search and catalog retain distinct filters");
  item.put("backdrop_path","/b.jpg");TmdbMetadata.add(catalog,item,"movie");
  check(catalog.size()==1&&catalog.get(0).title.equals("Localized")&&catalog.get(0).movieOrigin.equals("2020"),"catalog fields mapped");
  item.put("media_type","person");check(TmdbMetadata.search(item)==null,"search skips people");item.put("media_type","movie").put("poster_path","");check(TmdbMetadata.search(item)==null,"search requires poster");
  JSONObject tv=new JSONObject().put("media_type","tv").put("id",8).put("name","Series").put("original_name","Original series").put("poster_path","/tv.jpg").put("first_air_date","2019-05-06");
  search=TmdbMetadata.search(tv);check(search.kind.equals("tv")&&search.title.equals("Series")&&search.originalTitle.equals("Original series")&&search.tvOrigin.equals("2019"),"series search mapping");
  check(TmdbMetadata.countryName("us").equals("USA")&&TmdbMetadata.countryName("GB").equals("UK")&&TmdbMetadata.countryName("FR").equals("France"),"app language overrides system country locale");
  check(TmdbMetadata.countryName(null).isEmpty()&&TmdbMetadata.countryName("USA").isEmpty(),"invalid country codes ignored");
  check(TmdbMetadata.movieOrigin(new JSONObject().put("production_countries",new JSONArray().put(country("GB"))),"France, 1999 г.").equals("1999 · UK"),"cached year survives incomplete details");
  check(TmdbMetadata.movieOrigin(new JSONObject().put("release_date","2021").put("production_countries",new JSONArray().put("bad")),"").equals("2021"),"malformed country record preserves year");
  Pick p=pick("movie");JSONObject d=details();TmdbMetadata.applyDetails(p,d,"RU",pkg->false);
  check(p.movieOrigin.equals("2020 · USA")&&p.duration.equals("125 min")&&p.meta.equals("Drama · 2h 5m")&&p.detailed,"English movie metadata");
  check(p.rating==7.2&&p.poster.equals("/poster.jpg")&&p.backdrop.equals("/old.jpg"),"missing detail fields retain previous art/rating");
  d.put("images",new JSONObject().put("backdrops",new JSONArray().put("bad").put(image("",1920,JSONObject.NULL,10)).put(image("/text.jpg",1920,"en",10)).put(image("/large.jpg",3840,JSONObject.NULL,10)).put(image("/best.jpg",1920,JSONObject.NULL,1)).put(image("/small.jpg",1280,JSONObject.NULL,10))));
  d.put("vote_average",8.1).put("poster_path","null");TmdbMetadata.applyDetails(p,d,"RU",pkg->false);
  check(p.backdrop.equals("/best.jpg")&&p.rating==8.1&&p.poster.equals("/poster.jpg"),"backdrop ranking and literal-null poster handling preserved");
  JSONObject ru=new JSONObject().put("flatrate",new JSONArray().put(provider("Cinemax")).put(provider("Netflix")).put(provider("Max")));
  JSONObject us=new JSONObject().put("flatrate",new JSONArray().put(provider("Netflix")));
  d.put("watch/providers",new JSONObject().put("results",new JSONObject().put("RU",ru).put("US",us)));
  List<String> checked=new ArrayList<>();p=pick("movie");TmdbMetadata.applyDetails(p,d,"RU",pkg->{checked.add(pkg);return pkg.equals("com.wbd.stream");});
  check(p.app.equals("max")&&p.pkg.equals("com.wbd.stream")&&p.meta.endsWith(" · Max"),"first supported installed provider in selected region");
  check(checked.equals(Arrays.asList("com.netflix.ninja","com.wbd.stream")),"Max matches whole word and unavailable service skipped");
  p=pick("movie");TmdbMetadata.applyDetails(p,d,"US",pkg->true);check(p.app.equals("netflix"),"requested region selected");
  checked.clear();TmdbMetadata.applyDetails(p,d,"RU",pkg->{checked.add(pkg);return true;});
  check(p.app.equals("netflix")&&checked.isEmpty(),"existing provider identity retained");
  check(TmdbMetadata.serviceName(null).isEmpty()&&TmdbMetadata.serviceName(p).equals("Netflix"),"service display name unchanged");
  Ui.language("ru");p=pick("movie");JSONObject russianDetails=details().put("genres",new JSONArray().put(new JSONObject().put("name","Драма")));TmdbMetadata.applyDetails(p,russianDetails,"RU",pkg->false);
  check(p.movieOrigin.equals("2020 · США")&&p.duration.equals("125 мин")&&p.meta.equals("Драма · 2 ч. 5 мин"),"Russian genre/runtime/origin formatting");
  JSONObject series=new JSONObject().put("first_air_date","2019-01-01").put("origin_country",new JSONArray().put("KR")).put("number_of_seasons",2).put("genres",new JSONArray().put(new JSONObject().put("name","Action & Adventure")));
  p=pick("tv");TmdbMetadata.applyDetails(p,series,"RU",pkg->false);
  check(p.tvOrigin.equals("2019 · Южная Корея")&&p.meta.endsWith("2 сезона"),"Russian series country/year/plural");
  for(String language:new String[]{"en"}){
   Ui.language(language);p=pick("tv");TmdbMetadata.applyDetails(p,series,"RU",pkg->false);
   check(p.meta.endsWith(language.equals("en")?"2 seasons":language.equals("de")?"2 Staffeln":"2 temporadas"),"season count "+language);
  }
  Ui.language("en");p=pick("tv");p.tvOrigin="2022 · UK";TmdbMetadata.applyDetails(p,new JSONObject(),"RU",pkg->false);
  check(p.tvOrigin.equals("2022 · UK"),"missing series details preserve origin");
  p=pick("movie");TmdbMetadata.applyDetails(p,new JSONObject(),"RU",pkg->false);check(p.duration.isEmpty(),"unknown duration remains empty");
  System.out.println("PASS: TMDB catalog/search filters, original titles, countries/years, localized details, backdrop ranking, providers and missing-field fallbacks");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-metadata-') as tmp:
 root=Path(tmp);sources=files();sources['io/tvos/MetadataHarness.java']=harness
 for name,content in sources.items():
  path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.MetadataHarness'],check=True)
