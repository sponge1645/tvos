package io.tvos;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/** Window-level compositing also fades SurfaceView's hardware video layer on old TVs. */
final class ScreensaverWindow {
    private ScreensaverWindow() { }
    static Activity activity(Window window) {
        if (window == null) return null;
        Context context = window.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }
    @SuppressWarnings("deprecation") // The same override must work on Android 9 through 13.
    static void prepare(Window window, boolean dreamActivity) {
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setFormat(PixelFormat.TRANSLUCENT);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.alpha = 0f;
        attrs.dimAmount = 0f;
        attrs.windowAnimations = 0;
        window.setAttributes(attrs);
        if (dreamActivity) {
            Activity activity = activity(window);
            if (activity != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        if (!activity.setTranslucent(true))
                            AppLog.w("Dream window translucency unavailable on this firmware");
                    } catch (RuntimeException error) {
                        AppLog.unexpected("Dream window translucency", error);
                    }
                }
                activity.overridePendingTransition(0, 0);
            }
        }
    }
    static void alpha(Window window, float value) {
        if (window == null) return;
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.alpha = Math.max(0f, Math.min(1f, value));
        window.setAttributes(attrs);
    }
}
