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
        TextView out = new TextView(this); out.setText(value); out.setTextColor(0xFFF4F4F6);
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
            if (v instanceof TextView) {
                v.setBackground(rounded(focused ? 0xFFF5F5F7 : 0x283D434B, 40));
                ((TextView) v).setTextColor(focused ? 0xFF17191E : 0xFFF4F4F6);
            } else if (v instanceof Art) {
                ((Art) v).focused = focused;
                v.setElevation(focused ? px(14) : 0); v.invalidate();
            }
            windowImages();
        });
    }
    private TextView button(String value, Runnable action) {
        TextView out = label(value, 30); out.setGravity(Gravity.CENTER_VERTICAL);
        out.setPadding(px(26), 0, px(26), 0); out.setBackground(rounded(0x283D434B, 40));
        out.setMaxLines(1); out.setEllipsize(TextUtils.TruncateAt.END);
        focusable(out, true); out.setOnClickListener(v -> action.run()); return out;
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); Ui.init(this);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root = new FrameLayout(this); root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF383236, 0xFF24262E, 0xFF22272C})); setContentView(root);
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
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageButton voice = new android.widget.ImageButton(this);
        voice.setDefaultFocusHighlightEnabled(false);
        voice.setImageResource(android.R.drawable.ic_btn_speak_now); voice.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        voice.setContentDescription(text(R.string.search_voice)); voice.setBackground(rounded(0x283D434B, 40)); voice.setFocusable(true);
        voice.setOnClickListener(v -> voice()); voice.setOnFocusChangeListener((v, focused) -> {
            voice.setBackground(rounded(focused ? 0xFFF5F5F7 : 0x283D434B, 40));
            voice.setImageTintList(android.content.res.ColorStateList.valueOf(focused ? 0xFF17191E : 0xFFFFFFFF));
            voice.animate().scaleX(focused ? 1.07f : 1).scaleY(focused ? 1.07f : 1).setDuration(180).start();
        });
        header.addView(voice, new LinearLayout.LayoutParams(px(76), px(76)));
        query = new EditText(this); query.setDefaultFocusHighlightEnabled(false); query.setSingleLine(true); query.setTypeface(Ui.displaySemibold);
        query.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(52)); query.setTextColor(0xFFFFFFFF); query.setHintTextColor(0xFF8D939B);
        query.setHint(text(R.string.search_hint)); query.setBackgroundColor(0x00000000);
        query.setPadding(px(32), 0, px(20), 0); query.setSelectAllOnFocus(false);
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT); query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        header.addView(query, new LinearLayout.LayoutParams(0, px(84), 1)); screen.addView(header);
        View divider = new View(this); divider.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, px(1)); line.topMargin = px(26);
        screen.addView(divider, line);
        ScrollView scroll = new ScrollView(this); scroll.setClipChildren(false); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        results = column(); results.setPadding(0, px(12), 0, px(20)); scroll.addView(results);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
        TextView title = label(text(id), 30); title.setTextColor(0xFFB4B8BF);
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
                TextView title = label(text(R.string.search_recent), 30); title.setTextColor(0xFFB4B8BF);
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
                hint.setTextColor(0xFF959BA5); results.addView(hint);
            } else posters(matches);
        }
        results.post(this::windowImages);
    }
    /** Only the poster is a focus target; captions have no extra frame or focus padding. */
    private void posters(List<Recs.Pick> picks) {
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(6); grid.setClipChildren(false); grid.setClipToPadding(false); grid.setPadding(px(14), px(16), px(14), px(16));
        for (Recs.Pick pick : picks) {
            LinearLayout card = column();
            Art art = new Art(pick.poster, 20); card.addView(art, new LinearLayout.LayoutParams(-1, px(402)));
            TextView title = label(pick.title, 27); title.setGravity(Gravity.CENTER); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(-1, px(44)); caption.topMargin = px(10); card.addView(title, caption);
            android.widget.GridLayout.LayoutParams box = new android.widget.GridLayout.LayoutParams();
            box.width = px(268); box.height = -2; box.rightMargin = grid.getChildCount() % 6 == 5 ? 0 : px(32); box.bottomMargin = px(28);
            grid.addView(card, box); art.setContentDescription(pick.title); focusable(art, true);
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
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF bounds = new RectF();
        Art(String value, int radius) {
            super(SearchActivity.this); path = value == null ? "" : value; artwork.add(this);
            setBackground(rounded(0xFF242A32, radius)); setClipToOutline(radius > 0);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); Bitmap bitmap = images.poster(path);
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
    @SuppressWarnings("deprecation")
    private void voice() {
        Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, AppLanguage.current().apiTag);
        try { startActivityForResult(intent, 42); } catch (android.content.ActivityNotFoundException | SecurityException e) { toast(R.string.search_voice_unavailable); }
    }
    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 42 && result == RESULT_OK && data != null) {
            ArrayList<String> values = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (values != null && !values.isEmpty()) setQuery(values.get(0));
        }
    }
    @Override protected void onResume() { super.onResume(); running = true; shortcuts.reload(); search.resume(query.getText().toString().trim()); search.restore(query.getText().toString().trim(), !matches.isEmpty()); }
    @Override protected void onPause() { running = false; search.pause(detail == null); shortcuts.cancel(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("query", query.getText().toString()); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { closed = true; search.cancel(); shortcuts.close(); images.setWindow(new HashSet<>()); metadataIo.shutdownNow(); artworkIo.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return shortcuts != null && shortcuts.onKey(event) || super.dispatchKeyEvent(event); }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { if (detail != null) closeDetail(); else { hideKeyboard(); super.onBackPressed(); } }
}
