"""Public-album validation, host restrictions and photo-only largest-derivative selection."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import JSON,ARRAY
json=JSON.replace('private final Map<String,Object>', '''public JSONObject(){}public JSONObject(String raw){}public boolean has(String k){return m.containsKey(k);}public java.util.Iterator<String> keys(){return m.keySet().iterator();}public String getString(String k){return optString(k);}public JSONObject getJSONObject(String k){return optJSONObject(k);}private final Map<String,Object>''')
array=ARRAY.replace('public String optString(', 'public String getString(int i){return optString(i,"");}public String optString(')
harness='''package io.tvos.icloud;import org.json.*;import java.util.*;public class AlbumHarness {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
 String link="https://www.icloud.com/sharedalbum/#D2LExampleAlbum123";
 check(ICloudAlbum.host(ICloudAlbum.token(link)).equals("p145-sharedstreams.icloud.com"),"base62 partition");
 for(String invalid:new String[]{"http://www.icloud.com/sharedalbum/#D2LExampleAlbum123","https://evil.test/sharedalbum/#D2LExampleAlbum123","https://www.icloud.com@evil.test/sharedalbum/#D2LExampleAlbum123","https://www.icloud.com:443/sharedalbum/#D2LExampleAlbum123","https://www.icloud.com/sharedalbum/#../bad","https://www.icloud.com/photos/"})try{ICloudAlbum.token(invalid);throw new AssertionError("invalid link accepted");}catch(java.io.IOException expected){}
 check(ICloudAlbum.imageUrl("https://cvws.icloud-content.com/path?signature=example")&&!ICloudAlbum.imageUrl("https://icloud-content.com.evil.test/path")&&!ICloudAlbum.imageUrl("http://cvws.icloud-content.com/path")&&!ICloudAlbum.imageUrl("https://cvws.icloud-content.com:444/path"),"CDN host and HTTPS allowlist");
 JSONObject small=new JSONObject().put("width",350).put("height",257).put("checksum","small");
 JSONObject large=new JSONObject().put("width",1536).put("height",2096).put("checksum","large");
 JSONObject image=new JSONObject().put("photoGuid","private-guid").put("derivatives",new JSONObject().put("small",small).put("large",large));
 JSONObject video=new JSONObject().put("photoGuid","video-guid").put("mediaAssetType","video").put("derivatives",new JSONObject().put("small",small));
 List<String> paths=new ArrayList<>();List<ICloudAlbum.Photo> photos=ICloudAlbum.photos(link,12,(host,token,path,body)->{paths.add(path);if(path.equals("webstream"))return new JSONObject().put("photos",new JSONArray().put(image).put(video));check(body.getJSONArray("photoGuids").length()==1&&body.getJSONArray("photoGuids").getString(0).equals("private-guid"),"videos excluded before URL resolution");return new JSONObject().put("locations",new JSONObject().put("cdn",new JSONObject().put("scheme","https").put("hosts",new JSONArray().put("cvws.icloud-content.com")))).put("items",new JSONObject().put("large",new JSONObject().put("url_location","cdn").put("url_path","/photo.jpg")));});
 check(paths.equals(Arrays.asList("webstream","webasseturls"))&&photos.size()==1&&photos.get(0).url.endsWith("/photo.jpg")&&photos.get(0).id.equals(ICloudAlbum.key("private-guid")),"largest portrait image and stable private cache identifier");
 try{ICloudAlbum.photos(link,1,(h,t,p,b)->new JSONObject());throw new AssertionError("empty album accepted");}catch(java.io.IOException expected){}
 System.out.println("PASS: public iCloud links, partition, CDN restrictions, video exclusion, portrait derivatives and stable cache IDs");
 }
}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 sources={'org/json/JSONObject.java':json,'org/json/JSONArray.java':array,'org/json/JSONException.java':'package org.json;public class JSONException extends Exception {public JSONException(String s){super(s);}}','io/tvos/icloud/ICloudAlbum.java':Path('app/src/io/tvos/icloud/ICloudAlbum.java').read_text(),'io/tvos/icloud/AlbumHarness.java':harness}
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.icloud.AlbumHarness'],check=True)
