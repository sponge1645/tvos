package io.tvos;

import android.service.dreams.DreamService;

/**
 * The tvOS screensaver (enabled in tvOS mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private VideoDream view;
    private boolean dreaming;

    private android.app.Activity dreamActivity() {
        if (getWindow() == null) return null;
        android.content.Context context = getWindow().getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof android.app.Activity) return (android.app.Activity) context;
            android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    private void finishWithFade() {
        android.app.Activity activity = dreamActivity();
        finish();
        // DreamActivity supplies its own animations; override after its finishAndRemoveTask call.
        if (activity != null) activity.overridePendingTransition(0, R.anim.screensaver_window_exit);
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        view = new VideoDream(this);
        setContentView(view);
        // Older Android versions use a dream window without DreamActivity.
        if (getWindow() != null) {
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.windowAnimations = R.style.ScreensaverWindowAnimations;
            getWindow().setAttributes(attrs);
        }
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (view != null) view.post(() -> {
            if (!dreaming) return;
            android.app.Activity activity = dreamActivity();
            if (activity != null) activity.overridePendingTransition(R.anim.screensaver_window_enter, 0);
            view.startAfterWindowFade();
        });
    }

    @Override public void onWakeUp() {
        if (view != null) view.fadeExit(this::finishWithFade);
        else finishWithFade();
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
