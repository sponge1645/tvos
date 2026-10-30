package io.tvos;

import android.content.Context;
import java.io.IOException;

/** A short, state-checked Incy pause owned by one automatic-region request. */
final class RegionVpnPause implements AutoCloseable {
    interface Control {
        boolean connected();
        void toggle() throws IOException;
        long now();
        void waitStep() throws InterruptedException;
    }
    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private java.util.concurrent.ScheduledFuture<?> expiry;
    private final Control control;
    private boolean restore;
    private RegionVpnRecovery recovery;
    private long deadline;

    RegionVpnPause(Control control) { this.control = control; }

    static RegionVpnPause open(Context context) throws IOException {
        RegionVpnRecovery.recover(context);
        RegionVpnPause pause = new RegionVpnPause(RegionVpnRecovery.control(context));
        pause.recovery = new RegionVpnRecovery(context);
        if (pause.control.connected() && !VpnControl.PACKAGE.equals(context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("vpn_app", ""))) throw new IOException("Automatic region VPN pause supports Incy only");
        pause.begin();
        if (pause.restore) pause.expiry = TIMER.schedule(() -> {
            try { pause.close(); }
            catch (IOException e) { AppLog.w("Region VPN restoration failed: " + e.getClass().getSimpleName()); }
        }, pause.remainingMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        return pause;
    }

    void begin() throws IOException {
        if (!control.connected()) return;
        restore = true;
        try {
            if (recovery != null) recovery.pause(control);
            else { control.toggle(); await(false, control.now() + 8000); }
            deadline = control.now() + 30000;
        } catch (IOException e) {
            try { close(); } catch (IOException restoreFailure) { e.addSuppressed(restoreFailure); }
            throw e;
        }
    }

    long remainingMillis() {
        return deadline == 0 ? 30000 : Math.max(0, deadline - control.now());
    }

    private void await(boolean state, long until) throws IOException {
        while (control.connected() != state) {
            if (control.now() >= until) throw new IOException("VPN state change timed out");
            try { control.waitStep(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("VPN wait interrupted", e); }
        }
    }

    @Override public synchronized void close() throws IOException {
        if (expiry != null) expiry.cancel(false);
        if (!restore) return;
        if (recovery != null) recovery.restore(control);
        else if (!control.connected()) {
            control.toggle();
            await(true, control.now() + 8000);
        }
        restore = false;
    }
}
