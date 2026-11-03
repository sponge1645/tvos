package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

/** Hardware-button assignments through Android's IME pipeline; volume stays with firmware. */
public class RemoteInputMethod extends InputMethodService {
    private RemoteShortcuts shortcuts;
    private String targetPackage = "";
    private boolean russian;

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
        russian = "ru".equals(Prefs.language());
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

    // A real input method must still support search/text entry while it is selected.
    @Override public View onCreateInputView() {
        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setPadding(12, 8, 12, 8);
        keyboard.setBackgroundColor(0xFF202B39);
        String[] lines = russian
                ? new String[]{"1234567890", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё"}
                : new String[]{"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm.,"};
        for (String line : lines) {
            LinearLayout row = row(keyboard);
            for (int i = 0; i < line.length(); i++) {
                final String value = line.substring(i, i + 1);
                key(row, value, () -> { InputConnection input = getCurrentInputConnection(); if (input != null) input.commitText(value, 1); });
            }
        }
        LinearLayout row = row(keyboard);
        key(row, russian ? "EN" : "RU", () -> { russian = !russian; setInputView(onCreateInputView()); });
        key(row, "⇧", () -> {
            // Toggle caps on the currently shown alphabet without changing stored text.
            caps = !caps; setInputView(onCreateInputView());
        });
        key(row, "␣", () -> { InputConnection input = getCurrentInputConnection(); if (input != null) input.commitText(" ", 1); });
        key(row, "⌫", () -> { InputConnection input = getCurrentInputConnection(); if (input != null) input.deleteSurroundingText(1, 0); });
        key(row, "OK", () -> { if (!sendDefaultEditorAction(true)) sendKeyChar('\n'); });
        key(row, "⌨", () -> { InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (manager != null) manager.showInputMethodPicker(); });
        return keyboard;
    }
    private boolean caps;
    private LinearLayout row(LinearLayout keyboard) {
        LinearLayout row = new LinearLayout(this);
        keyboard.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }
    private void key(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(caps && label.length() == 1 ? label.toUpperCase(java.util.Locale.ROOT) : label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            if (caps && label.length() == 1 && Character.isLetter(label.charAt(0))) {
                InputConnection input = getCurrentInputConnection();
                if (input != null) input.commitText(label.toUpperCase(java.util.Locale.ROOT), 1);
            } else action.run();
        });
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    @Override public void onDestroy() {
        if (shortcuts != null) { shortcuts.close(); shortcuts = null; }
        super.onDestroy();
    }
}
