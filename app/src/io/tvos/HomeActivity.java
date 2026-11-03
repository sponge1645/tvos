package io.tvos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The tvOS home screen (Android's "Home" role in tvOS mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private RemoteShortcuts shortcuts;
    private HomeDreamReturn dreamReturn;
    private HomeUpdatePrompt updates;

    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RegionVpnRecovery.request(this);
        Watchdog.install(this);
        ChiqBootGuard.homeCreated(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(AppStrings.get(R.string.runtime_tvos_s_home_screen_ran_into_a_problem_ngoing_back_to_the_tv_s_ori));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        updates = new HomeUpdatePrompt(this, () -> hasWindowFocus() && !SetupGate.blocking(this));
        shortcuts = new RemoteShortcuts(this, false, event -> { if (home != null) home.onKey(event); });
        setContentView(home);
        dreamReturn = new HomeDreamReturn(this, home::prepareDreamReturn, home::revealFromDream, home::cancelDreamReturn);
        getWindow().setBackgroundDrawable(null);
        WifiPermission.requestOnce(this);
    }

    @Override protected void onStart() {
        super.onStart();
        if (dreamReturn != null) dreamReturn.started();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && shortcuts != null) shortcuts.cancel();
        if (dreamReturn != null) dreamReturn.focused(hasFocus);
        if (hasFocus && updates != null) updates.changed();
    }

    @Override protected void onStop() {
        if (dreamReturn != null) dreamReturn.stopped();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        runWatchNextProbe(getIntent());
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (shortcuts != null) shortcuts.reload();
        if (home != null) {
            HomeData.get(this).localizeCity(Prefs.language());
            home.resume();
            if (dreamReturn != null) dreamReturn.resumed();
            if (updates != null) updates.resume();
        }
        SetupGate.route(this);
    }

    @Override protected void onPause() {
        if (shortcuts != null) shortcuts.cancel();
        if (dreamReturn != null) dreamReturn.paused();
        if (updates != null) updates.pause();
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (shortcuts != null) shortcuts.cancel();
        if (runWatchNextProbe(intent)) return;
        if (intent.getBooleanExtra("io.tvos.RESTART_HOME", false)) {
            setIntent(intent);
            HomeData.get(this).settingsChanged();
            recreate();
            return;
        }
        if (home != null) home.reset(true);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == WatchingCatalog.PERMISSION_REQUEST && home != null) home.refreshWatching();
    }

    private boolean runWatchNextProbe(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("io.tvos.PROBE_WATCH_NEXT", false)) return false;
        intent.removeExtra("io.tvos.PROBE_WATCH_NEXT");
        WatchNextProbe.run(getApplicationContext());
        return true;
    }

    @Override protected void onDestroy() {
        if (updates != null) updates.pause();
        if (shortcuts != null) shortcuts.close();
        if (dreamReturn != null) dreamReturn.close();
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (shortcuts != null && shortcuts.onKey(event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
