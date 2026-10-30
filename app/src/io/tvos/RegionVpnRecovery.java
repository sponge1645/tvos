package io.tvos;

import android.content.Context;
import android.net.ConnectivityManager;
import io.tvos.network.VpnRestoreJournal;
import java.io.IOException;

/** Android adapter for the durable journal and an independent, persisted recovery job. */
final class RegionVpnRecovery {
    private final Context context;
    private final VpnRestoreJournal journal;
    RegionVpnRecovery(Context context) {
        this.context = context.getApplicationContext();
        journal = new VpnRestoreJournal(this.context.getFilesDir());
    }
    void pause(RegionVpnPause.Control control) throws IOException {
        journal.pause(() -> {
            if (!ModeJob.scheduleRestore(context, 38_000)) throw new IOException("Cannot schedule VPN restoration");
        }, () -> {
            control.toggle();
            await(control, false);
        });
        notice(R.string.region_vpn_paused);
    }
    void restore(RegionVpnPause.Control control) throws IOException {
        try {
            if (journal.restore(() -> {
                if (!control.connected()) { control.toggle(); await(control, true); }
            })) notice(R.string.region_vpn_restored);
        } catch (IOException e) {
            ModeJob.scheduleRestore(context, 30_000);
            notice(R.string.region_vpn_restore_failed);
            throw e;
        }
    }
    private static void await(RegionVpnPause.Control control, boolean connected) throws IOException {
        long until = control.now() + 8000;
        while (control.connected() != connected) {
            if (control.now() >= until) throw new IOException("VPN recovery state change timed out");
            try { control.waitStep(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("VPN recovery interrupted", e); }
        }
    }
    static void request(Context context) {
        if (new VpnRestoreJournal(context.getFilesDir()).pending()) ModeJob.scheduleRestore(context, 0);
    }
    static void recover(Context context) throws IOException {
        RegionVpnRecovery recovery = new RegionVpnRecovery(context);
        if (!recovery.journal.pending()) return;
        recovery.restore(control(context));
    }
    static RegionVpnPause.Control control(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return new RegionVpnPause.Control() {
            public boolean connected() { return NetworkStatus.detectVpn(manager); }
            public void toggle() throws IOException { VpnControl.toggle(context); }
            public long now() { return android.os.SystemClock.elapsedRealtime(); }
            public void waitStep() throws InterruptedException { Thread.sleep(100); }
        };
    }
    private void notice(int text) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context, AppStrings.get(text), android.widget.Toast.LENGTH_LONG).show());
    }
}
