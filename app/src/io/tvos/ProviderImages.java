package io.tvos;

import android.content.Context;
import android.content.pm.ProviderInfo;
import io.tvos.artwork.ImageSourcePolicy;

/** Resolve content authorities through PackageManager, never through a package-name guess. */
final class ProviderImages {
    private ProviderImages() {}
    static boolean allowed(Context context, String value, String owner) {
        return ImageSourcePolicy.allowed(value, owner, authority -> {
            ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
            return provider == null ? "" : provider.packageName;
        });
    }
}
