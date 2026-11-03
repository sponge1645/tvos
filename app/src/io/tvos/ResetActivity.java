package io.tvos;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.util.List;

/** Runs separately so old settings/home processes cannot write data back after reset. */
public final class ResetActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        new Thread(() -> {
            File marker = new File(getDataDir(), AppDataReset.MARKER);
            boolean ok;
            try {
                if (!marker.createNewFile() && !marker.isFile()) throw new java.io.IOException("Reset marker unavailable");
                marker.setLastModified(System.currentTimeMillis());
                ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
                if (processes == null) throw new java.io.IOException("Application processes unavailable");
                for (ActivityManager.RunningAppProcessInfo process : processes)
                    if (process.uid == Process.myUid() && process.pid != Process.myPid()) Process.killProcess(process.pid);
                ok = AppDataReset.clear(getDataDir());
                File external = getExternalCacheDir();
                if (external != null && external.exists()) ok &= AppDataReset.remove(external);
                File externalFiles = getExternalFilesDir(null);
                if (externalFiles != null && externalFiles.exists()) ok &= AppDataReset.remove(externalFiles);
            } catch (java.io.IOException | RuntimeException error) { ok = false; }
            if (marker.exists() && !marker.delete()) ok = false;
            final boolean complete = ok;
            runOnUiThread(() -> {
                if (!complete) android.widget.Toast.makeText(this, AppStrings.get(R.string.settings_could_not_reset_tvos), android.widget.Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, SetupActivity.class).putExtra("replay", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 500);
            });
        }, "reset-tvos").start();
    }
}
