package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Hardware-button assignments through Android's IME pipeline; volume stays with firmware. */
public class RemoteInputMethod extends InputMethodService {
    private RemoteShortcuts shortcuts;
    private String targetPackage = "";

    static void choose(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(AppStrings.get(R.string.settings_remote_input))
                .setMessage(AppStrings.get(R.string.settings_remote_input_help))
                .setPositiveButton(AppStrings.get(R.string.settings_remote_input_enable), (d, w) ->
                        activity.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)))
                .setNeutralButton(AppStrings.get(R.string.settings_remote_input_choose), (d, w) -> {
                    InputMethodManager manager = (InputMethodManager) activity.getSystemService(INPUT_METHOD_SERVICE);
                    if (manager != null) manager.showInputMethodPicker();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
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
        return shortcuts != null && shortcuts.onKey(event) || RemoteKeys.onKey(this, event);
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

    @Override public boolean onEvaluateInputViewShown() { return true; }
    @Override public boolean onShowInputRequested(int flags, boolean configChange) {
        if (RemoteKeyboardBridge.coolingDown()) return false;
        RemoteKeyboardBridge.request(this);
        return true;
    }
    @Override public View onCreateInputView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 12, 24, 12);
        panel.setBackgroundColor(0xFF202B39);
        TextView message = new TextView(this);
        message.setText(AppStrings.get(R.string.settings_remote_keyboard_connecting));
        message.setTextColor(0xFFFFFFFF); message.setTextSize(18);
        panel.addView(message);
        Button picker = new Button(this);
        picker.setText(AppStrings.get(R.string.settings_remote_input_choose));
        picker.setOnClickListener(v -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showInputMethodPicker();
        });
        panel.addView(picker);
        return panel;
    }
    @Override public void onDestroy() {
        if (shortcuts != null) { shortcuts.close(); shortcuts = null; }
        super.onDestroy();
    }
}
