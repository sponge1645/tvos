package io.tvos;

import io.tvos.icloud.ICloudAlbum;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Chromecast archive and openly licensed Wikimedia nature photography; offline image cache. */
final class GalleryPhotos {
    private final String source, album;
    private volatile boolean closed;
    private volatile long cloudRefreshed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final GalleryCache cache;
    private final File folder;
    private boolean loading;

    GalleryPhotos(Context context, String source) {
        this(context,source,"");
    }
    GalleryPhotos(Context context, String source, String album) {
        this.source = source;this.album=album;
        folder = new File(context.getCacheDir(), "tvos_gallery_v2_" + source + ("icloud".equals(source) ? "_" + ICloudAlbum.key(album) : ""));
        if ("icloud".equals(source)) {
            File[] old=context.getCacheDir().listFiles((dir,name) -> name.startsWith("tvos_gallery_v2_icloud_") && !name.equals(folder.getName()));
            if(old!=null)for(File dir:old){File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
        }
        cache = new GalleryCache(folder,GalleryCache.MAX_PHOTOS,GalleryCache.MAX_BYTES,"icloud".equals(source)?1000:80_000);
    }

    boolean hasImages() { return cache.hasImages(); }
    File next() { return cache.next(); }
    void release(File file) { cache.release(file); }

    void refresh(Runnable onReady) {
        if (loading || closed || ("icloud".equals(source) && cache.hasImages() && System.currentTimeMillis()-cloudRefreshed < 10*60_000L)) return;
        loading = true;
        io.execute(() -> {
            try {
                ArrayList<Photo> photos;
                if("icloud".equals(source)){photos=new ArrayList<>();for(ICloudAlbum.Photo p:ICloudAlbum.photos(album,12))photos.add(new Photo(p.url,null,p.id));cloudRefreshed=System.currentTimeMillis();}
                else photos = "na".equals(source) ? commons() : chromecast();
                Collections.shuffle(photos);
                int added = 0;
                for (Photo photo : photos) {
                    if (Thread.currentThread().isInterrupted() || added >= 12) break;
                    String id = photo.id == null ? Integer.toHexString(photo.url.hashCode()) : photo.id;
                    File file = new File(folder, id + ".jpg");
                    if (!file.isFile() || file.length() < ("icloud".equals(source)?1000:80_000)) {
                        File temp = new File(folder, id + ".tmp");
                        try {
                            if("icloud".equals(source))ICloudAlbum.download(photo.url,temp);else download(photo.url, temp);
                            int[] size = dimensions(temp);
                            float ratio = size[1] == 0 ? 0 : (float)size[0] / size[1];
                            if ("icloud".equals(source) ? size[0] < 200 || size[1] < 200 : size[0] < 1600 || size[1] < 900 || ratio < 1.35f || ratio > 2.3f)
                                throw new IOException("Unsuitable image dimensions");
                            if (!temp.renameTo(file)) throw new IOException("Rename failed");
                            if (photo.metadata != null) {
                                try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(folder, id + ".json")), "UTF-8")) {
                                    w.write(photo.metadata.toString());
                                }
                            }
                        } catch (Exception e) { temp.delete(); continue; }
                    }
                    added++;
                    cache.prune();
                    main.post(() -> {
                        if (!closed && cache.add(file) && onReady != null) onReady.run();
                    });
                }
                cache.prune();
            } catch (Exception e) { AppLog.w("gallery " + source + ": " + e.getClass().getSimpleName()); }
            finally { main.post(() -> loading = false); }
        });
    }
    void close() { closed=true;io.shutdownNow(); }

    private static final class Photo {
        final String url;
        final JSONObject metadata;
        final String id;
        Photo(String url, JSONObject metadata) { this(url,metadata,null); }
        Photo(String url, JSONObject metadata,String id) { this.url = url; this.metadata = metadata; this.id=id; }
    }

    private ArrayList<Photo> chromecast() throws Exception {
        // RSS is the public feed exposed by luke.lol; parse enclosure/media URLs rather than scraping HTML.
        HttpURLConnection c = connect("https://luke.lol/chromecast_rss.php");
        ArrayList<Photo> result = new ArrayList<>();
        try (InputStream stream = c.getInputStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Some Android XML providers do not support these optional features.
            // Keep RSS parsing working while disabling external entities where supported.
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (javax.xml.parsers.ParserConfigurationException ignored) { }
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (javax.xml.parsers.ParserConfigurationException ignored) { }
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (javax.xml.parsers.ParserConfigurationException ignored) { }
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new LimitedInputStream(stream, 3_000_000));
            NodeList nodes = doc.getElementsByTagName("item");
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node item = nodes.item(i);
                NodeList children = item.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    String local = child.getLocalName();
                    if (local == null) local = child.getNodeName();
                    if (!("enclosure".equals(local) || "content".equals(local) || "thumbnail".equals(local))) continue;
                    NamedNodeMap attrs = child.getAttributes();
                    Node attr = attrs == null ? null : attrs.getNamedItem("url");
                    if (attr == null) continue;
                    String url = attr.getNodeValue();
                    if (validImageUrl(url) && seen.add(url)) result.add(new Photo(url, null));
                }
            }
        } finally { c.disconnect(); }
        if (result.isEmpty()) throw new IOException("Chromecast RSS contains no image enclosures");
        return result;
    }

    private ArrayList<Photo> commons() throws Exception {
        String[] categories = {
            "Category:Featured pictures of mammals",
            "Category:Featured pictures of birds",
            "Category:Featured pictures of landscapes",
            "Category:Featured pictures of nature"
        };
        ArrayList<Photo> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String category : categories) {
            try {
                String params = "action=query&format=json&generator=categorymembers&gcmtype=file&gcmlimit=100"
                    + "&gcmtitle=" + URLEncoder.encode(category, "UTF-8")
                    + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=2560";
                JSONObject json = new JSONObject(read("https://commons.wikimedia.org/w/api.php?" + params, 3_000_000));
                JSONObject pages = json.optJSONObject("query");
                if (pages == null) continue;
                pages = pages.optJSONObject("pages");
                if (pages == null) continue;
                Iterator<String> keys = pages.keys();
                while (keys.hasNext()) {
                    JSONObject page = pages.optJSONObject(keys.next());
                    if (page == null) continue;
                    JSONArray infos = page.optJSONArray("imageinfo");
                    if (infos == null || infos.length() == 0) continue;
                    JSONObject info = infos.optJSONObject(0);
                    if (info == null) continue;
                    int width = info.optInt("width"), height = info.optInt("height");
                    if (width < 1920 || height < 1080 || (float)width / height < 1.35f || (float)width / height > 2.3f) continue;
                    String url = info.optString("thumburl", info.optString("url", ""));
                    if (!validImageUrl(url) || !seen.add(url)) continue;
                    JSONObject meta = info.optJSONObject("extmetadata");
                    String license = meta == null ? "" : meta.optJSONObject("LicenseShortName") == null ? "" : meta.optJSONObject("LicenseShortName").optString("value");
                    if (!(license.startsWith("CC BY") || license.equals("CC0") || license.equals("Public domain"))) continue;
                    JSONObject credits = new JSONObject();
                    credits.put("title", page.optString("title"));
                    credits.put("page", info.optString("descriptionurl"));
                    credits.put("license", license);
                    if (meta != null) {
                        JSONObject artist = meta.optJSONObject("Artist");
                        JSONObject licenseUrl = meta.optJSONObject("LicenseUrl");
                        credits.put("artist_html", artist == null ? "" : artist.optString("value"));
                        credits.put("license_url", licenseUrl == null ? "" : licenseUrl.optString("value"));
                    }
                    result.add(new Photo(url, credits));
                }
            } catch (Exception e) { AppLog.w("commons category: " + e); }
        }
        if (result.isEmpty()) throw new IOException("No eligible Commons photos");
        return result;
    }

    private static boolean validImageUrl(String url) {
        try {
            URI u = new URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme())) return false;
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return host.equals("luke.lol") || host.equals("thumb.wikimedia.org")
                || host.equals("upload.wikimedia.org") || host.equals("ccp-lh.googleusercontent.com");
        } catch (Exception e) { return false; }
    }
    private static int[] dimensions(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return new int[] {o.outWidth, o.outHeight};
    }
    private static HttpURLConnection connect(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "tvOS-Screensaver/1.0 (Android TV personal project)");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code / 100 != 2) { c.disconnect(); throw new IOException("HTTP " + code); }
        return c;
    }
    private static String read(String url, int max) throws Exception {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > max) throw new IOException("Response too large"); out.write(b, 0, n); }
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
    private static void download(String url, File file) throws Exception {
        if (!validImageUrl(url)) throw new IOException("Unsupported image host");
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            byte[] b = new byte[16384]; int n, total = 0;
            while ((n = in.read(b)) != -1) { total += n; if (total > 12_000_000) throw new IOException("Image too large"); out.write(b, 0, n); }
            if (total < 80_000) throw new IOException("Image too small");
        } finally { c.disconnect(); }
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining;
        LimitedInputStream(InputStream in, int max) { super(in); remaining = max; }
        @Override public int read() throws IOException {
            if (remaining <= 0) throw new IOException("Feed too large");
            int n = super.read(); if (n >= 0) remaining--; return n;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("Feed too large");
            int n = super.read(b, off, Math.min(len, remaining)); if (n > 0) remaining -= n; return n;
        }
    }
}
