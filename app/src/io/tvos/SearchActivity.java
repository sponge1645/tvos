package io.tvos;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;
import io.tvos.artwork.PosterCache;
import io.tvos.artwork.RoundedImages;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONArray;

/** A single drawing surface owns search geometry, focus and scrolling. Only text input is native. */
public final class SearchActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService metadataIo = Executors.newFixedThreadPool(2), artworkIo = Executors.newFixedThreadPool(2);
    private final List<Recs.Pick> trending = new ArrayList<>(), matches = new ArrayList<>();
    private final List<LocalApp> apps = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private final List<Integer> posterTargets = new ArrayList<>();
    private final Shadows shadows = new Shadows();
    private final RoundedImages rounded = new RoundedImages(Ui.LEGACY);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint heading = new TextPaint(Paint.ANTI_ALIAS_FLAG), textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG), metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scratch = new RectF();
    private SearchPage page;
    private EditText query;
    private PosterCache images;
    private PosterRenderer posters;
    private SearchController search;
    private MovieAppLauncher launcher;
    private RemoteShortcuts shortcuts;
    private SharedPreferences history;
    private Bitmap backdrop;
    private Future<?> detailWork;
    private Recs.Pick detail;
    private StaticLayout detailTitle, detailOverview, detailMeta;
    private String detailArt = "", problem = "";
    private float scale, scroll, scrollDestination, contentHeight, resultsTop, detailActionsTop, searchScroll;
    private int selected = -1, savedSelected = -1, column, generation;
    private long lastFrame;
    private boolean running, closed, initialInput = true, focusAfterSearch;
    private boolean night;
    private android.animation.ValueAnimator scrolling;
    private final List<Spring> focus = new ArrayList<>();

    private static final class LocalApp {
        final String pkg, label;
        LocalApp(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }
    private static final class Target {
        final RectF bounds;
        final String label;
        final Runnable action;
        final int poster;
        Target(RectF bounds, String label, int poster, Runnable action) { this.bounds = bounds; this.label = label; this.poster = poster; this.action = action; }
    }
    private String text(int id) { return AppStrings.get(id); }
    private int ink() { return detail != null || night ? 0xFFF5F5F7 : 0xFF1D1D1F; }
    private int muted() { return detail != null || night ? 0xFFA6A5AD : 0xFF65616C; }
    private boolean reduced() { return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("reduce_motion", false); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); Watchdog.install(this);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        night = true; // The supplied search reference uses a stable warm charcoal surface.
        getIntent().removeExtra("profile_frost");
        backdrop = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
        Canvas backgroundCanvas = new Canvas(backdrop);
        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        backgroundPaint.setShader(new LinearGradient(0, 0, 192, 108,
                new int[]{0xFF3C3539, 0xFF302C2E, 0xFF24252B}, null, Shader.TileMode.CLAMP));
        backgroundCanvas.drawPaint(backgroundPaint);
        backgroundPaint.setShader(new android.graphics.RadialGradient(100, 115, 90, 0x503E3042, 0, Shader.TileMode.CLAMP));
        backgroundCanvas.drawPaint(backgroundPaint);
        history = getSharedPreferences("search-history", MODE_PRIVATE);
        page = new SearchPage();
        images = new PosterCache(this, () -> main.post(() -> { if (!closed) page.invalidate(); }), artworkIo, AppLog::w);
        images.setWindow(new HashSet<>());
        posters = new PosterRenderer(Recs.get(this), shadows, rounded, 64);
        search = new SearchController(main, Recs.get(this), () -> running && detail == null, found -> {
            matches.clear(); matches.addAll(found); problem = found.isEmpty() ? text(R.string.search_empty) : ""; rebuild();
            if (focusAfterSearch) { focusAfterSearch = false; selectFirstPoster(); }
        });
        launcher = new MovieAppLauncher(this, page, metadataIo, main, new MovieAppLauncher.Host() {
            public boolean running() { return running && detail != null; }
            public int epoch() { return generation; }
            public void toast(String value) { Toast.makeText(SearchActivity.this, value, Toast.LENGTH_SHORT).show(); }
        });
        shortcuts = new RemoteShortcuts(this, true, null);
        query = new EditText(this, null, 0, R.style.SearchPageInput);
        query.setSingleLine(true); query.setTypeface(Ui.displaySemibold); query.setIncludeFontPadding(false);
        query.setTextSize(TypedValue.COMPLEX_UNIT_PX, 54 * scale); query.setTextColor(ink()); query.setHintTextColor(muted());
        query.setHint(text(R.string.search_hint)); query.setPadding(0, 0, 0, 0); query.setBackgroundColor(0);
        query.setDefaultFocusHighlightEnabled(false);
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void afterTextChanged(Editable e) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                matches.clear(); problem = ""; focusAfterSearch = false; selected = -1;
                search.changed(s.toString().trim()); rebuild(); scrollTo(0, false);
            }
        });
        query.setOnClickListener(v -> keyboard());
        query.setOnEditorActionListener((v, action, event) -> {
            remember(); hideKeyboard(); focusAfterSearch = true; search.flush(); selectFirstPoster(); return true;
        });
        page.addView(query); setContentView(page);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (state != null) query.setText(state.getString("query", ""));
        rebuild(); query.requestFocus(); loadSuggestions();
    }
    private void keyboard() { selected = -1; query.requestFocus(); ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT); }
    private void hideKeyboard() { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0); }
    private List<String> recent() {
        List<String> result = new ArrayList<>();
        try { JSONArray list = new JSONArray(history.getString("recent", "[]")); for (int i = 0; i < list.length(); i++) result.add(list.optString(i)); }
        catch (org.json.JSONException e) { AppLog.w("Invalid search history"); }
        return SearchHistory.remember(result, "");
    }
    private void remember() { String value = query.getText().toString().trim(); if (!value.isEmpty()) history.edit().putString("recent", new JSONArray(SearchHistory.remember(recent(), value)).toString()).apply(); }
    private void setQuery(String value) { query.setText(value); query.setSelection(query.length()); hideKeyboard(); focusAfterSearch = true; search.flush(); }
    private int target(float x, float y, float w, float h, String label, int poster, Runnable action) {
        targets.add(new Target(new RectF(x, y, x+w, y+h), label, poster, action)); focus.add(new Spring(.26f, .85f, 0)); return targets.size()-1;
    }
    private void rebuild() {
        if (closed || detail != null) return;
        String focusedLabel = selected >= 0 && selected < targets.size() ? targets.get(selected).label : "";
        boolean restoreFocus = page.hasFocus() && !query.hasFocus();
        targets.clear(); posterTargets.clear(); focus.clear(); selected = -1;
        String value = query.getText().toString().trim();
        boolean hasRecent = value.isEmpty() && !recent().isEmpty();
        if (value.isEmpty()) {
            float x = 80; textPaint.setTextSize(28); textPaint.setTypeface(Ui.medium);
            for (String entry : recent()) {
                float width = Math.max(110, textPaint.measureText(entry)+54);
                if (x+width > 1620) break;
                target(x, 205, width, 64, entry, -1, () -> setQuery(entry)); x += width+24;
            }
            if (hasRecent) target(1670, 205, 170, 64, text(R.string.search_clear), -1, () -> { history.edit().remove("recent").apply(); rebuild(); query.requestFocus(); });
        } else {
            float x = 80; textPaint.setTextSize(28); textPaint.setTypeface(Ui.medium);
            for (LocalApp app : apps) if (app.label.toLowerCase(Ui.locale()).contains(value.toLowerCase(Ui.locale()))) {
                float width = Math.min(420, Math.max(140, textPaint.measureText(app.label)+54)); if (x+width > 1840) break;
                target(x, 205, width, 64, app.label, -1, () -> { remember(); Intent intent = HomeApps.launchIntent(getPackageManager(), app.pkg); if (intent != null) AppLaunch.start(this, intent, "Search app"); }); x += width+24;
            }
        }
        resultsTop = targets.isEmpty() ? 300 : 410;
        List<Recs.Pick> picks = value.isEmpty() ? trending : matches;
        for (int i = 0; i < Math.min(64, picks.size()); i++) {
            final Recs.Pick pick = picks.get(i);
            posterTargets.add(target(80+(i%6)*300, resultsTop+(i/6)*510, 260, 475, pick.title, i, () -> openDetail(pick)));
        }
        contentHeight = picks.isEmpty() ? 1080 : Math.max(1080, resultsTop + ((Math.min(64, picks.size())-1)/6)*510 + 475 + 32);
        if (restoreFocus && !focusedLabel.isEmpty()) for (int i = 0; i < targets.size(); i++) {
            if (focusedLabel.equals(targets.get(i).label)) { selected = i; focus.get(i).snap(1); break; }
        }
        refreshImages(); page.requestLayout(); page.invalidate();
    }
    private List<Recs.Pick> picks() { return query.getText().toString().trim().isEmpty() ? trending : matches; }
    private void refreshImages() {
        Set<String> wanted = new HashSet<>();
        if (detail != null) { if (!detailArt.isEmpty()) wanted.add(detailArt); }
        else for (int index : posterTargets) {
            Target t = targets.get(index);
            if (t.bounds.bottom >= scroll-100 && t.bounds.top <= scroll+1180) {
                if (t.poster >= picks().size()) continue;
                String path = picks().get(t.poster).poster; if (path != null && !path.isEmpty()) wanted.add(path);
            }
        }
        images.setWindow(wanted);
    }
    private void scrollTo(float value, boolean animate) {
        float height = page.getHeight() > 0 ? page.getHeight()/scale : 1080;
        float to = Math.max(0, Math.min(Math.max(0, contentHeight-height), value));
        if (animate && scrolling != null && scrolling.isRunning() && Math.abs(to-scrollDestination) < .5f) return;
        if (scrolling != null) scrolling.cancel();
        scrollDestination = to;
        if (!animate || reduced()) { scroll = to; page.requestLayout(); refreshImages(); page.invalidate(); return; }
        if (Math.abs(to-scroll) < .5f) return;
        scrolling = android.animation.ValueAnimator.ofFloat(scroll, to); scrolling.setDuration(360);
        scrolling.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        scrolling.addUpdateListener(a -> { scroll = (float)a.getAnimatedValue(); page.requestLayout(); refreshImages(); page.invalidate(); }); scrolling.start();
    }
    private void select(int index, boolean preserveColumn) {
        if (index < 0 || index >= targets.size()) return;
        hideKeyboard(); page.requestFocus(); selected = index;
        Target t = targets.get(index);
        for (int i = 0; i < focus.size(); i++) { focus.get(i).target = i == index ? 1 : 0; if (reduced()) focus.get(i).snap(focus.get(i).target); }
        if (t.poster >= 0) {
            if (!preserveColumn) column = t.poster%6;
            posters.focus(t.poster, 0, 0, SystemClock.uptimeMillis());
            float viewport = page.getHeight() > 0 ? page.getHeight()/scale : 1080;
            float position = scrolling != null && scrolling.isRunning() ? scrollDestination : scroll;
            scrollTo(SearchGridNavigation.reveal(t.bounds.top, t.bounds.bottom, viewport, position), true);
        } else if (detail == null) scrollTo(0, true);
        else if (t.bounds.top < scroll+70 || t.bounds.bottom > scroll+970) scrollTo(Math.max(0, t.bounds.top-500), true);
        page.setContentDescription(t.label); page.invalidate();
    }
    private void selectFirstPoster() { if (!posterTargets.isEmpty()) select(posterTargets.get(0), false); }
    private void input() {
        selected = -1; for (Spring spring : focus) spring.target = 0;
        query.requestFocus(); scrollTo(0, true); page.invalidate();
    }
    private boolean key(KeyEvent event) {
        int key = event.getKeyCode();
        boolean arrow = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (query.hasFocus() && detail == null) {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) { if (!targets.isEmpty() && targets.get(0).poster < 0) select(0, false); else selectFirstPoster(); }
            return true;
        }
        if (!arrow && !ok) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (selected < 0 || selected >= targets.size()) { if (detail == null) input(); return true; }
        Target current = targets.get(selected);
        if (ok) { if (event.getRepeatCount() == 0) current.action.run(); return true; }
        if (current.poster >= 0) {
            int next;
            if (key == KeyEvent.KEYCODE_DPAD_UP && current.poster < 6) { input(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                next = SearchGridNavigation.horizontal(current.poster, posterTargets.size(), key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                select(posterTargets.get(next), false);
            } else {
                next = SearchGridNavigation.vertical(current.poster, posterTargets.size(), column, key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (next < 0) input(); else select(posterTargets.get(next), true);
            }
        } else if (detail != null) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) select(Math.max(0, selected-1), false);
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) select(Math.min(targets.size()-1, selected+1), false);
        } else {
            if (key == KeyEvent.KEYCODE_DPAD_UP) input();
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) selectFirstPoster();
            else select(Math.max(0, Math.min(posterTargets.isEmpty() ? targets.size()-1 : posterTargets.get(0)-1, selected+(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1))), false);
        }
        return true;
    }
    private void loadSuggestions() {
        metadataIo.execute(() -> {
            List<LocalApp> found = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER})
                for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0))
                    if (info.activityInfo != null && seen.add(info.activityInfo.packageName) && !getPackageName().equals(info.activityInfo.packageName)) found.add(new LocalApp(info.activityInfo.packageName, String.valueOf(info.loadLabel(getPackageManager()))));
            main.post(() -> { if (!closed) { apps.addAll(found); if (query.hasFocus()) rebuild(); } });
            try { List<Recs.Pick> popular = SearchData.trending(); main.post(() -> { if (!closed) { trending.addAll(popular); if (detail == null && query.getText().toString().trim().isEmpty()) rebuild(); } }); }
            catch (java.io.IOException | org.json.JSONException e) { AppLog.w("Search suggestions unavailable"); }
        });
    }
    private StaticLayout paragraph(String value, float size, int color, int width, int lines) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG); p.setTypeface(Ui.medium); p.setTextSize(size); p.setColor(color);
        return StaticLayout.Builder.obtain(value, 0, value.length(), p, width).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4, 1).setIncludePad(false).build();
    }
    private void openDetail(Recs.Pick pick) {
        remember(); hideKeyboard(); search.cancel(); savedSelected = selected; searchScroll = scroll; detail = pick; generation++;
        int expected = generation; detailArt = wide(pick.backdrop); query.setVisibility(View.INVISIBLE);
        buildDetail(null); scrollTo(0, false); select(0, false);
        if (detailWork != null) detailWork.cancel(false);
        detailWork = metadataIo.submit(() -> {
            try { SearchData.Detail data = SearchData.detail(pick); main.post(() -> {
                if (closed || generation != expected || detail != pick) return;
                if (!data.backdrop.isEmpty()) detailArt = wide(data.backdrop); buildDetail(data); if (selected >= 0) select(selected, false); refreshImages(); page.invalidate();
            }); } catch (java.io.IOException | org.json.JSONException e) { main.post(() -> { if (!closed && generation == expected && detail != null) { detailOverview = paragraph(text(R.string.search_detail_unavailable), 30, 0xFFDDDFE4, 820, 5); page.invalidate(); } }); }
        });
    }
    private void buildDetail(SearchData.Detail data) {
        detailTitle = paragraph(detail.title, 64, 0xFFFFFFFF, 820, 2);
        List<String> values = new ArrayList<>(); String year = "tv".equals(detail.kind) ? detail.tvOrigin : detail.movieOrigin;
        if (year != null && !year.isEmpty()) values.add(year);
        values.add(text("tv".equals(detail.kind) ? R.string.search_series : R.string.search_movie));
        if (data != null && !data.genres.isEmpty()) values.add(data.genres);
        if (detail.rating > 0) values.add(String.format(Ui.locale(), "TMDB %.1f", detail.rating));
        if (data != null && data.minutes > 0) values.add(String.format(Ui.locale(), text(R.string.search_runtime), data.minutes));
        detailMeta = paragraph(String.join("  ·  ", values), 27, 0xFFB7BAC0, 820, 2);
        detailOverview = paragraph(data == null ? text(R.string.search_loading) : data.overview.isEmpty() ? text(R.string.search_no_overview) : data.overview, 30, 0xFFDDDFE4, 820, 5);
        detailActionsTop = 90+detailTitle.getHeight()+26+detailMeta.getHeight()+32+detailOverview.getHeight()+100;
        targets.clear(); posterTargets.clear(); focus.clear();
        float y = detailActionsTop;
        for (int i = 1; i < MovieService.count(); i++) {
            String pkg = MovieService.resolve(MovieService.id(i), p -> HomeApps.launchIntent(getPackageManager(), p) != null); if (pkg.isEmpty()) continue;
            String name = MovieService.label(i, AppLanguage.current());
            target(80, y, 800, 72, String.format(Ui.locale(), text(R.string.search_watch_in), name), -1, () -> launcher.openIn(detail, pkg, name)); y += 96;
        }
        if (selected >= targets.size()) selected = targets.isEmpty() ? -1 : 0;
        if (selected >= 0) focus.get(selected).snap(1);
        contentHeight = Math.max(1080, y+100); refreshImages(); page.invalidate();
    }
    private void closeDetail() {
        generation++; if (detailWork != null) detailWork.cancel(false); detail = null; query.setVisibility(View.VISIBLE);
        rebuild(); int index = savedSelected; scrollTo(searchScroll, false); if (index >= 0 && index < targets.size()) select(index, false); else input();
    }
    private static String wide(String path) { return path == null || path.isEmpty() ? "" : path.startsWith("/") ? "https://image.tmdb.org/t/p/w1280"+path : path; }
    private void label(Canvas c, String value, float x, float y, float size, int color) {
        textPaint.setTypeface(Ui.medium); textPaint.setTextSize(size); textPaint.setColor(color); c.drawText(value, x, y, textPaint);
    }
    private void capsule(Canvas c, Target target, float amount) {
        amount = Ui.clamp(amount);
        float grow = 1+.06f*amount; int saved = c.save(); c.scale(grow, grow, target.bounds.centerX(), target.bounds.centerY());
        if (amount > .001f) {
            paint.setShader(null); paint.setColor(Ui.alpha(0xFFFFFFFF, amount));
            c.drawRoundRect(target.bounds, target.bounds.height()/2, target.bounds.height()/2, paint);
        }
        textPaint.setTypeface(Ui.displayMedium); textPaint.setTextSize(target.bounds.height() < 70 ? 28 : 30);
        textPaint.setColor(ActionSurface.blend(ink(), 0xFF1D1D1F, amount));
        String shown = TextUtils.ellipsize(target.label, textPaint, target.bounds.width()-44, TextUtils.TruncateAt.END).toString();
        c.drawText(shown, target.bounds.centerX()-textPaint.measureText(shown)/2, target.bounds.centerY()+Ui.halfCap(textPaint), textPaint); c.restoreToCount(saved);
    }
    private final class SearchPage extends ViewGroup {
        private float touchY, startScroll; private boolean dragging;
        SearchPage() { super(SearchActivity.this); setWillNotDraw(false); setFocusable(true); setFocusableInTouchMode(true); setClipChildren(true); }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(MeasureSpec.getSize(width), MeasureSpec.getSize(height));
            if (query != null) query.measure(MeasureSpec.makeMeasureSpec(Math.round(1620*scale), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.round(88*scale), MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (query != null) { int x = Math.round(195*scale), y = Math.round((80-scroll)*scale); query.layout(x, y, x+query.getMeasuredWidth(), y+query.getMeasuredHeight()); }
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); paint.setShader(null); paint.setAlpha(255); scratch.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(backdrop, null, scratch, paint);
            if (detail != null) {
                Bitmap image = images.poster(detailArt); if (image != null) rounded.draw(c, image, scratch, 0);
                paint.setShader(new LinearGradient(0, 0, getWidth(), 0, new int[]{0xF7101419,0xD9101419,0x42101419}, null, Shader.TileMode.CLAMP)); c.drawRect(scratch, paint); paint.setShader(null);
            }
            int saved = c.save(); c.clipRect(0, 0, getWidth(), getHeight()); c.scale(scale, scale); c.translate(0, -scroll);
            long now = SystemClock.uptimeMillis(); float dt = lastFrame == 0 ? .016f : Math.min(.04f, (now-lastFrame)/1000f); lastFrame = now; boolean moving = false;
            for (Spring spring : focus) moving |= spring.step(dt);
            if (detail == null) {
                Symbols.draw(SearchActivity.this, c, "search", 125, 124, 48, muted());
                paint.setColor(night ? 0x35FFFFFF : 0x351D1D1F); c.drawRect(80,resultsTop-110,1840,resultsTop-109,paint);
                boolean empty = query.getText().toString().trim().isEmpty();
                label(c, text(empty ? R.string.search_trending : R.string.search_titles), 80, resultsTop-33, 30, muted());
                if (!empty && matches.isEmpty()) label(c, !problem.isEmpty() ? problem : text(query.length() < 3 ? R.string.search_minimum : R.string.search_loading), 80, resultsTop+40, 28, muted());
                titlePaint.setTypeface(Ui.semibold); titlePaint.setTextSize(31); titlePaint.setColor(ink()); metaPaint.setTypeface(Ui.medium); metaPaint.setTextSize(25); metaPaint.setColor(muted());
                posters.begin(1, night ? 1 : 0, 0, 0, true, selected >= 0 && targets.get(selected).poster >= 0, titlePaint, metaPaint);
                for (int i = 0; i < targets.size(); i++) {
                    Target target = targets.get(i); if (target.bounds.bottom < scroll-90 || target.bounds.top > scroll+getHeight()/scale+90) continue;
                    if (target.poster < 0) capsule(c, target, focus.get(i).value);
                    else {
                        Recs.Pick pick = picks().get(target.poster); scratch.set(target.bounds.left, target.bounds.top, target.bounds.right, target.bounds.top+390);
                        posters.draw(c, pick, scratch, focus.get(i).value, target.poster, images.poster(pick.poster));
                    }
                }
            } else {
                paragraph(c, detailTitle, 80, 90); paragraph(c, detailMeta, 80, 116+detailTitle.getHeight());
                paragraph(c, detailOverview, 80, 148+detailTitle.getHeight()+detailMeta.getHeight());
                label(c, text(R.string.search_how), 80, detailActionsTop-34, 27, 0xFFB7BAC0);
                if (targets.isEmpty()) label(c, text(R.string.search_no_apps), 80, detailActionsTop+30, 27, 0xFFDDDFE4);
                for (int i = 0; i < targets.size(); i++) capsule(c, targets.get(i), focus.get(i).value);
            }
            c.restoreToCount(saved);
            if (moving) postInvalidateOnAnimation();
            else if (detail == null && selected >= 0 && targets.get(selected).poster >= 0) postInvalidateDelayed(posters.nextDelay(now));
        }
        private void paragraph(Canvas c, StaticLayout layout, float x, float y) { int saved = c.save(); c.translate(x,y); layout.draw(c); c.restoreToCount(saved); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { touchY = e.getY(); startScroll = scroll; dragging = false; return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE) { if (Math.abs(e.getY()-touchY) > 12*scale) dragging = true; if (dragging) SearchActivity.this.scrollTo(startScroll+(touchY-e.getY())/scale, false); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                if (!dragging) for (int i = targets.size()-1; i >= 0; i--) if (targets.get(i).bounds.contains(e.getX()/scale, e.getY()/scale+scroll)) { select(i,false); targets.get(i).action.run(); break; }
                return true;
            }
            return true;
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused && initialInput) { initialInput = false; query.post(() -> { if (running && detail == null && query.hasFocus()) keyboard(); }); } }
    @Override protected void onResume() { super.onResume(); running = true; shortcuts.reload(); search.resume(query.getText().toString().trim()); search.restore(query.getText().toString().trim(), !matches.isEmpty()); page.invalidate(); }
    @Override protected void onPause() { running = false; search.pause(detail == null); shortcuts.cancel(); if (scrolling != null) scrolling.cancel(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("query", query.getText().toString()); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { closed = true; search.cancel(); shortcuts.close(); images.setWindow(new HashSet<>()); metadataIo.shutdownNow(); artworkIo.shutdownNow(); main.removeCallbacksAndMessages(null); rounded.clear(); shadows.clear(); posters.clear(); super.onDestroy(); }
    @Override public boolean dispatchKeyEvent(KeyEvent e) { if (shortcuts != null && shortcuts.onKey(e)) return true; if (page != null && key(e)) return true; return super.dispatchKeyEvent(e); }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { if (detail != null) closeDetail(); else { hideKeyboard(); super.onBackPressed(); } }
}
