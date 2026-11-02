package io.tvos.artwork;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Comparator;

/** Bounded Dock image cache/decode; request lifetime and active-connection ownership belong to the caller. */
public final class DockImageLoader {
    public interface Requests {
        boolean active(int token);
        void opened(int token, HttpURLConnection connection);
        void closed(HttpURLConnection connection);
    }
    private final Context context;
    private final Requests requests;

    public DockImageLoader(Context context, Requests requests) {
        this.context = context;
        this.requests = requests;
    }

    public static String publishedImage(String url) {
        // Avoid fetching original-resolution TMDB artwork for a 1080p background.
        return url.startsWith("https://image.tmdb.org/t/p/original/")
                ? url.replace("/t/p/original/", "/t/p/w1280/") : url;
    }

    /** Input URLs have already passed publisher or metadata authorization in the caller. */
    public Bitmap read(String url, int token, boolean landscape, int timeout) throws IOException {
        File dir = new File(context.getCacheDir(), "dock-backdrops");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create backdrop cache");
        File file = new File(dir, PosterCache.cacheName(url));
        boolean downloaded = !file.isFile();
        if (downloaded) {
            File part = new File(file.getPath() + ".part");
            HttpURLConnection connection = null;
            try {
                InputStream stream;
                if (url.startsWith("https://")) {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
                    requests.opened(token, connection);
                    stream = connection.getInputStream();
                } else stream = context.getContentResolver().openInputStream(Uri.parse(url));
                if (stream == null) throw new IOException("No image stream");
                try (InputStream input = stream; FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16384]; long count = 0; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (!requests.active(token)) throw new IOException("Stale focus");
                        count += n; if (count > 16L * 1024 * 1024) throw new IOException("Image too large");
                        output.write(buffer, 0, n);
                    }
                }
                if (!part.renameTo(file)) throw new IOException("Cannot publish image");
            } finally { if (connection != null) { connection.disconnect(); requests.closed(connection); } part.delete(); }
        }
        file.setLastModified(System.currentTimeMillis());
        File[] files = downloaded ? dir.listFiles() : null;
        if (files != null) {
            java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = 6; i < files.length; i++) files[i].delete();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { file.delete(); return null; }
        if (landscape && (double) bounds.outWidth / bounds.outHeight < 1.2) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > (landscape ? 1920 : 600) || bounds.outHeight / options.inSampleSize > (landscape ? 1080 : 300)) options.inSampleSize *= 2;
        if (!requests.active(token)) return null;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }
}
