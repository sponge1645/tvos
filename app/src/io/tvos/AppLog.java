package io.tvos;

import android.util.Log;

/** The app's log (adb logcat -s tvOS). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("tvOS", message);
    }
    static boolean artworkEnabled() { return Log.isLoggable("tvOS-Artwork", Log.DEBUG); }
    static void artwork(String message) {
        if (artworkEnabled()) Log.d("tvOS-Artwork", message.replace('\n', ' ').replace('\r', ' '));
    }
    static void artworkError(String operation, Exception failure) {
        if (!artworkEnabled()) return;
        String message = failure.getMessage();
        String reason = message != null && (message.matches("TMDB returned HTTP [0-9]{3}")
                || message.equals("TMDB rejected the key") || message.equals("no TMDB key"))
                ? message : failure.getClass().getSimpleName();
        artwork(operation + ": " + reason);
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
        Log.e("tvOS", message.toString());
    }
}
