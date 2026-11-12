package io.tvos;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

/** Shared launch boundary for both button-interception entry points. */
final class RemoteAccessibility {
    private RemoteAccessibility() { }
    static void open(Context context) {
        if (!AppLaunch.start(context, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Remote accessibility settings"))
            Toast.makeText(context, AppStrings.get(R.string.remote_accessibility_unavailable), Toast.LENGTH_SHORT).show();
    }
}
