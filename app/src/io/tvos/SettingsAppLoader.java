package io.tvos;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Installed-app lookup off the UI thread; cancelled screens cannot receive results. */
final class SettingsAppLoader {
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    private final Context context;
    private final Handler handler;
    private final BooleanSupplier active;
    private UiRequest request;

    SettingsAppLoader(Context context, Handler handler, BooleanSupplier active) {
        this.context = context;
        this.handler = handler;
        this.active = active;
    }

    void cancel() {
        if (request != null) request.cancel();
        request = null;
    }

    void load(Consumer<List<String[]>> deliver) {
        cancel();
        if (!active.getAsBoolean()) return;
        UiRequest work = new UiRequest(IO);
        request = work;
        work.start(() -> {
            List<String[]> found = query();
            if (!work.active()) return;
            handler.post(() -> {
                if (!work.active() || !active.getAsBoolean() || request != work) return;
                request = null;
                deliver.accept(found);
            });
        });
    }

    private List<String[]> query() {
        PackageManager pm = context.getPackageManager();
        List<String[]> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
                try { found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))}); }
                catch (RuntimeException e) { AppLog.unexpected("Settings app label", e); }
            }
        } catch (RuntimeException e) { AppLog.unexpected("Settings app list", e); }
        found.sort((a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return found;
    }
}
