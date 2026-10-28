package io.tvos;

import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;

/** Keep the chosen installed KinoPub variant throughout cached and online lookup. */
final class KinoPubLink {
    private KinoPubLink() { }

    static boolean supports(String pkg) {
        return "watch.kino".equals(pkg) || "watch.kino.mod".equals(pkg);
    }

    static List<Intent> candidates(String pkg, int id) {
        if (!supports(pkg) || id <= 0) throw new IllegalArgumentException("Invalid KinoPub target");
        Uri uri = Uri.parse("https://kino.watch/item/view/" + id);
        List<Intent> intents = new ArrayList<>(3);
        // An applicationId-only MOD keeps the original activity's Java namespace.
        intents.add(new Intent(Intent.ACTION_VIEW, uri)
                .setClassName(pkg, "watch.kino.activity.InfoActivity"));
        if (!"watch.kino".equals(pkg)) {
            // Also allow builds that relocated the activity along with the package.
            intents.add(new Intent(Intent.ACTION_VIEW, uri)
                    .setClassName(pkg, pkg + ".activity.InfoActivity"));
        }
        intents.add(new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg));
        return intents;
    }
}
