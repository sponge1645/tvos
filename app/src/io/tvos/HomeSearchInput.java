package io.tvos;

import android.app.Activity;
import android.graphics.Paint;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

/** Search window/IME lifecycle; query results, navigation and glass drawing stay with the owner. */
final class HomeSearchInput {
    interface Host {
        boolean running(); String query(); void queryChanged(String query);
        void restore(); void dismissed(); void paused(); void syncGlass(View panel);
    }
    private static final class SearchIcon extends android.graphics.drawable.Drawable {
        private final android.content.Context context;
        private final float size;
        private final int color;
        private int alpha = 255;
        SearchIcon(android.content.Context context, float size, int color) {
            this.context = context; this.size = size; this.color = color;
            setBounds(0, 0, Math.max(1, (int) Math.ceil(Symbols.width(context, "search", size))),
                    Math.max(1, (int) Math.ceil(Symbols.height(context, "search", size))));
        }
        @Override public void draw(android.graphics.Canvas canvas) {
            Symbols.draw(context, canvas, "search", getBounds().exactCenterX(), getBounds().exactCenterY(), size,
                    (color & 0x00FFFFFF) | ((color >>> 24) * alpha / 255) << 24);
        }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @SuppressWarnings("deprecation") // Drawable's opacity contract is required on the supported old SDKs.
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    private final Activity activity;
    private final View owner,searchGlassLayer;
    private final Host host;
    private android.app.Dialog current;
    HomeSearchInput(Activity activity,View owner,View glass,Host host){
        this.activity=activity;this.owner=owner;this.searchGlassLayer=glass;this.host=host;
    }
    boolean showing(){return current!=null&&current.isShowing();}
    void dismiss(){if(current!=null)current.dismiss();}
    private void panelExit(android.app.Dialog dialog) {
        android.view.Window window = dialog.getWindow();
        if (window == null) { dialog.dismiss(); return; }
        android.view.View decor = window.getDecorView();
        decor.animate().cancel();
        searchGlassLayer.animate().cancel();
        searchGlassLayer.animate().alpha(0f).setDuration(220).start();
        decor.animate().alpha(0f).setDuration(220)
                .withEndAction(dialog::dismiss).start();
    }
    void open(String initialQuery,boolean dark,float centerY){
        if(showing())return;
        final android.app.Dialog dialog = new android.app.Dialog(activity) {
            private boolean closing;
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                // The IME consumes keys while it is visible. Once it has been hidden,
                // any navigation arrow dismisses the floating search, rather than trapping focus.
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                        || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                            && !closing) {
                        closing = true;
                        panelExit(this);
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            // Dialog Back handling must remain available on Android 9 TV keyboards.
            @SuppressWarnings("deprecation")
            @Override public void onBackPressed() {
                if (closing) return;
                closing = true;
                android.view.Window w = getWindow();
                if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                panelExit(this);
            }
        };
        current = dialog;
        // HOME is handled by Android, not delivered as a key event to applications.
        // Close the dialog when our activity goes to the background.
        final android.app.Application.ActivityLifecycleCallbacks searchLifecycle =
                new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity a) {
                if (a == activity && dialog.isShowing()) {
                    dialog.dismiss();
                    host.paused();
                }
            }
            @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        };
        activity.getApplication().registerActivityLifecycleCallbacks(searchLifecycle);
        final android.widget.LinearLayout panel = new android.widget.LinearLayout(activity);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (22 * activity.getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad / 2, pad, pad / 2);
        // Glass is drawn by the HomeView canvas through scene.liquid(), not a Dialog Drawable.
        // The dialog contains only a transparent text field and the system keyboard.
        panel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        final EditText input = new EditText(activity, null, 0, R.style.SearchInput);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setTextColor(dark ? 0xFFFFFFFF : 0xFF20242B);
        input.setHintTextColor(dark ? 0xAABBBBBB : 0xFF777777);
        input.setTextSize(21);
        input.setCompoundDrawablePadding(pad / 2);
        input.setCompoundDrawablesRelative(new SearchIcon(activity,
                24 * activity.getResources().getDisplayMetrics().density, dark ? 0xAABBBBBB : 0xFF777777), null, null, null);
        input.setHint(AppStrings.get(R.string.home_search_movies_and_series));
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        panel.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        // Measure the former capsule before removing its vertical padding and themed input minimum.
        // Keep 60% of that height, with enough room for the text at the user's font scale.
        int searchWidth = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.336f);
        panel.measure(View.MeasureSpec.makeMeasureSpec(searchWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Paint.FontMetricsInt searchMetrics = input.getPaint().getFontMetricsInt();
        int searchHeight = Math.max(Math.round(panel.getMeasuredHeight() * 0.60f),
                searchMetrics.bottom - searchMetrics.top
                        + Math.round(2 * activity.getResources().getDisplayMetrics().density));
        panel.setPadding(pad, 0, pad, 0);
        input.setMinimumHeight(0);
        input.setMinHeight(0);
        input.setIncludeFontPadding(false);
        input.setPadding(input.getPaddingLeft(), 0, input.getPaddingRight(), 0);
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);
        input.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, searchHeight));
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            window.setLayout(searchWidth,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }

        // Restore before attaching the watcher: reopening the same query must not clear or reload its results.
        input.setText(initialQuery);
        input.setSelection(input.length());
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                if (!query.equals(host.query())) host.queryChanged(query);
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        host.restore();
        input.setOnEditorActionListener((v, actionId, event) -> {
            panelExit(dialog);
            return true;
        });
        panel.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                panel.post(() -> host.syncGlass(panel)));
        dialog.setOnDismissListener(d -> {
            if (current == dialog) current = null;
            activity.getApplication().unregisterActivityLifecycleCallbacks(searchLifecycle);
            host.dismissed();
        });
        dialog.show();
        window = dialog.getWindow();
        if (window != null) {
            window.setLayout(searchWidth,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.y = Math.round(centerY - searchHeight / 2f);
            window.setAttributes(attrs);
        }
        panel.post(() -> {
            if (!host.running() || !dialog.isShowing()) return;
            // Match the toolbar's actual screen coordinates, including window insets.
            int[] panelPos = new int[2], homePos = new int[2];
            panel.getLocationOnScreen(panelPos);
            owner.getLocationOnScreen(homePos);
            int offset = Math.round(homePos[1] + centerY
                    - panelPos[1] - panel.getHeight() / 2f);
            android.view.Window searchWindow = dialog.getWindow();
            if (searchWindow != null && offset != 0) {
                android.view.WindowManager.LayoutParams attrs = searchWindow.getAttributes();
                attrs.y += offset;
                searchWindow.setAttributes(attrs);
            }
            panel.postOnAnimation(() -> {
                if (!dialog.isShowing()) return;
                host.syncGlass(panel);
                searchGlassLayer.setAlpha(0f);
                searchGlassLayer.animate().alpha(1f).setDuration(260).start();
            });
        });
        android.view.View decor = dialog.getWindow().getDecorView();
        decor.setAlpha(0f);
        decor.animate().alpha(1f).setDuration(260).start();
        input.requestFocus();
    }
}
