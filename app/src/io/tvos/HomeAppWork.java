package io.tvos;

import android.content.Context;
import android.os.Handler;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Application IO on the existing shared queue; owns delivery generation and VPN command state. */
final class HomeAppWork {
    interface Lists { void loaded(List<HomeApps.Item> shelf, List<HomeApps.Item> drawer, List<HomeApps.Item> others); }
    private final Context context;
    private final Executor executor;
    private final Handler handler;
    private final BooleanSupplier active;
    private final Lists lists;
    private final Runnable vpnFailed;
    private int epoch;
    private boolean vpnBusy;

    HomeAppWork(Context context, Executor executor, Handler handler, BooleanSupplier active, Lists lists, Runnable vpnFailed) {
        this.context = context;
        this.executor = executor;
        this.handler = handler;
        this.active = active;
        this.lists = lists;
        this.vpnFailed = vpnFailed;
    }

    int epoch() { return epoch; }
    /** Invalidate deliveries, not queued IO: preserve the shared executor's work order. */
    void invalidate() { epoch++; }

    static List<HomeApps.Item> drawerItems(Context context) {
        List<HomeApps.Item> result = HomeApps.allInputs(context);
        result.addAll(HomeApps.drawerShortcuts());
        return result;
    }

    void load() {
        int generation = epoch;
        executor.execute(() -> {
            List<HomeApps.Item> shelf = HomeApps.shelf(context, true);
            List<HomeApps.Item> drawer = drawerItems(context);
            List<HomeApps.Item> others = HomeApps.others(context);
            handler.post(() -> {
                if (!active.getAsBoolean() || generation != epoch) return;
                lists.loaded(shelf, drawer, others);
            });
        });
    }

    void toggleVpn() {
        if (vpnBusy) return;
        vpnBusy = true;
        int generation = epoch;
        executor.execute(() -> {
            boolean failed = false;
            try { VpnControl.toggle(context); }
            catch (IOException | RuntimeException e) {
                AppLog.w("VPN toggle failed: " + e.getClass().getSimpleName());
                failed = true;
            }
            final boolean error = failed;
            handler.post(() -> {
                vpnBusy = false;
                if (error && active.getAsBoolean() && generation == epoch) vpnFailed.run();
            });
        });
    }
}
