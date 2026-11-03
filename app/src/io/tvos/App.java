package io.tvos;

import android.app.Application;

/** Gives Prefs a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        java.io.File reset = new java.io.File(getDataDir(), AppDataReset.MARKER);
        if (reset.exists() && !Application.getProcessName().endsWith(":reset")) {
            // Suppress service restarts until the isolated reset process finishes.
            android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
            boolean resetting = false;
            if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo process : processes)
                if ((getPackageName() + ":reset").equals(process.processName)) resetting = true;
            if (resetting) {
                android.os.Process.killProcess(android.os.Process.myPid()); return;
            }
            // A crashed reset worker must not leave the launcher permanently blocked.
            reset.delete();
        }
        Prefs.init(this);
    }
}
