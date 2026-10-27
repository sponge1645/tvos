package io.tvos;

import android.service.dreams.DreamService;

/**
 * The tvOS screensaver (enabled in tvOS mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private VideoDream view;
    private android.view.View entrance;
    private final Runnable startContentTask = this::startContent;
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

    /** DreamService replaces Window.Callback; setAttributes alone does not relayout its window. */
    private void applyWindowAttributes(android.view.WindowManager.LayoutParams attrs) {
        android.view.Window window = getWindow();
        if (window == null) return;
        window.setAttributes(attrs);
        android.view.View decor = window.getDecorView();
        android.view.WindowManager manager = window.getWindowManager();
        if (manager != null && decor.isAttachedToWindow()) manager.updateViewLayout(decor, attrs);
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
        if (view != null) view.stop();
        view = null;
        if (entrance != null) entrance.removeCallbacks(startContentTask);
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
                    applyWindowAttributes(current);
                }, 0f);
            }
            applyWindowAttributes(attrs);
            android.util.Log.i("tvOS-Dream", "Attached SDK=" + android.os.Build.VERSION.SDK_INT
                    + " windowFade=" + controlled
                    + " attached=" + getWindow().getDecorView().isAttachedToWindow()
                    + " animatorsEnabled=" + android.animation.ValueAnimator.areAnimatorsEnabled());
        }
        // Match the in-place preview: do not attach a SurfaceView until the
        // previous screen has completely faded to black. Its separate surface
        // can otherwise punch a black hole through the entering dream window.
        entrance = new android.view.View(this);
        entrance.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(entrance);
    }

    // Run our window fade after the system has dispatched the visible dream start.
    @SuppressWarnings("deprecation")
    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        finishing = false;
        waking = false;
        if (entrance != null) entrance.post(() -> {
            if (!dreaming || waking) return;
            android.app.Activity activity = dreamActivity();
            if (activity != null) activity.overridePendingTransition(
                    windowFade == null ? R.anim.screensaver_window_enter : 0, 0);
            android.util.Log.i("tvOS-Dream", "Black entrance started");
            if (windowFade != null)
                windowFade.start(1f, VideoDream.TRANSITION_MS, startContentTask);
            else entrance.postDelayed(startContentTask, VideoDream.TRANSITION_MS);

        });
    }

    /** Only create the decoder surface once the window's black entrance is complete. */
    private void startContent() {
        if (!dreaming || waking || finishing || view != null) return;
        view = new VideoDream(this);
        view.setOnOpaqueListener(() -> HomeDreamReturn.opaque(this));
        setContentView(view);
        view.start(); // The window is already black; do not add another entrance delay.
        android.util.Log.i("tvOS-Dream", "Content attached after black entrance");
    }

    @Override public void onWakeUp() {
        waking = true;
        if (entrance != null) entrance.removeCallbacks(startContentTask);
        if (view != null) view.fadeExit(this::finishWithFade);
        else finishWithFade();
    }

    @Override public void onDreamingStopped() {
        dreaming = false;
        if (entrance != null) entrance.removeCallbacks(startContentTask);
        if (windowFade != null) windowFade.cancel();
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        dreaming = false;
        if (entrance != null) entrance.removeCallbacks(startContentTask);
        if (windowFade != null) windowFade.cancel();
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
