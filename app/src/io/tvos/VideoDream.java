package io.tvos;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.util.TypedValue;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.HashSet;
import java.util.Collections;


/** Low-overhead screensaver video: SurfaceView + Android hardware decoder, no Canvas video frames. */
final class VideoDream extends FrameLayout implements SurfaceHolder.Callback {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> urls = new ArrayList<>();
    private final android.os.Bundle settings;
    private final SurfaceView surface;
    private ScreensaverFade sceneFade;
    private Runnable opaqueListener;
    static final long TRANSITION_MS = 600;
    private boolean exiting, entered;
    // TVOS_AERIAL_POLISH_V1: fade only video, keep weather and clock steady.
    private final View videoFade;
    private final GlassClockView clock;
    private final TextView date;
    // TVOS_AERIAL_OVERLAY_V1
    private final TextView aerialLocation;
    private final AerialWeatherView aerialWeather;
    private final HomeData weatherData;
    private MediaPlayer player;
    private Runnable prepareTimeout, fadeOut;
    private final Runnable retryPlayback = this::playNext;
    private int catalogGeneration;
    private DreamView fallback;
    private boolean running, surfaceReady, preparing;
    private int index, failures, generation;
    private boolean appleMode, loadingCatalog;
    private boolean showAerialClock() {
        return settings.getBoolean("aerial_show_clock", false);
    }
    private boolean showAerialLocation() {
        return settings.getBoolean("aerial_show_location", false);
    } // TVOS_AERIAL_CONTROLS_V1
    private static final String CATALOG = "https://sylvan.apple.com/Aerials/resources-atv-23J-2.tar";
    private static final String CACHE_NAME = "tvos_aerial_catalog_23J.json";
    private static final long CACHE_MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_CATALOG_BYTES = 12 * 1024 * 1024;
    private final java.util.HashMap<String, String> locations = new java.util.HashMap<>();

