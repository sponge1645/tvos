package io.tvos;

import io.tvos.version.AppVersions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official stable releases only, with an exact compatible APK and mandatory published digest. */
final class TizenReleases {
    static final class Version {
        final String name;final AppDownloadCatalog.Source source;
        Version(String name,AppDownloadCatalog.Source source){this.name=name;this.source=source;}
    }
    private TizenReleases() { }
    static List<Version> load(String[] abis,BooleanSupplier cancelled) throws Exception {
        return parse(new JSONArray(ApkTransfer.json("https://api.github.com/repos/reisxd/TizenTubeCobalt/releases?per_page=50",cancelled)),abis,cancelled);
    }
    static List<Version> parse(JSONArray releases,String[] abis,BooleanSupplier cancelled) throws Exception {
        List<Version> result=new ArrayList<>();
        for(int n=0;n<releases.length();n++){
            ApkTransfer.checkCancelled(cancelled);JSONObject release=releases.getJSONObject(n);
            String tag=release.optString("tag_name");if(AppVersions.compare(tag,"0")==null||release.optBoolean("draft")||release.optBoolean("prerelease")||!tag.matches("v?[0-9]+(?:\\.[0-9]+){1,3}"))continue;
            JSONArray assets=release.optJSONArray("assets");if(assets==null)continue;
            AppDownloadCatalog.Source best=null;int bestScore=Integer.MIN_VALUE;
            for(int a=0;a<assets.length();a++){
                JSONObject asset=assets.getJSONObject(a);String file=asset.optString("name");
                if(!"cobalt-arm.apk".equals(file)&&!"cobalt-arm64.apk".equals(file))continue;
                int score=AppDownloadCatalog.assetScore(file,abis);if(score==Integer.MIN_VALUE||score<=bestScore)continue;
                String url=asset.optString("browser_download_url"),digest=asset.optString("digest");
                if(!url.equals("https://github.com/reisxd/TizenTubeCobalt/releases/download/"+tag+"/"+file)||!digest.matches("sha256:[0-9a-f]{64}"))continue;
                best=new AppDownloadCatalog.Source(url,digest.substring(7));bestScore=score;
            }
            if(best!=null)result.add(new Version(tag,best));
        }
        result.sort((a,b)->{Integer order=AppVersions.compare(b.name,a.name);return order==null?0:order;});
        return result;
    }
}
