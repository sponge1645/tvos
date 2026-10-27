package io.tvos;

import android.app.Dialog;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/** A separate translucent window lets hardware video crossfade over the unchanged settings window. */
final class SettingsPreview {
    private final Context context;
    private final View view;
    private final Runnable resume;
    private final FrameLayout previewHost;
    private VideoDream preview;
    private Dialog dialog;
    private boolean closing;

    SettingsPreview(Context context, View view, Runnable resume) {
        this.context = context; this.view = view; this.resume = resume;
        previewHost = new FrameLayout(context);
        previewHost.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }
    FrameLayout host() { return previewHost; }
    boolean active() { return preview != null; }
    boolean hasDream() { return preview != null; }
    boolean onKey(KeyEvent event) {
        if (preview == null) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BACK:
                if (!closing && event.getAction() == KeyEvent.ACTION_UP) {
                    closing = true;
                    view.setVisibility(View.VISIBLE);
                    resume.run(); // Restore settings before the dream window starts revealing them.
                    preview.fadeExit(this::close);
                }
                return true;
            default: return false;
        }
    }
    void show() {
        if (preview != null) return;
        closing = false;
        Dialog window = new Dialog(context, R.style.ScreensaverPreview) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return SettingsPreview.this.onKey(event) || super.dispatchKeyEvent(event);
            }
            @SuppressWarnings("deprecation") // Back routing for old TV keyboards.
            @Override public void onBackPressed() {
                SettingsPreview.this.onKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            }
        };
        dialog = window;
        VideoDream dream = new VideoDream(context);
        preview = dream;
        ScreensaverWindow.prepare(window.getWindow(), false);
        dream.useWindowAlpha(alpha -> ScreensaverWindow.alpha(window.getWindow(), alpha));
        dream.setOnOpaqueListener(() -> {
            if (preview == dream && !closing) view.setVisibility(View.INVISIBLE);
        });
        window.setContentView(dream);
        window.setOnDismissListener(ignored -> { if (dialog == window) close(); });
        window.show();
        if (window.getWindow() != null) window.getWindow().setLayout(-1, -1);
        dream.start();
    }
    void close() {
        if (preview == null && dialog == null) return;
        VideoDream old = preview; Dialog window = dialog;
        preview = null; dialog = null; closing = false;
        if (old != null) old.stop();
        if (window != null) window.dismiss();
        view.setVisibility(View.VISIBLE);
        resume.run();
    }
}
