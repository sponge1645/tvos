package io.tvos;

import android.util.Log;

/** The app's log (adb logcat -s FreeMyTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("FreeMyTV", message);
    }
    /** Diagnose unexpected code/platform faults without logging response bodies, URLs or keys. */
    static void unexpected(String operation, RuntimeException failure) {
        StringBuilder message = new StringBuilder(operation);
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            message.append("\n").append(cause.getClass().getName());
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(16, frames.length); i++)
                message.append("\n  at ").append(frames[i]);
        }
        Log.e("FreeMyTV", message.toString());
    }
}
