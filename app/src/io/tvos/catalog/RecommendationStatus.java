package io.tvos.catalog;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/** Existing diagnostic snapshot; error handling remains with the recommendation worker. */
public final class RecommendationStatus {
    private final LongSupplier clock;

    public RecommendationStatus(LongSupplier clock) {
        this.clock = clock;
    }

    public void write(File directory, int count, String error) throws IOException, JSONException {
        try (FileOutputStream out = new FileOutputStream(new File(directory, "recs-status.json"))) {
            out.write(new JSONObject().put("count", count).put("error", error).put("at", clock.getAsLong())
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
