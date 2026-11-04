package io.tvos;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.tvos.artwork.PosterCache;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;

/** TV search and title page. Android owns keyboard input; movie apps retain playback ownership. */
public final class SearchActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    // A slow suggestions request must not hold up an opened title or KinoPub resolution.
    private final ExecutorService metadataIo = Executors.newFixedThreadPool(2);
    private final ExecutorService artworkIo = Executors.newSingleThreadExecutor();
    private final List<Art> artwork = new ArrayList<>();
    private final List<Recs.Pick> trending = new ArrayList<>();
    private final List<LocalApp> apps = new ArrayList<>();
    private FrameLayout root, detail;
    private LinearLayout screen, results;
    private EditText query;
    private View returnFocus;
    private PosterCache images;
    private SearchController search;
    private MovieAppLauncher launcher;
    private RemoteShortcuts shortcuts;
    private SharedPreferences history;
    private Future<?> detailWork;
    private boolean running, closed, focusAfterSearch, initialInput = true;
    private int detailGeneration;
    private float scale;
    private boolean night;
    private Bitmap pageBackdrop;
    private final Scene buttonScene = new Scene();
    private final Shadows shadows = new Shadows();
    private final io.tvos.artwork.RoundedImages roundedImages = new io.tvos.artwork.RoundedImages(Ui.LEGACY);
    private int ink() { return detail != null || night ? 0xFFF4F4F6 : 0xFF1D1D1F; }
    private int muted() { return detail != null || night ? 0xFFB4B8BF : 0xFF626873; }
    private String problem = "";
    private List<Recs.Pick> matches = new ArrayList<>();

    private static final class LocalApp {
        final String pkg, label;
        LocalApp(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }
    private int px(float value) { return Math.max(1, Math.round(value * scale)); }
    private String text(int id) { return AppStrings.get(id); }
    private void toast(int id) { Toast.makeText(this, text(id), Toast.LENGTH_SHORT).show(); }
    private LinearLayout column() { LinearLayout out = new LinearLayout(this); out.setOrientation(LinearLayout.VERTICAL); out.setClipChildren(false); return out; }
    private TextView label(String value, float size) {
        TextView out = new TextView(this); out.setText(value); out.setTextColor(ink());
        out.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size)); out.setTypeface(Ui.medium);
        out.setIncludeFontPadding(false); return out;
    }
    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable out = new GradientDrawable(); out.setColor(color); out.setCornerRadius(px(radius)); return out;
    }
    private void focusable(View view, boolean grow) {
        view.setFocusable(true); view.setClickable(true); view.setDefaultFocusHighlightEnabled(false);
        view.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel(); v.animate().scaleX(focused && grow ? 1.07f : 1).scaleY(focused && grow ? 1.07f : 1).setDuration(180).start();
            if (v instanceof ProfileButton) {
                ((ProfileButton) v).setTextColor(focused ? 0xFF17191E : ink());
                v.invalidate();
            } else if (v instanceof Art) {
                ((Art) v).focused = focused;
                v.setElevation(focused ? px(14) : 0); v.invalidate();
            }
            windowImages();
        });
    }
    private TextView button(String value, Runnable action) {
        TextView out = new ProfileButton(value); out.setGravity(Gravity.CENTER);
        out.setPadding(px(26), 0, px(26), 0);
        out.setMaxLines(1); out.setEllipsize(TextUtils.TruncateAt.END);
        focusable(out, true); out.setOnClickListener(v -> action.run()); return out;
    }
    /** The same ActionSurface used by the avatar chooser, at a smaller native-view size. */
    private final class ProfileButton extends TextView {
        private final ActionSurface surface = new ActionSurface(buttonScene, shadows);
        private final RectF rect = new RectF();
        ProfileButton(String value) {
            super(SearchActivity.this); setText(value); setTextColor(ink()); setTypeface(Ui.displayMedium);
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28)); setIncludeFontPadding(false);
        }
        @Override protected void onDraw(Canvas c) {
            if (hasFocus()) {
                int saved = c.save(); c.scale(scale, scale);
                float width = getWidth()/scale, height = getHeight()/scale;
                float insetX = width * (1 - 1/1.1f)/2, insetY = height * (1 - 1/1.1f)/2;
                rect.set(insetX, insetY, width - insetX, height - insetY);
                surface.draw(c, rect, 1, night ? 1 : 0, 1); c.restoreToCount(saved);
            }
            super.onDraw(c);
        }
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); Ui.init(this);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        night = HomeData.get(this).isNight(System.currentTimeMillis());
        int[] frost = getIntent().getIntArrayExtra("profile_frost"); getIntent().removeExtra("profile_frost");
        pageBackdrop = ProfileActivity.backdrop(frost, night);
        int[] pixels = new int[Backdrop.FW * Backdrop.FH]; pageBackdrop.getPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        buttonScene.setFrost(pixels);
        Bitmap glass = Bitmap.createScaledBitmap(pageBackdrop, Backdrop.GW, Backdrop.GH, true);
        int[] glassPixels = new int[Backdrop.GW * Backdrop.GH]; glass.getPixels(glassPixels, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        buttonScene.setGlassImage(glassPixels); glass.recycle();
        root = new FrameLayout(this); setContentView(root);
        View background = new View(this) {
            private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            private final Rect target = new Rect();
            @Override protected void onDraw(Canvas c) { target.set(0, 0, getWidth(), getHeight()); c.drawBitmap(pageBackdrop, null, target, paint); }
        };
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        images = new PosterCache(this, () -> main.post(() -> {
            if (!closed) for (Art art : artwork) art.invalidate();
        }), artworkIo, AppLog::w);
        images.setWindow(new HashSet<>());
        history = getSharedPreferences("search-history", MODE_PRIVATE);
        search = new SearchController(main, Recs.get(this), () -> running && detail == null,
                found -> { matches = found; problem = found.isEmpty() ? text(R.string.search_empty) : ""; render(); if (focusAfterSearch) { focusAfterSearch = false; focusResult(); } });
        launcher = new MovieAppLauncher(this, root, metadataIo, main, new MovieAppLauncher.Host() {
            public boolean running() { return running && detail != null; }
            public int epoch() { return detailGeneration; }
            public void toast(String value) { Toast.makeText(SearchActivity.this, value, Toast.LENGTH_SHORT).show(); }
        });
        shortcuts = new RemoteShortcuts(this, true, null);
        screen = column(); screen.setPadding(px(60), px(50), px(60), px(24));
        ScrollView page = new ScrollView(this); page.setFillViewport(true); page.setClipChildren(false); page.setClipToPadding(false); page.setVerticalScrollBarEnabled(false);
        page.addView(screen, new ScrollView.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        View magnifier = new View(this) {
            @Override protected void onDraw(Canvas c) { Symbols.draw(SearchActivity.this, c, "search", getWidth()/2f, getHeight()/2f, 42*scale, muted()); }
        };
        magnifier.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(magnifier, new LinearLayout.LayoutParams(px(64), px(64)));
        query = new EditText(this, null, 0, R.style.SearchPageInput); query.setDefaultFocusHighlightEnabled(false); query.setSingleLine(true); query.setTypeface(Ui.displaySemibold);
        query.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(44)); query.setTextColor(ink()); query.setHintTextColor(muted());
        query.setHint(text(R.string.search_hint)); query.setBackgroundColor(0x00000000);
        query.setPadding(px(32), 0, px(20), 0); query.setSelectAllOnFocus(false);
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT); query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        header.addView(query, new LinearLayout.LayoutParams(0, px(84), 1)); screen.addView(header);
        View divider = new View(this); divider.setBackgroundColor(night ? 0x26FFFFFF : 0x261D1D1F);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, px(1)); line.topMargin = px(26);
        screen.addView(divider, line);
        results = column(); results.setPadding(0, px(12), 0, px(40));
        screen.addView(results, new LinearLayout.LayoutParams(-1, -2));
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void afterTextChanged(Editable value) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                focusAfterSearch = false; matches = new ArrayList<>(); problem = ""; search.changed(s.toString().trim()); render();
            }
        });
        query.setOnClickListener(v -> keyboard());
        query.setOnEditorActionListener((v, action, event) -> { focusAfterSearch = true; search.flush(); remember(); hideKeyboard(); focusResult(); return true; });
        root.getViewTreeObserver().addOnScrollChangedListener(this::windowImages);
        if (saved != null) query.setText(saved.getString("query", ""));
        render(); query.requestFocus();
        loadSuggestions();
    }
    private void keyboard() {
        focusAfterSearch = false; query.requestFocus(); ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }
    private void hideKeyboard() { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0); }
    private void focusResult() { results.post(() -> { if (detail == null) results.requestFocus(View.FOCUS_DOWN); }); }
    private List<String> recent() {
        List<String> out = new ArrayList<>();
        try { JSONArray list = new JSONArray(history.getString("recent", "[]")); for (int i = 0; i < list.length(); i++) out.add(list.optString(i)); }
        catch (org.json.JSONException e) { AppLog.w("Invalid search history"); }
        return SearchHistory.remember(out, "");
    }
    private void remember() {
        String value = query.getText().toString().trim();
        if (!value.isEmpty()) history.edit().putString("recent", new JSONArray(SearchHistory.remember(recent(), value)).toString()).apply();
    }
    private void heading(int id) {
        TextView title = label(text(id), 30); title.setTextColor(muted());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = px(26); params.bottomMargin = px(20); results.addView(title, params);
    }
    private LinearLayout row(int height) {
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); scroll.setClipChildren(false); scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setClipChildren(false); row.setPadding(px(14), px(22), px(20), px(24));
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        results.addView(scroll, new LinearLayout.LayoutParams(-1, px(height))); return row;
    }
    private void chip(LinearLayout row, String title, Runnable action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, px(66)); params.rightMargin = px(28); row.addView(button(title, action), params);
    }
    private void setQuery(String value) { query.setText(value); query.setSelection(query.length()); hideKeyboard(); focusAfterSearch = true; search.flush(); }
    private void render() {
        if (closed || detail != null) return;
        artwork.clear(); results.removeAllViews();
        String value = query.getText().toString().trim();
        if (value.isEmpty()) {
            List<String> recent = recent();
            if (!recent.isEmpty()) {
                LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = label(text(R.string.search_recent), 30); title.setTextColor(muted());
                header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                TextView clear = button(text(R.string.search_clear), () -> {
                    history.edit().remove("recent").apply(); render(); query.requestFocus();
                });
                header.addView(clear, new LinearLayout.LayoutParams(-2, px(58)));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = px(16); results.addView(header, params);
                LinearLayout row = row(100); for (String item : recent) chip(row, item, () -> setQuery(item));
            }
            if (!trending.isEmpty()) { heading(R.string.search_trending); posters(trending); }
        } else {
            List<LocalApp> found = new ArrayList<>();
            String lower = value.toLowerCase(Ui.locale());
            for (LocalApp app : apps) if (app.label.toLowerCase(Ui.locale()).contains(lower)) found.add(app);
            if (!found.isEmpty()) { heading(R.string.search_apps); LinearLayout row = row(116); for (LocalApp app : found) chip(row, app.label,
                    () -> { remember(); Intent intent = HomeApps.launchIntent(getPackageManager(), app.pkg); if (intent != null) AppLaunch.start(this, intent, "Search app"); }); }
            heading(R.string.search_titles);
            if (matches.isEmpty()) {
                TextView hint = label(!problem.isEmpty() ? problem : text(value.codePointCount(0, value.length()) < 3 ? R.string.search_minimum : R.string.search_loading), 28);
                hint.setTextColor(muted()); results.addView(hint);
            } else posters(matches);
        }
        results.post(this::windowImages);
    }
    /** Only the poster is a focus target; captions have no extra frame or focus padding. */
    private void posters(List<Recs.Pick> picks) {
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(6); grid.setClipChildren(false); grid.setClipToPadding(false); grid.setPadding(px(14), px(16), px(14), px(16));
        for (Recs.Pick pick : picks) {
            Art art = new Art(pick.poster, 0); art.pick = pick;
            art.poster = new PosterRenderer(Recs.get(this), shadows, roundedImages, 1);
            android.widget.GridLayout.LayoutParams box = new android.widget.GridLayout.LayoutParams();
            box.width = px(268); box.height = px(502); box.rightMargin = grid.getChildCount() % 6 == 5 ? 0 : px(32); box.bottomMargin = px(32);
            grid.addView(art, box); art.setContentDescription(pick.title); art.setBackgroundColor(0x00000000); art.setClipToOutline(false);
            art.setFocusable(true); art.setClickable(true); art.setDefaultFocusHighlightEnabled(false);
            art.setOnFocusChangeListener((v, focused) -> {
                art.focused = focused; art.poster.focus(focused ? 0 : -1, 0, 0, android.os.SystemClock.uptimeMillis()); art.invalidate();
            });
            art.setOnClickListener(v -> showDetail(pick, art));
        }
        results.addView(grid, new LinearLayout.LayoutParams(-1, -2));
    }
    private void loadSuggestions() {
        metadataIo.execute(() -> {
            List<LocalApp> installed = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo != null && seen.add(info.activityInfo.packageName) && !getPackageName().equals(info.activityInfo.packageName))
                        installed.add(new LocalApp(info.activityInfo.packageName, String.valueOf(info.loadLabel(getPackageManager()))));
                }
            }
            installed.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            main.post(() -> { if (!closed) { apps.addAll(installed); if (getCurrentFocus() == query || query.getText().toString().trim().isEmpty()) render(); } });
            try {
                List<Recs.Pick> popular = SearchData.trending();
                main.post(() -> { if (!closed) { trending.addAll(popular); if (query.getText().toString().trim().isEmpty()) render(); } });
            } catch (java.io.IOException | org.json.JSONException e) { AppLog.w("Search suggestions unavailable: " + e.getClass().getSimpleName()); }
        });
    }
    private String metadata(Recs.Pick pick, SearchData.Detail extra) {
        List<String> values = new ArrayList<>(); String year = "tv".equals(pick.kind) ? pick.tvOrigin : pick.movieOrigin;
        if (year != null && !year.isEmpty()) values.add(year);
        values.add(text("tv".equals(pick.kind) ? R.string.search_series : R.string.search_movie));
        if (extra != null && !extra.genres.isEmpty()) values.add(extra.genres);
        if (pick.rating > 0) values.add(String.format(Ui.locale(), "TMDB %.1f", pick.rating));
        if (extra != null && extra.minutes > 0) values.add(String.format(Ui.locale(), text(R.string.search_runtime), extra.minutes));
        return String.join("  ·  ", values);
    }
    private void showDetail(Recs.Pick pick, View from) {
        hideKeyboard(); remember(); search.cancel(); returnFocus = from; detailGeneration++;
        final int generation = detailGeneration;
        detail = new FrameLayout(this); detail.setBackgroundColor(0xFF101419); artwork.clear();
        Art back = new Art(wide(pick.backdrop), 0); detail.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF7101419, 0xD9101419, 0x42101419})); detail.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this); scroll.setClipChildren(false); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = column(); content.setPadding(px(65), px(90), px(35), px(50));
        TextView title = label(pick.title, 64); title.setTypeface(Ui.displayBold); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); content.addView(title);
        TextView meta = label(metadata(pick, null), 28); meta.setTextColor(0xFFB7BAC0); addSpace(content, meta, 24);
        TextView overview = label(text(R.string.search_loading), 30); overview.setTextColor(0xFFDDDFE4); overview.setMaxLines(5); overview.setEllipsize(TextUtils.TruncateAt.END); overview.setLineSpacing(px(4), 1); addSpace(content, overview, 28);
        TextView how = label(text(R.string.search_how), 27); how.setTextColor(0xFFB7BAC0); addSpace(content, how, 38);
        View first = null;
        for (int i = 1; i < MovieService.count(); i++) {
            String pkg = MovieService.resolve(MovieService.id(i), p -> HomeApps.launchIntent(getPackageManager(), p) != null);
            if (pkg.isEmpty()) continue;
            String name = MovieService.label(i, AppLanguage.current());
            TextView action = button(String.format(Ui.locale(), text(R.string.search_watch_in), name), () -> launcher.openIn(pick, pkg, name));
            try {
                android.graphics.drawable.Drawable icon = getPackageManager().getApplicationIcon(pkg); icon.setBounds(0, 0, px(46), px(46));
                action.setCompoundDrawables(icon, null, null, null); action.setCompoundDrawablePadding(px(20));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) { AppLog.w("Search provider removed"); }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, px(76)); params.topMargin = px(16); content.addView(action, params); if (first == null) first = action;
        }
        if (first == null) { TextView note = label(text(R.string.search_no_apps), 28); addSpace(content, note, 20); }
        scroll.addView(content, new ScrollView.LayoutParams(px(940), -2));
        detail.addView(scroll, new FrameLayout.LayoutParams(px(940), -1)); root.addView(detail, new FrameLayout.LayoutParams(-1, -1));
        screen.setVisibility(View.INVISIBLE); detail.setAlpha(0); detail.animate().alpha(1).setDuration(220).start();
        if (first != null) first.requestFocus(); else { detail.setFocusableInTouchMode(true); detail.requestFocus(); }
        root.post(this::windowImages);
        if (detailWork != null) detailWork.cancel(false);
        detailWork = metadataIo.submit(() -> {
            try {
                SearchData.Detail data = SearchData.detail(pick);
                main.post(() -> { if (closed || detail == null || generation != detailGeneration) return;
                    overview.setText(data.overview.isEmpty() ? text(R.string.search_no_overview) : data.overview); meta.setText(metadata(pick, data));
                    if (!data.backdrop.isEmpty()) back.path = wide(data.backdrop); windowImages(); back.invalidate(); });
            } catch (java.io.IOException | org.json.JSONException e) {
                main.post(() -> { if (!closed && detail != null && generation == detailGeneration) overview.setText(text(R.string.search_detail_unavailable)); });
            }
        });
    }
    private void addSpace(LinearLayout col, View view, int gap) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = px(gap); col.addView(view, p); }
    private static String wide(String path) { return path == null || path.isEmpty() ? "" : path.startsWith("/") ? "https://image.tmdb.org/t/p/w1280" + path : path; }
    private void closeDetail() {
        detailGeneration++; if (detailWork != null) detailWork.cancel(false);
        root.removeView(detail); detail = null; artwork.clear(); collectArt(results); screen.setVisibility(View.VISIBLE);
        if (returnFocus != null) returnFocus.requestFocus(); results.post(this::windowImages);
    }
    private void collectArt(View view) { if (view instanceof Art) artwork.add((Art) view); else if (view instanceof ViewGroup) {
        ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) collectArt(group.getChildAt(i)); } }
    private final Rect visibleBounds = new Rect();
    private void windowImages() {
        if (images == null || closed) return;
        Set<String> wanted = new HashSet<>();
        for (Art art : artwork) if (!art.path.isEmpty() && art.getGlobalVisibleRect(visibleBounds) && wanted.size() < 10) wanted.add(art.path);
        images.setWindow(wanted); for (Art art : artwork) art.invalidate();
    }
    private final class Art extends View {
        String path;
        boolean focused;
        Recs.Pick pick;
        PosterRenderer poster;
        private final Paint title = Ui.text(Ui.semibold, 31 * scale, ink());
        private final Paint meta = Ui.text(Ui.medium, 25 * scale, muted());
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF bounds = new RectF();
        Art(String value, int radius) {
            super(SearchActivity.this); path = value == null ? "" : value; artwork.add(this);
            setBackground(rounded(0xFF242A32, radius)); setClipToOutline(radius > 0);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); Bitmap bitmap = images.poster(path);
            if (pick != null) {
                bounds.set(0, 0, getWidth(), px(402));
                poster.begin(scale, night ? 1 : 0, 0, 0, true, focused, title, meta);
                poster.draw(canvas, pick, bounds, focused ? 1 : 0, 0, bitmap);
                if (focused) postInvalidateDelayed(poster.nextDelay(android.os.SystemClock.uptimeMillis()));
                return;
            }
            if (bitmap != null) {
            float zoom = Math.max((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
            float w = bitmap.getWidth() * zoom, h = bitmap.getHeight() * zoom;
            bounds.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            canvas.drawBitmap(bitmap, null, bounds, paint);
            }
            if (focused) {
                rim.setStyle(Paint.Style.STROKE); rim.setStrokeWidth(px(3)); rim.setColor(0xE6FFFFFF);
                bounds.set(px(2), px(2), getWidth() - px(2), getHeight() - px(2));
                canvas.drawRoundRect(bounds, px(19), px(19), rim);
            }
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && initialInput && detail == null) {
            initialInput = false;
            query.post(() -> { if (running && detail == null && query.hasFocus()) keyboard(); });
        }
    }
    @Override protected void onResume() { super.onResume(); running = true; shortcuts.reload(); search.resume(query.getText().toString().trim()); search.restore(query.getText().toString().trim(), !matches.isEmpty()); }
    @Override protected void onPause() { running = false; search.pause(detail == null); shortcuts.cancel(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("query", query.getText().toString()); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { closed = true; search.cancel(); shortcuts.close(); images.setWindow(new HashSet<>()); metadataIo.shutdownNow(); artworkIo.shutdownNow(); main.removeCallbacksAndMessages(null); roundedImages.clear(); shadows.clear(); pageBackdrop = null; super.onDestroy(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return shortcuts != null && shortcuts.onKey(event) || super.dispatchKeyEvent(event); }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { if (detail != null) closeDetail(); else { hideKeyboard(); super.onBackPressed(); } }
}
