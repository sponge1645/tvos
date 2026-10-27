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
    private ScreensaverFade windowFade;
    private boolean finishing, waking;

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

    // Avoid a second system fade after our window has already become transparent.
    @SuppressWarnings("deprecation")
    private void finishWindow() {
        android.app.Activity activity = dreamActivity();
        finish();
        if (activity != null) activity.overridePendingTransition(0, 0);
    }

    // DreamActivity transition overrides must also work before Android 14.
    @SuppressWarnings("deprecation")
    private void finishWithFade() {
        android.app.Activity activity = dreamActivity();
        if (finishing) return;
        finishing = true;
        if (windowFade != null) {
            windowFade.start(0f, VideoDream.TRANSITION_MS, this::finishWindow);
            return;
        }
        finish();
        // DreamActivity supplies its own animations; override after its finishAndRemoveTask call.
        if (activity != null) activity.overridePendingTransition(0, R.anim.screensaver_window_exit);
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (windowFade != null) windowFade.cancel();
        windowFade = null;
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        // Install the hidden first frame before attaching the black content/SurfaceView.
        // Window alpha covers video surfaces as well as the ordinary View hierarchy.
        if (getWindow() != null) {
            boolean controlled = android.os.Build.VERSION.SDK_INT <= 29;
            android.app.Activity activity = dreamActivity();
            if (!controlled && activity != null) {
                try {
                    controlled = activity.setTranslucent(true);
                    if (!controlled) AppLog.w("DreamActivity refused translucency; using native fade");
                } catch (RuntimeException e) {
                    AppLog.unexpected("DreamActivity translucency", e);
                }
            }
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.windowAnimations = controlled ? 0 : R.style.ScreensaverWindowAnimations;
            attrs.alpha = controlled ? 0f : 1f;
            if (controlled) {
                windowFade = new ScreensaverFade(getWindow().getDecorView(), value -> {
                    if (getWindow() == null) return;
                    android.view.WindowManager.LayoutParams current = getWindow().getAttributes();
                    current.alpha = value;
                    getWindow().setAttributes(current);
                }, 0f);
            }
            getWindow().setAttributes(attrs);
            android.util.Log.i("tvOS-Dream", "Attached SDK=" + android.os.Build.VERSION.SDK_INT
                    + " windowFade=" + controlled
                    + " animatorsEnabled=" + android.animation.ValueAnimator.areAnimatorsEnabled());
        }
        view = new VideoDream(this);
        view.setOnOpaqueListener(() -> HomeDreamReturn.opaque(this));
        setContentView(view);
    }

    // Run our window fade after the system has dispatched the visible dream start.
    @SuppressWarnings("deprecation")
    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        finishing = false;
        waking = false;
        if (view != null) view.post(() -> {
            if (!dreaming || waking) return;
            android.app.Activity activity = dreamActivity();
            if (activity != null) activity.overridePendingTransition(
                    windowFade == null ? R.anim.screensaver_window_enter : 0, 0);
            if (windowFade != null)
                windowFade.start(1f, VideoDream.TRANSITION_MS, null);
            view.startAfterWindowFade();

        });
    }

    @Override public void onWakeUp() {
        waking = true;
        if (view != null) view.fadeExit(this::finishWithFade);
        else finishWithFade();
    }

    @Override public void onDreamingStopped() {
        dreaming = false;
        if (windowFade != null) windowFade.cancel();
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        dreaming = false;
        if (windowFade != null) windowFade.cancel();
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
