package io.tvos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import io.tvos.remote.KeyboardHandoff;
import io.tvos.remote.RemoteBindings;
import io.tvos.remote.RemoteBindings.Binding;
import io.tvos.settings.SettingsBackground;
import io.tvos.settings.SettingsLogoRenderer;
import io.tvos.settings.SettingsRemoteInput;
import io.tvos.settings.SettingsRowRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small tvOS settings flow: button -> gesture -> action. Learning never executes mappings. */
public class RemoteShortcutSettingsActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Binding> bindings = new ArrayList<>();
    private final SettingsRemoteInput input = new SettingsRemoteInput();
    private RemoteShortcuts shortcuts;
    private Menu view;
    private Click click;
    private boolean busy, resumed, capturing;
    private KeyEvent learned;
    private boolean captureBackDown;
    private int generation;
    private Runnable back;
    private String text(int id) { return AppStrings.get(id); }
    private void message(int id) { Toast.makeText(this, text(id), Toast.LENGTH_SHORT).show(); }
    private static final class Row {
        final String label, value;
        final Runnable action;
        final boolean checked;
        String shownLabel, shownValue;
        final Spring focus = new Spring(.26f, .85f, 0);
        Row(String label, String value, boolean checked, Runnable action) {
            this.label = label; this.value = value; this.checked = checked; this.action = action;
        }
    }
    private Row row(String label, String value, Runnable action) { return new Row(label, value, false, action); }
    private Row row(int label, Runnable action) { return row(text(label), "", action); }
    private void show(String title, String hint, List<Row> rows, Runnable previous, int selected) {
        generation++; capturing = false; learned = null; captureBackDown = false; input.reset();
        if (shortcuts != null) shortcuts.cancel();
        back = previous; view.show(title, hint, rows, selected);
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); Ui.init(this);
        bindings.addAll(RemoteBindings.read(getFilesDir()));
        shortcuts = new RemoteShortcuts(this, true, null); click = new Click(this);
        view = new Menu(); setContentView(view);
        if (getIntent().getBooleanExtra("input_options", false)) connection(); else root();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (shortcuts != null) shortcuts.reload(); view.invalidate(); }
    @Override protected void onPause() {
        resumed = false; input.reset(); learned = null; captureBackDown = false;
        if (shortcuts != null) shortcuts.cancel(); super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) { input.reset(); learned = null; captureBackDown = false; if (shortcuts != null) shortcuts.cancel(); }
    }
    @Override protected void onDestroy() {
        generation++; io.shutdown();
        if (shortcuts != null) shortcuts.close(); if (click != null) click.release();
        view.background.clear(); super.onDestroy();
    }
    private void goBack() { if (busy) return; click.back(); if (back == null) finish(); else back.run(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (capturing) return learn(event);
        if (shortcuts != null && shortcuts.onKey(event) || RemoteKeys.onKey(this, event)) return true;
        if (input.onKey(event, false, new SettingsRemoteInput.Actions() {
            @Override public void vertical(int direction, boolean down, boolean first) { if (down && !busy) view.move(direction); }
            @Override public void horizontal(int direction, boolean down, boolean first) { }
            @Override public void confirm() { if (!busy && view.spoken.keyClick()) view.activate(); }
            @Override public void back(boolean activate, boolean root) { if (activate) goBack(); }
        })) return true;
        return super.dispatchKeyEvent(event);
    }
    private void root() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < bindings.size(); i++) {
            final int at = i; Binding b = bindings.get(i);
            String value = b.actions()[1].isEmpty() && b.actions()[2].isEmpty()
                    ? label(b.actions()[0]) : text(R.string.remote_multiple);
            rows.add(row(name(b), value, () -> edit(at)));
        }
        rows.add(row(R.string.settings_remote_add, this::capture));
        rows.add(row(R.string.remote_input_options, this::connection));
        show(text(R.string.remote_buttons), text(R.string.remote_root_hint), rows, null, 0);
    }
    private String name(Binding b) {
        if (RemoteBindings.ok(b.code)) return "OK";
        if (b.scan == 400 && "Xiaomi RC".equalsIgnoreCase(b.device)) return "MI";
        if (b.code == Prefs.KEY_NETFLIX || b.code == 265 && b.scan == 161) return "Netflix";
        if (b.code == Prefs.KEY_YOUTUBE || b.code == 4065 || b.code == 247 && b.scan == 96) return "YouTube";
        if (b.code == Prefs.KEY_PRIME || b.code == 249 && b.scan == 500) return "Prime Video";
        if (b.code == Prefs.KEY_TCL_CHANNEL) return "TCL Channel";
        switch (b.code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return text(R.string.settings_remote_play);
            case KeyEvent.KEYCODE_MEDIA_NEXT: return text(R.string.settings_remote_next);
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return text(R.string.settings_remote_previous);
            case KeyEvent.KEYCODE_SEARCH: return text(R.string.home_search);
            case KeyEvent.KEYCODE_ASSIST: case KeyEvent.KEYCODE_VOICE_ASSIST: return text(R.string.remote_voice);
            case KeyEvent.KEYCODE_PROG_RED: return text(R.string.remote_red);
            case KeyEvent.KEYCODE_PROG_GREEN: return text(R.string.remote_green);
            case KeyEvent.KEYCODE_PROG_YELLOW: return text(R.string.remote_yellow);
            case KeyEvent.KEYCODE_PROG_BLUE: return text(R.string.remote_blue);
            default: return text(R.string.remote_button) + " " + b.code;
        }
    }
    private void capture() {
        List<Row> rows = new ArrayList<>();
        show(text(R.string.settings_remote_add), text(R.string.remote_capture_hint), rows, this::root, 0);
        capturing = true; view.announceForAccessibility(text(R.string.settings_remote_press));
    }
    private boolean learn(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) captureBackDown = true;
            else if (event.getAction() == KeyEvent.ACTION_UP) { boolean cancel = captureBackDown && !event.isCanceled(); captureBackDown = false; if (cancel) goBack(); }
            return true;
        }
        if (!RemoteBindings.allowed(code)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) message(R.string.settings_remote_reserved);
            return code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_MUTE;
        }
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) learned = new KeyEvent(event);
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || learned == null) return true;
        KeyEvent down = learned; learned = null;
        if (event.isCanceled() || down.getKeyCode() != code || down.getDeviceId() != event.getDeviceId()
                || down.getScanCode() != event.getScanCode()) return true;
        String device = RemoteShortcuts.device(event); if (device.length() > 128) return true;
        for (int i = 0; i < bindings.size(); i++) if (bindings.get(i).matches(code, event.getScanCode(), device)) { edit(i); return true; }
        if (bindings.size() >= RemoteBindings.MAX) { message(R.string.remote_limit); return true; }
        bindings.add(new Binding(code, event.getScanCode(), device, !RemoteBindings.ok(code),
                new String[]{RemoteBindings.ok(code) ? "default" : "none", "", ""}));
        int at = bindings.size() - 1; capturing = false; save(() -> edit(at)); return true;
    }
    private void edit(int at) {
        if (at < 0 || at >= bindings.size()) { root(); return; }
        Binding b = bindings.get(at); String[] actions = b.actions(); List<Row> rows = new ArrayList<>();
        rows.add(row(text(R.string.remote_works), text(b.global ? R.string.remote_everywhere : R.string.remote_on_home), () -> scope(at)));
        int[] taps = {R.string.remote_single, R.string.remote_double, R.string.remote_triple};
        for (int i = 0; i < 3; i++) { final int tap = i; rows.add(row(text(taps[i]), label(actions[i]), () -> actions(at, tap))); }
        rows.add(row(R.string.settings_remote_delete, () -> delete(at)));
        show(name(b), "", rows, this::root, 1);
    }
    private void scope(int at) {
        Binding b = bindings.get(at); List<Row> rows = new ArrayList<>();
        for (int i = 0; i < (RemoteBindings.ok(b.code) ? 1 : 2); i++) {
            final boolean global = i == 1;
            rows.add(new Row(text(global ? R.string.remote_everywhere : R.string.remote_on_home), "", b.global == global, () -> {
                bindings.set(at, new Binding(b.code, b.scan, b.device, global, b.actions())); save(() -> edit(at));
            }));
        }
        show(text(R.string.remote_works), RemoteBindings.ok(b.code) ? text(R.string.settings_remote_ok_home) : "", rows, () -> edit(at), b.global ? 1 : 0);
    }
    private void delete(int at) {
        List<Row> rows = new ArrayList<>();
        rows.add(row(R.string.settings_remote_delete, () -> { bindings.remove(at); save(this::root); }));
        rows.add(row(R.string.settings_remote_cancel, () -> edit(at)));
        show(text(R.string.remote_delete_title), "", rows, () -> edit(at), 1);
    }
    private static final String[] ACTIONS = {"none", "vpn", "settings", "play", "next", "previous", "mute"};
    private String label(String action) {
        switch (action) {
            case "default": return text(R.string.settings_remote_original);
            case "vpn": return "INCY VPN";
            case "settings": return text(R.string.home_settings);
            case "play": return text(R.string.settings_remote_play);
            case "next": return text(R.string.settings_remote_next);
            case "previous": return text(R.string.settings_remote_previous);
            case "mute": return text(R.string.settings_remote_mute);
            case "": return text(R.string.settings_remote_unassigned);
            case "none": return text(R.string.settings_remote_none);
            default: return appLabel(action.startsWith("app:") ? action.substring(4) : action);
        }
    }
    private String appLabel(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) { return pkg; }
    }
    private void actions(int at, int tap) {
        Binding b = bindings.get(at); List<Row> rows = new ArrayList<>(); List<String> ids = new ArrayList<>();
        if (tap == 0 && !b.global && RemoteBindings.ok(b.code)) ids.add("default");
        if (tap > 0) ids.add("");
        java.util.Collections.addAll(ids, ACTIONS);
        for (String id : ids) rows.add(new Row(label(id), "", id.equals(b.actions()[tap]), () -> assign(at, tap, id)));
        rows.add(row(R.string.settings_remote_app, () -> apps(pkg -> assign(at, tap, "app:" + pkg), () -> actions(at, tap), false)));
        show(text(R.string.settings_remote_action), "", rows, () -> edit(at), Math.max(0, ids.indexOf(b.actions()[tap])));
    }
    private void assign(int at, int tap, String action) {
        Binding b = bindings.get(at); String[] actions = b.actions(); actions[tap] = action;
        bindings.set(at, new Binding(b.code, b.scan, b.device, b.global, actions)); save(() -> edit(at));
    }
    private void connection() {
        List<Row> rows = new ArrayList<>();
        rows.add(row(R.string.remote_enable, () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        rows.add(row(R.string.remote_select, () -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker()));
        rows.add(row(R.string.settings_remote_text_keyboard, this::keyboards));
        rows.add(row(R.string.remote_app_buttons, this::legacy));
        show(text(R.string.remote_input_options), text(R.string.remote_connection_hint), rows, this::root, 0);
    }
    private void keyboards() {
        List<Row> rows = new ArrayList<>(); String selected = RemoteKeyboardBridge.target(this);
        for (InputMethodInfo info : RemoteKeyboardBridge.keyboards(this)) rows.add(new Row(
                String.valueOf(info.loadLabel(getPackageManager())), "", info.getId().equals(selected), () -> {
                    busy = true;
                    io.execute(() -> {
                        boolean ok = true;
                        try { KeyboardHandoff.writeTarget(getFilesDir(), info.getId()); } catch (java.io.IOException error) { ok = false; }
                        final boolean saved = ok;
                        runOnUiThread(() -> { busy = false; if (isDestroyed() || isFinishing()) return;
                            if (!saved) message(R.string.settings_remote_save_failed); connection(); });
                    });
                }));
        if (rows.isEmpty()) rows.add(row(R.string.remote_enable, () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        show(text(R.string.settings_remote_text_keyboard), "", rows, this::connection, 0);
    }
    private void legacy() {
        List<Row> rows = new ArrayList<>(); String[] names = SettingsValues.keyLabels();
        for (int i = 0; i < Prefs.KEYS.length; i++) {
            final int key = Prefs.KEYS[i]; String pkg = Prefs.keyApp(key);
            rows.add(row(names[i], pkg.isEmpty() ? text(R.string.settings_remote_none) : appLabel(pkg), () ->
                    apps(selected -> { Prefs.setKeyApp(key, selected); legacy(); }, this::legacy, true)));
        }
        show(text(R.string.remote_app_buttons), "", rows, this::connection, 0);
    }
    private void apps(java.util.function.Consumer<String> select, Runnable previous, boolean allowNone) {
        show(text(R.string.settings_remote_app), text(R.string.remote_loading), new ArrayList<>(), previous, 0);
        final int token = generation;
        io.execute(() -> {
            List<String[]> apps = new ArrayList<>();
            try {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                for (android.content.pm.ResolveInfo app : getPackageManager().queryIntentActivities(query, 0)) {
                    String pkg = app.activityInfo.packageName; boolean seen = false;
                    for (String[] old : apps) if (old[0].equals(pkg)) { seen = true; break; }
                    if (!seen) apps.add(new String[]{pkg, String.valueOf(app.loadLabel(getPackageManager()))});
                }
                apps.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
            } catch (RuntimeException error) { AppLog.w("Remote app list unavailable"); }
            runOnUiThread(() -> {
                if (token != generation || isFinishing() || isDestroyed()) return;
                List<Row> rows = new ArrayList<>();
                if (allowNone) rows.add(row(text(R.string.settings_remote_none), "", () -> select.accept("")));
                for (String[] app : apps) rows.add(row(app[1], "", () -> select.accept(app[0])));
                show(text(R.string.settings_remote_app), rows.isEmpty() ? text(R.string.remote_no_apps) : "", rows, previous, 0);
            });
        });
    }
    private void save(Runnable complete) {
        List<Binding> snapshot = new ArrayList<>(bindings); busy = true; input.reset();
        if (shortcuts != null) shortcuts.cancel();
        io.execute(() -> {
            boolean ok = true; try { RemoteBindings.write(getFilesDir(), snapshot); } catch (java.io.IOException error) { ok = false; }
            final boolean saved = ok;
            if (saved) sendBroadcast(new Intent(RemoteShortcuts.CHANGE).setPackage(getPackageName()));
            runOnUiThread(() -> {
                busy = false; if (isFinishing() || isDestroyed()) return;
                if (!saved) { bindings.clear(); bindings.addAll(RemoteBindings.read(getFilesDir())); message(R.string.settings_remote_save_failed); root(); }
                else { shortcuts.reload(); complete.run(); }
            });
        });
    }
    private final class Menu extends View {
        final SettingsBackground background = new SettingsBackground();
        final SettingsLogoRenderer logo = new SettingsLogoRenderer();
        final Shadows shadows = new Shadows();
        final SettingsRowRenderer capsules = new SettingsRowRenderer(shadows::draw);
        final RectF rect = new RectF();
        final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        List<Row> rows = new ArrayList<>(); String title = "", hint = "", shownTitle = "", shownHint = "";
        int focus, offset, touch = -1; float k = 1; long entered, last;
        final Spoken spoken;
        Menu() {
            super(RemoteShortcutSettingsActivity.this); setFocusable(true); setFocusableInTouchMode(true);
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = offset; i < Math.min(rows.size(), offset + 8); i++) out.add(i + 1); }
                @Override public String text(int id) { if (id <= 0 || id > rows.size()) return ""; Row row = rows.get(id - 1); return row.label + (row.value.isEmpty() ? "" : ", " + row.value); }
                @Override public boolean bounds(int id, Rect out) { int i = id - 1; if (i < offset || i >= Math.min(rows.size(), offset + 8)) return false;
                    rowBounds(i); rect.roundOut(out); return true; }
                @Override public int focused() { return rows.isEmpty() ? -1 : focus + 1; }
                @Override public void focus(int id) { if (id > 0 && id <= rows.size()) { focus = id - 1; reveal(); invalidate(); } }
                @Override public void click(int id) { if (id > 0 && id <= rows.size()) { focus(id); activate(); } }
            });
        }
        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return spoken; }
        void show(String nextTitle, String nextHint, List<Row> nextRows, int selected) {
            title = nextTitle; hint = nextHint; rows = nextRows; focus = Math.max(0, Math.min(selected, rows.size() - 1)); offset = 0;
            reveal(); fit(); entered = SystemClock.uptimeMillis(); last = entered;
            setContentDescription(title + (hint.isEmpty() ? "" : ", " + hint)); requestFocus(); invalidate(); spoken.focusMoved();
        }
        void reveal() { offset = Math.max(0, Math.min(offset, focus)); if (focus >= offset + 8) offset = focus - 7; }
        void move(int direction) { int next = Math.max(0, Math.min(rows.size() - 1, focus + direction)); if (next == focus || rows.isEmpty()) return;
            focus = next; reveal(); click.move(); invalidate(); spoken.focusMoved(); }
        void activate() { if (busy || rows.isEmpty() || focus >= rows.size()) return; click.open(); rows.get(focus).action.run(); }
        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { k = w / 1920f; background.clear(); fit(); }
        void fit() {
            titlePaint.setTypeface(Ui.displayBold); titlePaint.setTextSize(56 * k); titlePaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTypeface(Ui.medium); labelPaint.setTextSize(30 * k);
            valuePaint.setTypeface(Ui.medium); valuePaint.setTextSize(27 * k); valuePaint.setTextAlign(Paint.Align.RIGHT);
            hintPaint.setTypeface(Ui.medium); hintPaint.setTextSize(27 * k); hintPaint.setTextAlign(Paint.Align.CENTER);
            shownTitle = TextUtils.ellipsize(title, titlePaint, 1740 * k, TextUtils.TruncateAt.END).toString();
            shownHint = TextUtils.ellipsize(hint, hintPaint, 730 * k, TextUtils.TruncateAt.END).toString();
            for (Row row : rows) {
                row.shownValue = TextUtils.ellipsize(row.value, valuePaint, 300 * k, TextUtils.TruncateAt.END).toString();
                float valueWidth = valuePaint.measureText(row.shownValue);
                row.shownLabel = TextUtils.ellipsize(row.label, labelPaint, (row.checked ? 620 : 650) * k - (valueWidth > 0 ? valueWidth + 30 * k : 0), TextUtils.TruncateAt.END).toString();
            }
        }
        void rowBounds(int at) { float y = (214 + (at - offset) * 86) * k; rect.set(940 * k, y, 1750 * k, y + 62 * k); }
        @Override protected void onDraw(Canvas c) {
            boolean light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
            background.draw(c, getWidth(), getHeight(), light); logo.draw(c, k, light, Ui.displaySemibold);
            long now = SystemClock.uptimeMillis(); float alpha = Math.min(1, (now - entered) / 180f);
            titlePaint.setColor(light ? 0xFF738190 : 0xFF5A676D);
            int saved = c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * alpha));
            c.drawText(shownTitle, getWidth() / 2f, 111 * k, titlePaint);
            hintPaint.setColor(light ? 0xB33A5761 : 0xFF888D90);
            c.drawText(shownHint, 522 * k, 845 * k, hintPaint);
            if (capturing) {
                labelPaint.setColor(light ? 0xFF738190 : 0xFF95B3BE);
                c.drawText(text(R.string.settings_remote_press), 980 * k, 465 * k, labelPaint);
            }
            float dt = Math.min(.05f, Math.max(0, now - last) / 1000f); last = now; boolean moving = alpha < 1;
            for (int i = offset; i < Math.min(rows.size(), offset + 8); i++) {
                Row row = rows.get(i); row.focus.target = i == focus ? 1 : 0;
                boolean reduced = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("reduce_motion", false);
                if (reduced) row.focus.snap(row.focus.target); else moving |= row.focus.step(dt);
                rowBounds(i); float amount = capsules.draw(c, rect, k, light, row.focus.value);
                int ink = ActionSurface.blend(light ? 0xFF57707C : 0xFF93A9C2, 0xFF1C1C1E, amount);
                labelPaint.setColor(ink); valuePaint.setColor(ink);
                float baseline = rect.centerY() + Ui.halfCap(labelPaint);
                c.drawText(row.shownLabel, 1031 * k, baseline, labelPaint);
                c.drawText(row.shownValue, (row.checked ? 1690 : 1720) * k, baseline, valuePaint);
                if (row.checked) Symbols.draw(getContext(), c, "check", 1720 * k, rect.centerY(), 25 * k, ink);
            }
            c.restoreToCount(saved); if (moving && resumed) postInvalidateOnAnimation();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) { touch = hit(event.getX(), event.getY()); return true; }
            if (event.getAction() == MotionEvent.ACTION_UP) { int at = hit(event.getX(), event.getY()); if (at >= 0 && at == touch && !capturing) { focus = at; activate(); } touch = -1; return true; }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) touch = -1; return true;
        }
        int hit(float x, float y) { for (int i = offset; i < Math.min(rows.size(), offset + 8); i++) { rowBounds(i); if (rect.contains(x, y)) return i; } return -1; }
    }
}
