package io.tvos;

import android.app.Activity;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

/** Hardware-button assignments through Android's IME pipeline; volume stays with firmware. */
public class RemoteInputMethod extends InputMethodService {
    private RemoteShortcuts shortcuts;
    private String targetPackage = "";

    static void choose(Activity activity) {
        activity.startActivity(new Intent(activity, RemoteShortcutSettingsActivity.class).putExtra("input_options", true));
    }
    @Override public void onCreate() {
        super.onCreate();
        shortcuts = new RemoteShortcuts(this, true, null);
    }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        targetPackage = info == null || info.packageName == null ? "" : info.packageName;
        if (shortcuts != null) shortcuts.cancel();
    }
    private boolean handle(KeyEvent event) {
        // Home handles its own bindings. Settings' capture dialog must receive originals.
        if (targetPackage.isEmpty() || getPackageName().equals(targetPackage)) return false;
        // Firmware receives original volume/power/navigation keys, including repeats and releases.
        if (!io.tvos.remote.RemoteBindings.allowed(event.getKeyCode())) return false;
        return shortcuts != null && shortcuts.onKey(event);
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        return handle(event) || super.onKeyDown(code, event);
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        return handle(event) || super.onKeyUp(code, event);
    }
    @Override public void onFinishInput() {
        targetPackage = "";
        if (shortcuts != null) shortcuts.cancel();
        super.onFinishInput();
    }
    @Override public boolean onEvaluateFullscreenMode() { return false; }

    // Keep the show request for the destination IME without drawing or resizing for our own window.
    @Override public boolean onEvaluateInputViewShown() { return false; }
    @Override public boolean onShowInputRequested(int flags, boolean configChange) {
        if (RemoteKeyboardBridge.coolingDown()) return false;
        return RemoteKeyboardBridge.request(this);
    }
    @Override public View onCreateInputView() { return null; }
    @Override public View onCreateCandidatesView() { return null; }
    @Override public void onWindowShown() {
        super.onWindowShown();
        android.view.Window window = getWindow().getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }
    }
    @Override public void onComputeInsets(Insets out) {
        super.onComputeInsets(out);
        int height = getWindow().getWindow().getDecorView().getHeight();
        out.contentTopInsets = out.visibleTopInsets = height;
        out.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
        out.touchableRegion.setEmpty();
    }
    @Override public void onDestroy() {
        if (shortcuts != null) { shortcuts.close(); shortcuts = null; }
        super.onDestroy();
    }
}
