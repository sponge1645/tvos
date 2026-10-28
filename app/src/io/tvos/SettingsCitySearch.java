package io.tvos;

import android.os.Handler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Settings city lookup scheduling and status; the view owns input, results and focus. */
final class SettingsCitySearch {
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    private final Handler handler;
    private final BooleanSupplier active;
    private final IntSupplier screen;
    private final Consumer<List<String[]>> deliver;
    private final Runnable changed;
    private UiRequest request;
    private int serial;
    private String status = "";
    private final Runnable clearStatus = this::clearStatus;

    private void clearStatus() {
        status = "";
        if (active.getAsBoolean()) changed.run();
    }

    SettingsCitySearch(Handler handler, BooleanSupplier active, IntSupplier screen,
                       Consumer<List<String[]>> deliver, Runnable changed) {
        this.handler = handler;
        this.active = active;
        this.screen = screen;
        this.deliver = deliver;
        this.changed = changed;
    }

    String status() { return status; }
    boolean searching() { return request != null; }

    void cancel() {
        serial++;
        if (request != null) request.cancel();
        request = null;
        handler.removeCallbacks(clearStatus);
        status = "";
    }

    void search(String name) {
        if (!active.getAsBoolean() || name.trim().isEmpty()) return;
        cancel();
        final int generation = serial;
        final int originScreen = screen.getAsInt();
        UiRequest work = new UiRequest(IO);
        request = work;
        status = AppStrings.get(R.string.settings_city_searching);
        changed.run();
        work.start(() -> {
            List<String[]> found;
            String error;
            try {
                found = HomeData.findCity(name);
                error = found.isEmpty() ? AppStrings.get(R.string.settings_city_not_found) : "";
            } catch (Exception e) {
                found = new ArrayList<>();
                error = AppStrings.get(R.string.settings_city_search_error_prefix) + e.getMessage();
                AppLog.w("city search: " + e.getMessage());
            }
            List<String[]> result = found;
            String message = error;
            if (!work.active()) return;
            handler.post(() -> {
                if (!work.active() || !active.getAsBoolean() || generation != serial) return;
                request = null;
                if (screen.getAsInt() != originScreen) { status = ""; return; }
                status = message;
                if (!result.isEmpty()) deliver.accept(result);
                changed.run();
                if (!message.isEmpty()) handler.postDelayed(clearStatus, 4000);
            });
        });
    }
}
