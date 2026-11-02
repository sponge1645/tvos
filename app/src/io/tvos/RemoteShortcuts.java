package io.tvos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import io.tvos.remote.RemoteBindings;
import io.tvos.remote.RemoteBindings.Binding;
import io.tvos.remote.TapSequence;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Main-thread adapter. Home forwards originals locally; global bindings never inject keys. */
final class RemoteShortcuts implements AutoCloseable {
    static final String CHANGE = "io.tvos.REMOTE_BINDINGS_CHANGED";
    private final Context context;
    private final boolean globalOnly;
    private final Consumer<KeyEvent> original;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Binding> bindings;
    private Binding active;
    private int sequenceDevice;
    private TapSequence sequence;
    private KeyEvent template;
    private final Set<String> suppressed = new HashSet<>();
    private boolean closed;
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };
    RemoteShortcuts(Context c, boolean globalOnly, Consumer<KeyEvent> original) {
        context = c; this.globalOnly = globalOnly; this.original = original;
        bindings = RemoteBindings.read(c.getFilesDir());
        IntentFilter filter = new IntentFilter(CHANGE); filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(changes, filter, RemoteVolume.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(changes, filter, RemoteVolume.PERMISSION, null);
    }
    static String device(KeyEvent e) { InputDevice d = e.getDevice(); return d == null ? "" : d.getName(); }
    private Binding find(KeyEvent e) {
        if (bindings.isEmpty()) return null;
        int code = e.getKeyCode(), scan = e.getScanCode(); String device = device(e);
        for (Binding b : bindings) if (b.matches(code, scan, device)) {
            if (b.nativeOnly()) return null;
            return b;
        }
        return null;
    }
    boolean homeBinding(KeyEvent e) { Binding b = find(e); return b != null && !b.global; }
    boolean onKey(KeyEvent e) {
        if (closed || (e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (bindings.isEmpty() && suppressed.isEmpty()) return false;
        String identity = e.getDeviceId() + ":" + e.getKeyCode() + ":" + e.getScanCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && e.getAction() != KeyEvent.ACTION_UP) return false;
        Binding b = find(e);
        if (b == null || globalOnly && !b.global) {
            if (down) cancel();
            return !down && suppressed.remove(identity);
        }
        if (down && e.getRepeatCount() == 0) {
            if (active != b || sequenceDevice != e.getDeviceId()) {
                cancel(); active = b; sequenceDevice = e.getDeviceId();
                template = new KeyEvent(e);
                sequence = new TapSequence(new TapSequence.Scheduler() {
                    @Override public void later(Runnable task, long delay) { handler.postDelayed(task, delay); }
                    @Override public void cancel(Runnable task) { handler.removeCallbacks(task); }
                }, new TapSequence.Sink() {
                    @Override public void action(String action) { RemoteShortcutActions.run(context, action); }
                    @Override public void original(boolean press, boolean cancelled) {
                        if (original == null || template == null) return;
                        KeyEvent copy = KeyEvent.changeAction(template, press ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP);
                        if (cancelled) copy = KeyEvent.changeFlags(copy, copy.getFlags() | KeyEvent.FLAG_CANCELED);
                        original.accept(KeyEvent.changeTimeRepeat(copy, SystemClock.uptimeMillis(), 0));
                    }
                }, b.actions(), original != null);
            }
            template = new KeyEvent(e);
            suppressed.add(identity); sequence.down(0);
        } else if (sequence != null && active == b && sequenceDevice == e.getDeviceId() && suppressed.contains(identity)) {
            if (down) sequence.down(e.getRepeatCount());
            else { suppressed.remove(identity); sequence.up(e.isCanceled()); }
        }
        else if (!down) suppressed.remove(identity);
        return true;
    }
    void reload() { cancel(); bindings = RemoteBindings.read(context.getFilesDir()); }
    void cancel() {
        if (sequence != null) sequence.cancel();
        sequence = null; active = null; template = null;
        // At most 32 configured keys; consume trailing releases after cancellation.
        if (suppressed.size() > RemoteBindings.MAX) suppressed.clear();
    }
    @Override public void close() {
        if (closed) return;
        cancel(); closed = true; suppressed.clear(); context.unregisterReceiver(changes);
    }
}