    private final Runnable updateClock = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
            updateAerialWeather();
            clock.setVisibility(fallback == null && showAerialClock() ? View.VISIBLE : View.GONE);
            // Date and weather are intentionally hidden in Aerial mode.
            handler.postDelayed(this, 10_000);
        }
    };

    VideoDream(Context context) {
        super(context);
        settings = ScreensaverSettingsProvider.read(context);
        setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(context);
        surface.getHolder().addCallback(this);
        addView(surface, new LayoutParams(-1, -1));
        videoFade = new View(context);
        videoFade.setBackgroundColor(Color.BLACK);
        videoFade.setAlpha(1f);
        addView(videoFade, new LayoutParams(-1, -1));
        clock = new GlassClockView(context);
        // SFPRO_AERIAL_TEXT
        clock.setTypeface(sfAerial(context, "sf_display_semibold.otf"));
        clock.setTextColor(0xF2FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 108);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(dp(8), 0, dp(3), 0x99000000); // TVOS_AERIAL_MINIMAL_V1
        clock.setGravity(Gravity.CENTER);
        // Android 11-compatible glass-like silver gradient.
        clock.getPaint().setShader(new LinearGradient(0, 0, 0, dp(145),
                new int[]{0xFFFDF6EC, 0xFFE6E4E1, 0xFF92969D, 0xFFF0D1B7},
                null, Shader.TileMode.CLAMP));
        LayoutParams cp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cp.setMargins(0, dp(55), 0, 0);
        addView(clock, cp);
        date = new TextView(context);
        date.setTypeface(sfAerial(context, "sf_display_regular.otf"));
        date.setTextColor(0xE6FFFFFF);
        date.setTextSize(20);
        date.setShadowLayer(5, 1, 2, Color.BLACK);
        date.setGravity(Gravity.CENTER);
        LayoutParams dp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dp.setMargins(0, dp(151), 0, 0);
        addView(date, dp);
        date.setVisibility(View.GONE);
        weatherData = HomeData.get(context);
        aerialLocation = new TextView(context);
        aerialLocation.setTypeface(sfAerial(context, "sf_display_light.otf"));
        aerialLocation.setTextColor(0xD9F2F2F2);
        aerialLocation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        // Rasterize the glyph shadow into a transparent text layer, not the video surface.
        // Padding keeps the blur away from the rectangular layer clip on TV firmware.
        aerialLocation.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        aerialLocation.setPadding(dp(10), dp(10), dp(10), dp(10));
        aerialLocation.setShadowLayer(dp(8), 0, dp(2), 0xE6000000);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        lp.setMargins(dp(38), 0, 0, dp(32));
        addView(aerialLocation, lp);
        aerialWeather = new AerialWeatherView(context);
        LayoutParams wp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        wp.setMargins(0, 0, dp(38), dp(32));
        addView(aerialWeather, wp);
        aerialWeather.setVisibility(View.GONE);
        setAlpha(0f);
        sceneFade = new ScreensaverFade(this);
        loadPlaylist();
    }

    /** Glass-like clock overlay that leaves the hardware-decoded Aerial video untouched.
     *  This is an optical glass approximation, not live video refraction. */
    private static final class GlassClockView extends TextView {

        GlassClockView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            // TextView draws the same text on the exact same baseline in each pass.
            Paint text = getPaint();
            Paint.Style originalStyle = text.getStyle();
            Shader originalShader = text.getShader();
            int originalColor = text.getColor();
            float originalStroke = text.getStrokeWidth();

            text.setShader(null);
            text.setStyle(Paint.Style.STROKE);
            text.setStrokeWidth(getResources().getDisplayMetrics().density * 2.4f);
            text.setColor(0x99FFFFFF);
            super.onDraw(canvas);

            text.setStyle(originalStyle);
            text.setStrokeWidth(originalStroke);
            text.setShader(originalShader);
            text.setColor(originalColor);
            super.onDraw(canvas);
        }
    }

    private static android.graphics.Typeface sfAerial(Context context, String name) {
        try { return android.graphics.Typeface.createFromAsset(context.getAssets(), "fonts/" + name); }
        catch (RuntimeException e) { return android.graphics.Typeface.DEFAULT; }
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private void loadPlaylist() {
        appleMode = "apple".equals(settings.getString("video_dream_source", "google"));
        // Google and National Geographic use DreamView's photo catalogs.
    }

    void start() {
        if (running) return;
        running = true;
        exiting = false;
        entered = false;
        sceneFade.reset(0f);
        weatherData.refreshIfStale();
        // Keep weather hidden until the first video and location fade in.
        aerialWeather.animate().cancel();
        aerialWeather.setAlpha(0f);
        updateClock.run();
        if (appleMode) loadAppleCatalog();
        else if (urls.isEmpty()) showFallback();
        else if (surfaceReady) playNext();
    }

    void useWindowAlpha(ScreensaverFade.Alpha target) {
        sceneFade.cancel();
        setAlpha(1f);
        target.set(0f);
        sceneFade = new ScreensaverFade(this, target, 0f);
    }

    void setOnOpaqueListener(Runnable listener) { opaqueListener = listener; }

    /** Called only after a first video frame or a photo/fallback frame is ready. */
    private void fadeEntrance() {
        if (!running || exiting || entered) return;
        entered = true;
        sceneFade.start(1f, TRANSITION_MS, () -> {
            if (running && !exiting && opaqueListener != null) opaqueListener.run();
        });
    }

    /** Use the same cover, duration and easing in both directions, including interrupted entry. */
    void fadeExit(Runnable finished) {
        if (exiting) return;
        if (!running) { finished.run(); return; }
        exiting = true;
        generation++;
        catalogGeneration++;
        handler.removeCallbacksAndMessages(null);
        videoFade.animate().cancel();
        aerialLocation.animate().cancel();
        aerialWeather.animate().cancel();
        sceneFade.cancel();
        // Reverse from the current whole-window opacity; never insert an opaque black frame.
        handler.postDelayed(finished, TRANSITION_MS + 150);
        sceneFade.start(0f, TRANSITION_MS, () -> {
            handler.removeCallbacks(finished);
            finished.run();
        });
    }

    void stop() {
        running = false;
        sceneFade.reset(0f);
        generation++;
        catalogGeneration++;
        loadingCatalog = false;
        handler.removeCallbacksAndMessages(null);
        videoFade.animate().cancel();
        aerialLocation.animate().cancel();
        aerialWeather.animate().cancel();
        aerialWeather.setAlpha(0f);
        videoFade.setAlpha(0f);
        aerialLocation.setAlpha(1f);
        releasePlayer();
        if (fallback != null) { fallback.stop(); removeView(fallback); fallback = null; }
    }

    private void loadAppleCatalog() {
        if (loadingCatalog) return;
        loadingCatalog = true;
        final int token = ++catalogGeneration;
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            ArrayList<String> found = new ArrayList<>();
            java.util.HashMap<String, String> titles = new java.util.HashMap<>();
            File cache = new File(app.getCacheDir(), CACHE_NAME);
            boolean fresh = cache.isFile() && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE;
            // Use cached metadata immediately when fresh; otherwise refresh over HTTPS.
            if (fresh) found = readCatalogFile(cache, titles);
            if (found.isEmpty()) {
                try {
                    byte[] bytes = downloadCatalog();
                    found = parseCatalog(new String(bytes, "UTF-8"), titles);
                    if (!found.isEmpty()) {
                        File tmp = new File(app.getCacheDir(), CACHE_NAME + ".tmp");
                        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); }
                        if (!tmp.renameTo(cache)) tmp.delete();
                    }
                } catch (Exception e) { AppLog.w("Aerial catalog: " + e.getMessage()); }
            }
            if (found.isEmpty() && cache.isFile()) found = readCatalogFile(cache, titles);
            final ArrayList<String> result = found;
            final java.util.HashMap<String, String> resultTitles = titles;
            handler.post(() -> {
                if (!running || exiting || token != catalogGeneration) return;
                loadingCatalog = false;
                urls.clear(); urls.addAll(result);
                locations.clear(); locations.putAll(resultTitles);
                Collections.shuffle(urls);
                index = 0; failures = 0;
                AppLog.w("Aerial catalog loaded: " + urls.size() + " clips");
                if (urls.isEmpty()) showFallback();
                else if (surfaceReady) playNext();
            });
        }, "tvos-aerial-catalog").start();
    }

    // Apple distributes entries.json inside an uncompressed POSIX tar archive.
    private static byte[] downloadCatalog() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(CATALOG).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() != 200)
                throw new java.io.IOException("catalog HTTP " + connection.getResponseCode());
            try (java.io.InputStream in = new java.io.BufferedInputStream(connection.getInputStream())) {
                byte[] header = new byte[512];
                while (true) {
                    readFully(in, header, 0, 512);
                    String name = new String(header, 0, 100, "UTF-8").trim();
                    if (name.length() == 0) break;
                    String octal = new String(header, 124, 12, "US-ASCII").trim().replace("\u0000", "").trim();
                    long size = Long.parseLong(octal, 8);
                    if (size < 0 || size > 50L * 1024 * 1024) throw new java.io.IOException("invalid tar size");
                    if (name.equals("entries.json") || name.equals("./entries.json")) {
                        if (size > MAX_CATALOG_BYTES) throw new java.io.IOException("entries.json too large");
                        byte[] json = new byte[(int) size];
                        readFully(in, json, 0, json.length);
                        return json;
                    }
                    long skip = ((size + 511) / 512) * 512;
                    while (skip > 0) {
                        long n = in.skip(skip);
                        if (n <= 0) { if (in.read() < 0) throw new java.io.EOFException(); n = 1; }
                        skip -= n;
                    }
                }
                throw new java.io.IOException("entries.json not found in tar");
            }
        } finally { connection.disconnect(); }
    }

    private static void readFully(java.io.InputStream in, byte[] bytes, int off, int len) throws java.io.IOException {
        while (len > 0) {
            int n = in.read(bytes, off, len);
            if (n < 0) throw new java.io.EOFException();
            off += n; len -= n;
        }
    }

    private static ArrayList<String> readCatalogFile(File file, java.util.HashMap<String, String> titles) {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_CATALOG_BYTES) return new ArrayList<>();
                out.write(buf, 0, n);
            }
            return parseCatalog(out.toString("UTF-8"), titles);
        } catch (Exception ignored) { return new ArrayList<>(); }
    }

    private static ArrayList<String> parseCatalog(String json, java.util.HashMap<String, String> titles) throws Exception {
        Object root = new JSONTokener(json).nextValue();
        ArrayList<String> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        collectEntries(root, result, seen, titles, 0);
        return result;
    }

    private static void collectEntries(Object node, ArrayList<String> result, HashSet<String> seen,
                                       java.util.HashMap<String, String> titles, int depth) {
        if (depth > 16 || result.size() >= 1000) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectEntries(a.opt(i), result, seen, titles, depth + 1);
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            // New Apple catalog: one entry has an assets array, each asset has url-1080-H264.
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                String place = getPlace(obj);
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String url = asset.optString("url-1080-H264", "");
                    if (url.isEmpty()) url = asset.optString("url", "");
                    if (url.startsWith("https://") && !url.contains(" ") && seen.add(url)) {
                        result.add(url);
                        String title = getPlace(asset);
                        if (title.isEmpty()) title = place;
                        if (!title.isEmpty()) titles.put(url, title);
                    }
                }
                return;
            }
            // Some releases put H264 URLs directly on entries.
            String direct = obj.optString("url-1080-H264", "");
            if (direct.startsWith("https://") && !direct.contains(" ") && seen.add(direct)) {
                result.add(direct);
                String title = getPlace(obj);
                if (!title.isEmpty()) titles.put(direct, title);
            }
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                Object child = obj.opt(keys.next());
                if (child instanceof JSONArray || child instanceof JSONObject)
                    collectEntries(child, result, seen, titles, depth + 1);
            }
        }
    }

    private static String getPlace(JSONObject obj) {
        for (String key : new String[]{"accessibilityLabel", "localizedName", "name", "title", "location"}) {
            Object value = obj.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) return ((String) value).trim();
            if (value instanceof JSONObject) {
                JSONObject v = (JSONObject) value;
                for (String lang : new String[]{"ru", "ru-RU", "en", "en-US"}) {
                    String title = v.optString(lang, "");
                    if (!title.isEmpty()) return title;
                }
            }
        }
        return "";
    }

    /** The same pre-rendered glyph shadow and 24 sp badge used by both photo sources. */
    private static final class AerialWeatherView extends View {
        private final ScreensaverWeather badge;
        private String symbol = "", degrees = "";

        AerialWeatherView(Context context) {
            super(context);
            badge = new ScreensaverWeather(context);
        }

        void setWeather(String symbol, String degrees) {
            if (this.symbol.equals(symbol) && this.degrees.equals(degrees)) return;
            this.symbol = symbol;
            this.degrees = degrees;
            badge.setWeather(symbol, degrees);
            requestLayout();
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            badge.setWeather(symbol, degrees);
            setMeasuredDimension(resolveSize(badge.width(), widthSpec), resolveSize(badge.height(), heightSpec));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!degrees.isEmpty()) badge.draw(canvas, 0, 0);
        }
    }

    // TVOS_WEATHER_FADE_V1: independent setting shared with photo screensaver.
    private boolean showScreensaverWeather() {
        return settings.getBoolean("screensaver_show_weather", false);
    }

    private void updateAerialWeather() {
        if (!appleMode || fallback != null || !showScreensaverWeather()) {
            aerialWeather.setVisibility(View.GONE);
            return;
        }
        HomeData.Weather wx = weatherData.weather;
        if (Double.isNaN(wx.temp)) {
            aerialWeather.setVisibility(View.GONE);
            return;
        }
        aerialWeather.setWeather(Symbols.weather(wx.code, wx.isDay), HomeData.degrees(wx.temp));
        // Weather must remain transparent until the video fade-in starts.
        aerialWeather.setVisibility(View.VISIBLE);
    }

    private static String aerialPlace(String url) {
        // Current verified test clip. Other clips need catalog location metadata.
        if (url.contains("KOREA_JAPAN_NIGHT")) return AppStrings.get(R.string.runtime_aerial_test_location);
        return "";
    }

    private void releasePlayer() {
        if (prepareTimeout != null) handler.removeCallbacks(prepareTimeout);
        if (fadeOut != null) handler.removeCallbacks(fadeOut);
        handler.removeCallbacks(retryPlayback);
        prepareTimeout = fadeOut = null;
        preparing = false;
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try { old.reset(); } catch (Exception ignored) {}
            try { old.release(); } catch (Exception ignored) {}
        }
    }

    private void showFallback() {
        if (!running || fallback != null) return;
        releasePlayer();
        surface.setVisibility(View.GONE);
        videoFade.setVisibility(View.GONE);
        clock.setVisibility(View.GONE);
        date.setVisibility(View.GONE);
        aerialLocation.setVisibility(View.GONE);
        aerialWeather.animate().cancel();
        aerialWeather.setAlpha(0f);
        aerialWeather.setVisibility(View.GONE);
        fallback = new DreamView(getContext(), settings);
        fallback.setOnContentReadyListener(this::fadeEntrance);
        addView(fallback, 0, new LayoutParams(-1, -1));
        fallback.start();
    }

    // Preserve TV media-stream routing pending device checks of AudioAttributes.
    @SuppressWarnings("deprecation")
    private void playNext() {
        if (!running || exiting || !surfaceReady || urls.isEmpty() || preparing || fallback != null) return;
        releasePlayer();
        preparing = true;
        final int token = ++generation;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDisplay(surface.getHolder());
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mp.setVolume(0, 0);
            // Scaling mode will be configured after preparation.
            mp.setScreenOnWhilePlaying(true);
            mp.setOnPreparedListener(p -> {
                AppLog.w("TVOS_STREAM_TEST prepared");
                if (!running || token != generation || p != player) return;
                try {
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    p.start();

                } catch (Exception e) { onFailure(token); }
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what != MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) return false;
                if (!running || exiting || token != generation || p != player) return true;
                if (prepareTimeout != null) handler.removeCallbacks(prepareTimeout);
                prepareTimeout = null;
                preparing = false;
                failures = 0;
                try {
                    if (appleMode) {
                        videoFade.animate().cancel();
                        if (!entered) videoFade.setAlpha(0f);
                        else videoFade.animate().alpha(0f).setDuration(850).start();
                        aerialLocation.animate().cancel();
                        if (!entered) aerialLocation.setAlpha(1f);
                        else aerialLocation.animate().alpha(1f).setDuration(850).start();
                        aerialWeather.animate().cancel();
                        if (aerialWeather.getVisibility() == View.VISIBLE) {
                            if (!entered) aerialWeather.setAlpha(1f);
                            else aerialWeather.animate().alpha(1f).setDuration(850).start();
                        }
                        int duration = p.getDuration();
                        if (duration > 1800) {
                            fadeOut = () -> {
                                if (running && token == generation && player == p && !preparing) {
                                    videoFade.animate().cancel();
                                    videoFade.animate().alpha(1f).setDuration(700).start();
                                    aerialLocation.animate().cancel();
                                    aerialLocation.animate().alpha(0f).setDuration(650).start();
                                    aerialWeather.animate().cancel();
                                    aerialWeather.animate().alpha(0f).setDuration(650).start();
                                }
                            };
                            handler.postDelayed(fadeOut, duration - 700L);
                        }
                    }
                    fadeEntrance();
                } catch (Exception e) { onFailure(token); }
                return true;
            });
            mp.setOnCompletionListener(p -> { if (running && token == generation && p == player) {
                if (appleMode) {
                    videoFade.animate().cancel();
                    videoFade.setAlpha(1f);
                    aerialLocation.animate().cancel();
                    aerialLocation.setAlpha(0f);
                    aerialWeather.animate().cancel();
                    aerialWeather.setAlpha(0f);
                }
                index++;
                if (index % urls.size() == 0) Collections.shuffle(urls);
                playNext();
            } });
            mp.setOnErrorListener((p, what, extra) -> {
                if (running && token == generation && p == player) {
                    AppLog.w("TVOS_STREAM_TEST error what=" + what + " extra=" + extra);
                    onFailure(token);
                }
                return true;
            });
            String currentUrl = urls.get(index % urls.size());
            String title = locations.get(currentUrl);
            aerialLocation.animate().cancel();
            if (appleMode) aerialLocation.setAlpha(0f);
            aerialLocation.setText(title != null ? title : aerialPlace(currentUrl));
            aerialLocation.setVisibility(appleMode && showAerialLocation() ? View.VISIBLE : View.GONE);
            aerialWeather.animate().cancel();
            aerialWeather.setAlpha(0f);
            updateAerialWeather();
            mp.setDataSource(getContext(), Uri.parse(currentUrl));
            mp.prepareAsync();
            prepareTimeout = () -> { if (running && preparing && token == generation) onFailure(token); };
            handler.postDelayed(prepareTimeout, 15000);
        } catch (Exception e) { AppLog.w("TVOS_STREAM_TEST exception: " + e); onFailure(token); }
    }

    private void onFailure(int token) {
        if (token != generation || !running) return;
        releasePlayer();
        if (appleMode) {
            videoFade.animate().cancel();
            videoFade.setAlpha(1f);
            aerialLocation.animate().cancel();
            aerialLocation.setAlpha(0f);
            aerialWeather.animate().cancel();
            aerialWeather.setAlpha(0f);
        }
        index++;
        failures++;
        if (failures >= urls.size()) {
            AppLog.w("Aerial: all " + urls.size() + " videos failed");
            showFallback(); return;
        }
        handler.postDelayed(retryPlayback, 300);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (running && fallback == null) playNext();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        generation++;
        videoFade.animate().cancel();
        aerialLocation.animate().cancel();
        releasePlayer();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
}
