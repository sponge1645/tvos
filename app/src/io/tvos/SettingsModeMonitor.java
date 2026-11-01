package io.tvos;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** HomeMode observation and pending-switch status, without View or navigation ownership. */
final class SettingsModeMonitor {
    private final Context context;
    private final Handler handler;
    private final BooleanSupplier running, region;
    private final Consumer<String> announce;
    private final Runnable refresh;
    private JSONObject state = new JSONObject();
    private boolean wasBusy, regionWasOpen;
    private long pendingAt;

    SettingsModeMonitor(Context context, Handler handler, BooleanSupplier running, BooleanSupplier region,
            Consumer<String> announce, Runnable refresh) {
        this.context = context;
        this.handler = handler;
        this.running = running;
        this.region = region;
        this.announce = announce;
        this.refresh = refresh;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running.getAsBoolean()) return;
            boolean inRegion = region.getAsBoolean();
            if (inRegion && !regionWasOpen) AutoRegion.enter(context);
            regionWasOpen = inRegion;
            state = HomeMode.state(context);
            boolean nowBusy = !busyText().isEmpty();
            if (nowBusy) pendingAt = 0;
            JSONArray notices = state.optJSONArray("notices");
            if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                JSONObject newest = notices.optJSONObject(0);
                if (newest != null) announce.accept(NoticeText.render(newest));
            }
            wasBusy = nowBusy;
            refresh.run();
            handler.postDelayed(this, busy() ? 500 : 2000);
        }
    };

    void start() { handler.post(poll); }
    void stop() { regionWasOpen = false; handler.removeCallbacks(poll); }
    void markPending() { pendingAt = SystemClock.uptimeMillis(); }
    boolean busy() {
        return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
    }
    private String busyText() {
        long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
        boolean stale = now < at || now - at > 10 * 60_000L;
        return stale ? "" : state.optString("busy");
    }
    boolean freeActive() {
        return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
    }
}
