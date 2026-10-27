package io.tvos;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Native focusable genre menu; the home owns only eligibility, anchor and catalog refresh. */
final class HomeGenres {
    interface Host {
        boolean eligible();
        default String kind() { return "movie"; }
        RectF anchor();
        void opened();
        void changed();
        void message(String text);
    }
    // All labels share one text column; the checkmark occupies its own fixed gutter.
    private static final float TEXT_LEFT = 54, CHECK_LEFT = 22;
    private static final int VISIBLE_GENRES = 7;
    private static final class GenreScroll extends ScrollView {
        private final int fadeLength;
        GenreScroll(Context context, float k) {
            super(context, null, 0, R.style.GenreScroll);
            fadeLength = Math.max(1, Math.round(24 * k));
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 34 * k);
                }
            });
            setVerticalScrollBarEnabled(true);
            setScrollbarFadingEnabled(true);
            setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            setClipToPadding(true);
            setVerticalFadingEdgeEnabled(true);
            setFadingEdgeLength(fadeLength);
        }
        void showPosition() { awakenScrollBars(); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            if (getChildCount() == 0) return 0;
            // Native ScrollView reserves both fading edges, even though our bottom strength is zero.
            // Reveal only the actual row: seven whole rows must still fit after keyboard scrolling.
            int top = getScrollY(), height = getHeight();
            int target = top;
            if (rect.bottom > top + height) target = rect.bottom - height;
            else if (rect.top < top) target = rect.top;
            int max = Math.max(0, getChildAt(0).getHeight() - height);
            return Math.max(0, Math.min(max, target)) - top;
        }
        @Override protected float getTopFadingEdgeStrength() {
            return Math.min(1f, Math.max(0, getScrollY()) / (float) fadeLength);
        }
        @Override protected float getBottomFadingEdgeStrength() { return 0; }
    }
    private static final class MenuRow extends TextView {
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path checkPath;
        MenuRow(Context context, boolean checked, float k) {
            super(context);
            checkPath = checked ? new Path() : null;
            if (checked) {
                checkPaint.setStyle(Paint.Style.STROKE);
                checkPaint.setStrokeWidth(3 * k);
                checkPaint.setStrokeCap(Paint.Cap.ROUND);
                checkPaint.setStrokeJoin(Paint.Join.ROUND);
                checkPath.moveTo(CHECK_LEFT * k, 34 * k);
                checkPath.lineTo((CHECK_LEFT + 7) * k, 41 * k);
                checkPath.lineTo((CHECK_LEFT + 20) * k, 25 * k);
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (checkPath != null) {
                checkPaint.setColor(getCurrentTextColor());
                canvas.drawPath(checkPath, checkPaint);
            }
        }
    }
    private final View home;
    private final Context context;
    private final Handler handler;
    private final GenreCatalog catalog;
    private final Host host;
    private final Runnable hold = this::show;
    private final int[] location = new int[2];
    private final class MenuPopup extends PopupWindow {
        final View root;
        final Rect clip = new Rect();
        final boolean reduced;
        float originX, originY, progress;
        boolean closing, opening;
        Runnable afterOpen;
        MenuPopup(View root, int width, boolean reduced, float radius) {
            super(root, width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
            this.root = root; this.reduced = reduced;
            root.setClipToOutline(true);
            root.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    if (progress == 1) outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    else if (clip.right <= clip.left || clip.bottom <= clip.top) outline.setEmpty();
                    else outline.setRoundRect(clip, Math.min(radius,
                            Math.min(clip.right - clip.left, clip.bottom - clip.top) / 2f));
                }
            });
        }
        @Override public void dismiss() { closeMenu(this); }
        void dismissNow() { super.dismiss(); }
    }
    private MenuPopup popup;
    private UiRequest genreRequest, movieRequest;
    private ValueAnimator animation;
    private GenreCatalog.Genre selected;
    private List<Recs.Pick> movies;
    private boolean openingRelease;

    HomeGenres(View home, Handler handler, GenreCatalog catalog, Host host) {
        this.home = home; this.context = home.getContext(); this.handler = handler;
        this.catalog = catalog; this.host = host;
    }
    String label() { return selected == null ? AppStrings.get("tv".equals(host.kind()) ? R.string.home_tab_shows : R.string.home_tab_movies) : selected.name; }
    List<Recs.Pick> movies() { return movies; } // null means the ordinary Movies catalog.
    void arm() {
        cancelHold();
        if (host.eligible()) handler.postDelayed(hold, HomeDockEditor.HOLD_MS);
    }
    void cancelHold() { handler.removeCallbacks(hold); }
    void released() { cancelHold(); openingRelease = false; }
    boolean pause() {
        boolean changed = selected != null;
        cancelHold();
        if (popup != null) popup.dismissNow();
        if (genreRequest != null) genreRequest.cancel();
        if (movieRequest != null) movieRequest.cancel();
        genreRequest = movieRequest = null;
        selected = null; movies = null; openingRelease = false;
        return changed;
    }
    private boolean initialRelease(View view, int code, KeyEvent event) {
        if (popup != null && popup.closing) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!openingRelease || !ok) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) openingRelease = false;
        return true; // The release/repeats of the opening hold must not choose a row.
    }
    private void show() {
        cancelHold();
        if (!host.eligible() || popup != null) return;
        host.opened();
        openingRelease = true;
        float k = home.getWidth() / 1920f;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14 * k);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF52A2B2D, 0xF5161718});
        panel.setCornerRadius(44 * k);
        panel.setStroke(Math.max(1, Math.round(k)), 0x50FFFFFF);
        root.setBackground(panel);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener(this::initialRelease);
        row(root, null, k); // Pinned reset, outside the scrolling genre list.
        heading(root, AppStrings.get(R.string.home_genre), k);
        GenreScroll scroll = new GenreScroll(context, k);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, VISIBLE_GENRES * Math.round(68 * k)));
        for (int i = 0; i < VISIBLE_GENRES; i++) {
            TextView placeholder = text(i == 0 ? AppStrings.get(R.string.home_genre_loading) : "", 26, k);
            placeholder.setTextColor(0xFF999999);
            list.addView(placeholder, new LinearLayout.LayoutParams(-1, Math.round(68 * k)));
        }
        boolean reduced = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
                || !ValueAnimator.areAnimatorsEnabled();
        MenuPopup window = new MenuPopup(root, Math.round(420 * k), reduced, 44 * k);
        popup = window;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        window.setElevation(16 * k);
        window.setEnterTransition(null);
        window.setExitTransition(null);
        window.setAnimationStyle(0); // Opening and closing are handled by the anchored content reveal.
        window.setOnDismissListener(() -> {
            if (popup != window) return;
            cancelAnimation();
            window.afterOpen = null;
            if (genreRequest != null) genreRequest.cancel();
            genreRequest = null;
            if (popup == window) popup = null;
        });
        RectF anchor = host.anchor();
        home.getLocationOnScreen(location);
        int x = location[0] + Math.round(Math.max(40 * k, Math.min(home.getWidth() - 460 * k, anchor.left)) - 5 * k);
        int y = location[1] + Math.round(Math.max(40 * k, anchor.top - 44 * k));
        // Anchor the reveal at the leftmost point of the original Movies capsule.
        window.originX = anchor.left + location[0] - x;
        window.originY = anchor.centerY() + location[1] - y;
        root.setPivotX(window.originX); root.setPivotY(window.originY);
        applyReveal(window, reduced ? 1 : 0); // Never expose a full-size first window frame.
        window.showAtLocation(home, Gravity.TOP | Gravity.LEFT, x, y);
        root.requestFocus();
        if (!reduced) root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Begin on the next vsync after measurement/layout; cold popup setup consumes no animation time.
                root.postOnAnimation(() -> { if (popup == window && !window.closing) animateMenu(window, true); });
                return true;
            }
        });
        genreRequest = catalog.genres(host.kind(), AppLanguage.current(), choices -> {
            if (popup != window) return;
            Runnable content = () -> {
                list.removeAllViews();
                if (choices == null) {
                    heading(list, AppStrings.get(R.string.home_genre_failed), k);
                    root.getChildAt(0).requestFocus();
                } else {
                    int focus = 0;
                    for (GenreCatalog.Genre genre : choices) {
                        row(list, genre, k);
                        if (selected != null && selected.id == genre.id) focus = list.getChildCount() - 1;
                    }
                    if (list.getChildCount() > 0) list.getChildAt(focus).requestFocus();
                    else root.getChildAt(0).requestFocus();
                }
                window.update();
                scroll.postOnAnimation(scroll::showPosition);
            };
            // Cold network delivery must not inflate/re-layout all rows in the middle of the entrance.
            if (window.opening) window.afterOpen = content;
            else if (!window.closing) content.run();
        });
    }
    private void cancelAnimation() {
        ValueAnimator previous = animation;
        animation = null;
        if (previous != null) previous.cancel();
    }
    private void applyReveal(MenuPopup window, float fraction) {
        float p = Math.max(0, Math.min(1, fraction));
        window.progress = p;
        View root = window.root;
        root.setAlpha(p);
        float scale = .96f + .04f * p;
        root.setScaleX(scale); root.setScaleY(scale);
        if (p < 1) {
            window.clip.set(Math.round(window.originX * (1 - p)), Math.round(window.originY * (1 - p)),
                    Math.round(window.originX + (root.getWidth() - window.originX) * p),
                    Math.round(window.originY + (root.getHeight() - window.originY) * p));
        }
        root.invalidateOutline();
    }
    private void closeMenu(MenuPopup window) {
        if (popup != window || window.closing) return;
        window.closing = true;
        window.afterOpen = null;
        if (genreRequest != null) genreRequest.cancel();
        genreRequest = null;
        cancelAnimation();
        if (window.reduced || window.progress == 0) window.dismissNow();
        else animateMenu(window, false);
    }
    private void animateMenu(MenuPopup window, boolean opening) {
        cancelAnimation();
        window.opening = opening;
        ValueAnimator animator = ValueAnimator.ofFloat(window.progress, opening ? 1 : 0);
        animation = animator;
        animator.setDuration(opening ? 320 : Math.max(80, Math.round(320 * window.progress)));
        animator.setInterpolator(opening ? new DecelerateInterpolator(1.5f) : new AccelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> { if (animation == a && popup == window) applyReveal(window, (float) a.getAnimatedValue()); });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (animation != animator || popup != window) return;
                animation = null;
                window.opening = false;
                if (!opening) { window.dismissNow(); return; }
                applyReveal(window, 1);
                Runnable content = window.afterOpen;
                window.afterOpen = null;
                if (content != null) window.root.postOnAnimation(() -> {
                    if (popup == window && !window.closing) content.run();
                });
            }
        });
        animator.start();
    }

    private TextView text(String label, float size, float k) {
        TextView text = new TextView(context);
        styleText(text, label, size, k);
        return text;
    }
    private void styleText(TextView text, String label, float size, float k) {
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        text.setTypeface(Ui.displayMedium);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(Math.round(TEXT_LEFT * k), 0, Math.round(18 * k), 0);
        text.setSingleLine(true);
    }
    private void heading(LinearLayout root, String label, float k) {
        TextView title = text(label, 26, k);
        title.setTextColor(0xFF999999);
        root.addView(title, new LinearLayout.LayoutParams(-1, Math.round(46 * k)));
    }
    private void row(LinearLayout root, GenreCatalog.Genre genre, float k) {
        boolean checked = genre == null ? selected == null : selected != null && genre.id == selected.id;
        String label = genre == null ? AppStrings.get("tv".equals(host.kind()) ? R.string.home_tab_shows : R.string.home_tab_movies) : genre.name;
        TextView row = new MenuRow(context, checked, k);
        styleText(row, label, 34, k);
        row.setTextColor(Color.WHITE);
        row.setFocusable(true); row.setClickable(true);
        row.setOnKeyListener(this::initialRelease);
        row.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(34 * k);
            fill.setColor(focused ? Color.WHITE : Color.TRANSPARENT);
            row.setBackground(fill);
            row.setTextColor(focused ? Color.BLACK : Color.WHITE);
        });
        row.setOnClickListener(v -> choose(genre));
        root.addView(row, new LinearLayout.LayoutParams(-1, Math.round(68 * k)));
    }
    private void choose(GenreCatalog.Genre genre) {
        if (popup == null || popup.closing) return;
        if (movieRequest != null) movieRequest.cancel();
        movieRequest = null;
        if (popup != null) popup.dismiss();
        if (genre == null) {
            selected = null; movies = null;
            host.changed();
            return;
        }
        host.message(AppStrings.get(R.string.home_genre_loading));
        movieRequest = catalog.titles(host.kind(), genre, AppLanguage.current(), result -> {
            movieRequest = null;
            if (result == null || result.isEmpty()) {
                host.message(AppStrings.get(R.string.home_genre_failed));
                return; // Keep the last successful selection and posters.
            }
            selected = genre; movies = result;
            host.changed();
        });
    }
}
