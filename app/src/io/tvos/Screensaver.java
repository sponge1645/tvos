package io.tvos;

import android.service.dreams.DreamService;

/** System-driven screensaver, composited over the preceding screen without a black phase. */
public class Screensaver extends DreamService {
    private VideoDream view;
    private boolean dreaming, finishing, waking;

    @SuppressWarnings("deprecation") // DreamActivity exists before overrideActivityTransition was added.
    private void finishAfterFade() {
        if (finishing) return;
        finishing = true;
        android.app.Activity activity = ScreensaverWindow.activity(getWindow());
        finish();
        if (activity != null) activity.overridePendingTransition(0, 0);
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        ScreensaverWindow.prepare(getWindow(), true);
        view = new VideoDream(this);
        view.useWindowAlpha(value -> ScreensaverWindow.alpha(getWindow(), value));
        view.setOnOpaqueListener(() -> HomeDreamReturn.opaque(this));
        setContentView(view);
    }

    @SuppressWarnings("deprecation")
    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true; finishing = waking = false;
        if (view != null) view.post(() -> {
            if (!dreaming || waking) return;
            android.app.Activity activity = ScreensaverWindow.activity(getWindow());
            if (activity != null) activity.overridePendingTransition(0, 0);
            view.start(); // Window remains transparent until the first media frame is ready.
        });
    }
    @Override public void onWakeUp() {
        waking = true;
        if (view != null) view.fadeExit(this::finishAfterFade);
        else finishAfterFade();
    }
    @Override public void onDreamingStopped() {
        dreaming = false;
        if (view != null) view.stop();
        super.onDreamingStopped();
    }
    @Override public void onDetachedFromWindow() {
        dreaming = false;
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
