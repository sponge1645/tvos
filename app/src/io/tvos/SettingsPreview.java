package io.tvos;

import android.content.Context;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/** In-place screensaver preview, black-cover transitions and forced lifecycle cleanup. */
final class SettingsPreview {
    private final Context context;
    private final View view;
    private final Runnable resume;
    private final FrameLayout previewHost;
    private final View previewCover;
    private final ScreensaverFade previewFade;
    private boolean previewTransition;
    private VideoDream preview;

    SettingsPreview(Context context, View view, Runnable resume) {
        this.context = context;
        this.view = view;
        this.resume = resume;
        previewHost = new FrameLayout(context);
        previewHost.setBackgroundColor(Color.BLACK);
        previewHost.addView(view, new FrameLayout.LayoutParams(-1, -1));
        previewCover = new View(context);
        previewCover.setBackgroundColor(Color.BLACK);
        previewCover.setAlpha(0f);
        previewCover.setVisibility(View.GONE);
        previewFade = new ScreensaverFade(previewCover);
        previewHost.addView(previewCover, new FrameLayout.LayoutParams(-1, -1));
    }

    FrameLayout host() { return previewHost; }
    boolean active() { return preview != null || previewTransition; }
    boolean hasDream() { return preview != null; }

    boolean onKey(KeyEvent event) {
        if (previewTransition) return true;
        if (preview == null) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_UP) preview.fadeExit(this::finishPreview);
                return true;
            default: return false;
        }
    }

    void show() {
        if (preview != null || previewTransition) return;
        previewTransition = true;
        previewFade.cancel();
        previewCover.setVisibility(View.VISIBLE);
        previewCover.setAlpha(0f);
        // Keep settings on screen until they have faded completely to black.
        previewFade.start(1f, VideoDream.TRANSITION_MS, () -> {
            view.setVisibility(View.GONE);
            preview = new VideoDream(context);
            previewHost.addView(preview, 1, new android.widget.FrameLayout.LayoutParams(-1, -1));
            preview.start();
            // VideoDream starts black and reveals only ready content, with the same fade.
            previewCover.setVisibility(View.GONE);
            previewTransition = false;
        });
    }

    private void finishPreview() {
        if (preview == null || previewTransition) return;
        previewTransition = true;
        // Replace the black dream with settings underneath another fully opaque black cover.
        previewFade.cancel();
        previewCover.setAlpha(1f);
        previewCover.setVisibility(View.VISIBLE);
        preview.stop();
        previewHost.removeView(preview);
        preview = null;
        view.setVisibility(View.VISIBLE);
        resume.run();
        previewFade.start(0f, VideoDream.TRANSITION_MS, () -> {
            previewCover.setVisibility(View.GONE);
            previewTransition = false;
        });
    }

    void close() {
        if (preview == null && !previewTransition) return;
        previewFade.cancel();
        previewTransition = false;
        if (preview != null) {
            preview.stop();
            previewHost.removeView(preview);
            preview = null;
        }
        previewCover.setVisibility(View.GONE);
        previewCover.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        resume.run();
    }

}
