package io.tvos;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.util.List;

/** Native focusable genre menu; the home owns only eligibility, anchor and catalog refresh. */
final class HomeGenres {
    interface Host {
        boolean eligible();
        RectF anchor();
        void opened();
        void changed();
        void message(String text);
    }
    private final View home;
    private final Context context;
    private final Handler handler;
    private final GenreCatalog catalog;
    private final Host host;
    private final Runnable hold = this::show;
    private final int[] location = new int[2];
    private PopupWindow popup;
    private UiRequest genreRequest, movieRequest;
    private ValueAnimator animation;
    private GenreCatalog.Genre selected;
    private List<Recs.Pick> movies;
    private boolean openingRelease;

    HomeGenres(View home, Handler handler, GenreCatalog catalog, Host host) {
        this.home = home; this.context = home.getContext(); this.handler = handler;
        this.catalog = catalog; this.host = host;
    }
    String label() { return selected == null ? AppStrings.get(R.string.home_tab_movies) : selected.name; }
    List<Recs.Pick> movies() { return movies; } // null means the ordinary Movies catalog.
    void arm() {
        cancelHold();
        if (host.eligible()) handler.postDelayed(hold, HomeDockEditor.HOLD_MS);
    }
    void cancelHold() { handler.removeCallbacks(hold); }
    void released() { cancelHold(); openingRelease = false; }
    void pause() {
        cancelHold();
        if (popup != null) popup.dismiss();
        if (genreRequest != null) genreRequest.cancel();
        if (movieRequest != null) movieRequest.cancel();
        genreRequest = movieRequest = null;
    }
    private boolean initialRelease(View view, int code, KeyEvent event) {
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
        heading(root, AppStrings.get(R.string.home_genre), k);
        row(root, null, k);
        for (int i = 0; i < 5; i++) {
            TextView placeholder = text(i == 0 ? AppStrings.get(R.string.home_genre_loading) : "", 26, k);
            placeholder.setTextColor(0xFF999999);
            root.addView(placeholder, new LinearLayout.LayoutParams(-1, Math.round(68 * k)));
        }
        PopupWindow window = new PopupWindow(root, Math.round(420 * k), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup = window;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        window.setElevation(16 * k);
        window.setAnimationStyle(0); // Only the content stretch below animates the entrance.
        window.setOnDismissListener(() -> {
            if (popup != window) return;
            if (animation != null) animation.cancel();
            animation = null;
            if (genreRequest != null) genreRequest.cancel();
            genreRequest = null;
            if (popup == window) popup = null;
        });
        RectF anchor = host.anchor();
        home.getLocationOnScreen(location);
        int x = location[0] + Math.round(Math.max(40 * k, Math.min(home.getWidth() - 460 * k, anchor.left)));
        int y = location[1] + Math.round(Math.max(40 * k, anchor.top - 44 * k));
        window.showAtLocation(home, Gravity.TOP | Gravity.LEFT, x, y);
        root.requestFocus();
        boolean reduced = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
                || !ValueAnimator.areAnimatorsEnabled();
        if (!reduced) {
            root.setPivotX(42 * k); root.setPivotY(36 * k);
            root.setScaleX(.86f); root.setScaleY(.12f);
            animation = ValueAnimator.ofFloat(0, 1);
            animation.setDuration(320);
            animation.setInterpolator(new OvershootInterpolator(.65f));
            animation.addUpdateListener(a -> {
                float progress = (float) a.getAnimatedValue();
                root.setScaleX(.86f + .14f * progress);
                root.setScaleY(.12f + .88f * progress);
            });
            animation.start();
        }
        genreRequest = catalog.genres(AppLanguage.current(), choices -> {
            if (popup != window) return;
            root.removeAllViews();
            heading(root, AppStrings.get(R.string.home_genre), k);
            row(root, null, k);
            if (choices == null) {
                heading(root, AppStrings.get(R.string.home_genre_failed), k);
                root.getChildAt(1).requestFocus();
            } else {
                int focus = 1;
                for (GenreCatalog.Genre genre : choices) {
                    row(root, genre, k);
                    if (selected != null && selected.id == genre.id) focus = root.getChildCount() - 1;
                }
                if (selected == null && choices.size() > 0) focus = 2;
                root.getChildAt(focus).requestFocus();
            }
            window.update();
        });
    }
    private TextView text(String label, float size, float k) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        text.setTypeface(Ui.displayMedium);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(Math.round(30 * k), 0, Math.round(18 * k), 0);
        text.setSingleLine(true);
        return text;
    }
    private void heading(LinearLayout root, String label, float k) {
        TextView title = text(label, 26, k);
        title.setTextColor(0xFF999999);
        root.addView(title, new LinearLayout.LayoutParams(-1, Math.round(46 * k)));
    }
    private void row(LinearLayout root, GenreCatalog.Genre genre, float k) {
        boolean checked = genre == null ? selected == null : selected != null && genre.id == selected.id;
        String label = genre == null ? AppStrings.get(R.string.home_tab_movies) : genre.name;
        TextView row = text((checked ? "✓ " : "   ") + label, 34, k);
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
        if (movieRequest != null) movieRequest.cancel();
        movieRequest = null;
        if (popup != null) popup.dismiss();
        if (genre == null) {
            selected = null; movies = null;
            host.changed();
            return;
        }
        host.message(AppStrings.get(R.string.home_genre_loading));
        movieRequest = catalog.movies(genre, AppLanguage.current(), result -> {
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
