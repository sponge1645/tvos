package io.tvos;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/** Compatibility entry; the settings menu opens the controller in its own existing window. */
public final class RemoteShortcutSettingsActivity extends Activity {
    private RemoteShortcutSettings controller;
    static void open(Activity activity, View source, String title) {
        if (activity instanceof SettingsActivity) ((SettingsActivity) activity).openRemoteSettings(source, title);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this);
        controller = new RemoteShortcutSettings(this, null, "", this::finish);
        setContentView(controller.content()); controller.start();
    }
    @Override protected void onResume() { super.onResume(); if (controller != null) controller.resume(); }
    @Override protected void onPause() { if (controller != null) controller.pause(); super.onPause(); }
    @Override protected void onDestroy() { if (controller != null) controller.close(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (controller != null) controller.windowFocus(focused); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return controller != null && controller.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }
}
