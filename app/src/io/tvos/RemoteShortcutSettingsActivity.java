package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import io.tvos.remote.RemoteBindings;
import io.tvos.remote.RemoteBindings.Binding;
import io.tvos.remote.VolumeHoldStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit per-button scope/taps editor; captures only while its capture dialog is visible. */
public class RemoteShortcutSettingsActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Binding> bindings = new ArrayList<>();
    private ListView list;
    private boolean busy, light, resumed;
    private String text(int id) { return AppStrings.get(id); }
    private AlertDialog.Builder dialog(int title) {
        return new AlertDialog.Builder(this, light ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(text(title));
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        bindings.addAll(RemoteBindings.read(getFilesDir()));
        light = !HomeData.get(this).isNight(System.currentTimeMillis());
        list = new ListView(this); list.setBackgroundColor(light ? 0xFFEAF3FA : 0xFF15232D);
        list.setOnItemClickListener((p, v, at, id) -> {
            if (busy) return;
            if (at == 0) capture();
            else if (at == 1) volumeMode();
            else if (at == 2) grantVolume();
            else if (at == 3) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            else edit(at - 4);
        });
        setContentView(list); render();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    private void render() {
        List<String> rows = new ArrayList<>();
        rows.add(text(R.string.settings_remote_add));
        rows.add(text(R.string.settings_remote_volume_method) + ": " + text(VolumeHoldStore.system(getFilesDir())
                ? R.string.settings_remote_system_method : R.string.settings_remote_adb_method));
        rows.add(text(R.string.settings_remote_grant_volume));
        rows.add(text(R.string.settings_remote_accessibility));
        for (Binding b : bindings) rows.add(name(b) + " — " + text(b.global ? R.string.settings_remote_global : R.string.settings_remote_home));
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public android.view.View getView(int at, android.view.View view, android.view.ViewGroup parent) {
                TextView text = (TextView) super.getView(at, view, parent);
                text.setTextColor(light ? 0xFF1C1C1E : 0xFFFFFFFF); text.setTextSize(20); text.setMinHeight(64); return text;
            }
        });
    }
    private String name(Binding b) { return KeyEvent.keyCodeToString(b.code) + " (" + b.device + ")"; }
    private void capture() {
        AlertDialog capture = dialog(R.string.settings_remote_press).setMessage(text(R.string.settings_remote_capture_help))
                .setNegativeButton(text(R.string.settings_remote_cancel), null).create();
        capture.setOnKeyListener((d, code, event) -> {
            if (code == KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
            if (!RemoteBindings.allowed(code)
                    || (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                Toast.makeText(this, text(R.string.settings_remote_reserved), Toast.LENGTH_SHORT).show(); return true;
            }
            String device = RemoteShortcuts.device(event);
            if (device.length() > 128) return true;
            for (int i = 0; i < bindings.size(); i++) if (bindings.get(i).matches(code, event.getScanCode(), device)) {
                capture.dismiss(); edit(i); return true;
            }
            if (bindings.size() >= RemoteBindings.MAX) return true;
            bindings.add(new Binding(code, event.getScanCode(), device, false,
                    new String[] {RemoteBindings.ok(code) ? "default" : "none", "", ""}));
            capture.dismiss(); save(); edit(bindings.size() - 1); return true;
        });
        capture.show();
    }
    private void edit(int at) {
        if (at < 0 || at >= bindings.size()) return;
        Binding b = bindings.get(at); String[] a = b.actions();
        String[] rows = {text(R.string.settings_remote_scope) + ": " + text(b.global ? R.string.settings_remote_global : R.string.settings_remote_home),
                "1× — " + label(a[0]), "2× — " + label(a[1]), "3× — " + label(a[2]), text(R.string.settings_remote_delete)};
        dialog(R.string.settings_remote_edit).setItems(rows, (d, row) -> {
            if (busy) return;
            if (row == 0) scope(at);
            else if (row == 4) { bindings.remove(at); save(); }
            else actions(at, row - 1);
        }).setNeutralButton(text(R.string.settings_remote_help), (d, w) -> dialog(R.string.settings_remote_help)
                .setMessage(text(R.string.settings_remote_gesture_help)).setPositiveButton(android.R.string.ok, (x, y) -> edit(at)).show())
                .setNegativeButton(text(R.string.settings_remote_done), null).show();
    }
    private void scope(int at) {
        Binding b = bindings.get(at);
        dialog(R.string.settings_remote_scope).setSingleChoiceItems(new String[] {text(R.string.settings_remote_home), text(R.string.settings_remote_global)},
                b.global ? 1 : 0, (d, row) -> {
                    if (row == 1 && RemoteBindings.ok(b.code)) {
                        Toast.makeText(this, text(R.string.settings_remote_ok_home), Toast.LENGTH_LONG).show(); return;
                    }
                    bindings.set(at, new Binding(b.code, b.scan, b.device, row == 1, b.actions()));
                    save(); d.dismiss(); edit(at);
                }).setNegativeButton(text(R.string.settings_remote_cancel), null).show();
    }
    private static final String[] ACTIONS = {"none", "vpn", "settings", "play", "next", "previous", "mute"};
    private String label(String action) {
        switch (action) {
            case "default": return text(R.string.settings_remote_original);
            case "vpn": return text(R.string.settings_remote_vpn);
            case "settings": return text(R.string.settings_remote_settings);
            case "play": return text(R.string.settings_remote_play);
            case "next": return text(R.string.settings_remote_next);
            case "previous": return text(R.string.settings_remote_previous);
            case "mute": return text(R.string.settings_remote_mute);
            case "": return text(R.string.settings_remote_unassigned);
            case "none": return text(R.string.settings_remote_none);
            default:
                try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(action.substring(4), 0))); }
                catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) { return action; }
        }
    }
    private void actions(int at, int tap) {
        Binding b = bindings.get(at);
        List<String> ids = new ArrayList<>(); List<String> names = new ArrayList<>();
        if (tap == 0 && !b.global && RemoteBindings.ok(b.code)) { ids.add("default"); names.add(text(R.string.settings_remote_original)); }
        if (tap > 0) { ids.add(""); names.add(text(R.string.settings_remote_unassigned)); }
        for (String a : ACTIONS) { ids.add(a); names.add(label(a)); }
        names.add(text(R.string.settings_remote_app));
        dialog(R.string.settings_remote_action).setItems(names.toArray(new String[0]), (d, row) -> {
            if (row == ids.size()) chooseApp(at, tap); else assign(at, tap, ids.get(row));
        }).setNegativeButton(text(R.string.settings_remote_cancel), null).show();
    }
    private void assign(int at, int tap, String action) {
        Binding b = bindings.get(at); String[] a = b.actions();
        a[tap] = action;
        bindings.set(at, new Binding(b.code, b.scan, b.device, b.global, a)); save(); edit(at);
    }
    private void chooseApp(int at, int tap) {
        busy = true;
        io.execute(() -> {
            List<String[]> apps = new ArrayList<>();
            try {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                for (android.content.pm.ResolveInfo app : getPackageManager().queryIntentActivities(query, 0)) {
                    String pkg = app.activityInfo.packageName;
                    boolean seen = false;
                    for (String[] old : apps) if (old[0].equals(pkg)) { seen = true; break; }
                    if (!seen) apps.add(new String[] {pkg, String.valueOf(app.loadLabel(getPackageManager()))});
                }
                apps.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
            } catch (RuntimeException error) { AppLog.w("Remote app list unavailable"); }
            runOnUiThread(() -> {
                busy = false; if (!resumed || isFinishing() || isDestroyed()) return;
                String[] names = new String[apps.size()]; for (int i = 0; i < names.length; i++) names[i] = apps.get(i)[1];
                dialog(R.string.settings_remote_app).setItems(names, (d, row) -> assign(at, tap, "app:" + apps.get(row)[0]))
                        .setNegativeButton(text(R.string.settings_remote_cancel), null).show();
            });
        });
    }
    private void save() {
        final List<Binding> snapshot = new ArrayList<>(bindings); busy = true;
        io.execute(() -> {
            boolean ok = true;
            try { RemoteBindings.write(getFilesDir(), snapshot); }
            catch (java.io.IOException error) { ok = false; }
            final boolean saved = ok;
            if (saved) getApplicationContext().sendBroadcast(new Intent(RemoteShortcuts.CHANGE).setPackage(getPackageName()));
            runOnUiThread(() -> {
                busy = false; if (!resumed || isFinishing() || isDestroyed()) return;
                if (!saved) { bindings.clear(); bindings.addAll(RemoteBindings.read(getFilesDir()));
                    Toast.makeText(this, text(R.string.settings_remote_save_failed), Toast.LENGTH_LONG).show(); }
                render();
            });
        });
    }
    private void volumeMode() {
        dialog(R.string.settings_remote_volume_method).setSingleChoiceItems(new String[] {text(R.string.settings_remote_adb_method), text(R.string.settings_remote_system_method)},
                VolumeHoldStore.system(getFilesDir()) ? 1 : 0, (d, row) -> {
                    try {
                        VolumeHoldStore.setSystem(getFilesDir(), row == 1);
                        sendBroadcast(new Intent(RemoteVolume.CHANGE).setPackage(getPackageName()));
                    } catch (java.io.IOException error) { Toast.makeText(this, text(R.string.settings_remote_save_failed), Toast.LENGTH_LONG).show(); }
                    d.dismiss(); render();
                    if (row == 1) dialog(R.string.settings_remote_system_method).setMessage(text(R.string.settings_remote_system_help))
                            .setPositiveButton(android.R.string.ok, null).show();
                }).setNegativeButton(text(R.string.settings_remote_cancel), null).show();
    }
    private void grantVolume() {
        busy = true;
        io.execute(() -> {
            try (Adb adb = Adb.connect(getApplicationContext(), 10000, null)) {
                adb.shell("pm grant io.tvos android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER");
            } catch (java.io.IOException | RuntimeException error) { AppLog.w("Volume listener grant unavailable"); }
            runOnUiThread(() -> {
                busy = false; if (!resumed || isFinishing() || isDestroyed()) return;
                boolean granted = checkSelfPermission(SystemVolume.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                sendBroadcast(new Intent(RemoteVolume.CHANGE).setPackage(getPackageName()));
                Toast.makeText(this, text(granted ? R.string.settings_remote_granted : R.string.settings_remote_grant_failed), Toast.LENGTH_LONG).show();
            });
        });
    }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
