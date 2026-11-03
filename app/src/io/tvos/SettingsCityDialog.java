package io.tvos;

import android.app.AlertDialog;
import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.util.function.Consumer;

/** Same city input and IME action in Settings and first-run setup. */
final class SettingsCityDialog {
    private SettingsCityDialog() { }
    static AlertDialog show(Context context, Consumer<String> search) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setHint(AppStrings.get(R.string.settings_city_name_hint));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        AlertDialog dialog = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(AppStrings.get(R.string.settings_city)).setView(field)
                .setPositiveButton(AppStrings.get(R.string.settings_find_city), (d, which) -> search.accept(field.getText().toString()))
                .setNegativeButton(AppStrings.get(R.string.settings_cancel), null).create();
        field.setOnEditorActionListener((v, action, event) -> {
            dialog.dismiss(); search.accept(field.getText().toString()); return true;
        });
        dialog.show(); return dialog;
    }
}
