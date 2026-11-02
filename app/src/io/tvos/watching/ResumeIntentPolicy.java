package io.tvos.watching;

import android.content.Intent;
import java.net.URISyntaxException;

/** Preserve provider resume data while limiting the launch to its publishing package. */
public final class ResumeIntentPolicy {
    private ResumeIntentPolicy() {}

    public static Intent parse(String link, String owner) {
        if (owner == null || owner.isEmpty() || "io.tvos".equals(owner)) return null;
        if (link == null || link.length() > 262144) return null;
        try {
            Intent intent = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() != null && !owner.equals(intent.getComponent().getPackageName())) return null;
            intent.setSelector(null);
            intent.setPackage(owner);
            intent.setClipData(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }
}
