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
    private HomeDreamReturn dreamReturn;
    private final SelfUpdate.Listener updateListener = this::noticeUpdate;

    private void noticeUpdate() {
        SelfUpdate.Status update = SelfUpdate.get(this).status();
        if (update.kind != SelfUpdate.READY || update.release == null || isFinishing() || isDestroyed()) return;
        android.content.SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        if (prefs.getLong("self_update_notified", 0) == update.release.versionCode) return;
        prefs.edit().putLong("self_update_notified", update.release.versionCode).apply();
        android.widget.Toast.makeText(this, AppStrings.get(R.string.runtime_tvos_update_downloaded_install_it_in_software_updates), android.widget.Toast.LENGTH_LONG).show();
    }
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        setContentView(home);
        dreamReturn = new HomeDreamReturn(this, home::revealFromDream);
        getWindow().setBackgroundDrawable(null);
        WifiPermission.requestOnce(this);
    }

    @Override protected void onStart() {
        super.onStart();
        if (dreamReturn != null) dreamReturn.started();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (dreamReturn != null) dreamReturn.focused(hasFocus);
    }

    @Override protected void onStop() {
        if (dreamReturn != null) dreamReturn.stopped();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) {
            HomeData.get(this).localizeCity(Prefs.language());
            home.resume();
            if (dreamReturn != null) dreamReturn.resumed();
            SelfUpdate.get(this).addListener(updateListener);
            noticeUpdate();
            SelfUpdate.get(this).maybeAutomatic(false);
        }
    }

    @Override protected void onPause() {
        if (dreamReturn != null) dreamReturn.paused();
        SelfUpdate.get(this).removeListener(updateListener);
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("io.tvos.RESTART_HOME", false)) {
            setIntent(intent);
            HomeData.get(this).settingsChanged();
            recreate();
            return;
        }
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (dreamReturn != null) dreamReturn.close();
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
