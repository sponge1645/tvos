package io.tvos;

import io.tvos.settings.SettingsDockEditor;
import io.tvos.settings.SettingsOrderEditor;
import java.util.List;

/** Preference adapters; reusable settings models have no dependency on the launcher package. */
final class SettingsEditors {
    private SettingsEditors() { }

    static SettingsOrderEditor categories() {
        return new SettingsOrderEditor(new SettingsOrderEditor.Store() {
            @Override public int[] order() { return Prefs.categoryOrder(); }
            @Override public boolean enabled(int id) { return Prefs.categoryEnabled(id); }
            @Override public int count() { return Prefs.categoryCount(); }
            @Override public void setEnabled(int id, boolean enabled) { Prefs.setCategoryEnabled(id, enabled); }
            @Override public void saveOrder(List<Integer> order) { Prefs.setCategoryOrder(order); }
        });
    }

    static SettingsOrderEditor buttons() {
        return new SettingsOrderEditor(new SettingsOrderEditor.Store() {
            @Override public int[] order() { return Prefs.topButtonOrder(); }
            @Override public boolean enabled(int id) { return Prefs.topButtonEnabled(id); }
            @Override public int count() { return Prefs.topButtonCount(); }
            @Override public void setEnabled(int id, boolean enabled) { Prefs.setTopButtonEnabled(id, enabled); }
            @Override public void saveOrder(List<Integer> order) { Prefs.setTopButtonOrder(order); }
        });
    }

    static SettingsDockEditor dock() {
        return new SettingsDockEditor(HomeApps.MAX_SHELF, Prefs::setShelf);
    }
}
