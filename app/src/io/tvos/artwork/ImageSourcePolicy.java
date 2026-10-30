package io.tvos.artwork;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Function;

/** Validate a publisher's image reference without opening its URI. */
public final class ImageSourcePolicy {
    private ImageSourcePolicy() {}
    public static boolean allowed(String value, String owner, Function<String, String> providerOwner) {
        if (value == null || owner == null || owner.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            String authority = uri.getAuthority();
            if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0) return false;
            switch (uri.getScheme() == null ? "" : uri.getScheme()) {
                case "https": return uri.getHost() != null;
                case "android.resource": return owner.equals(uri.getAuthority());
                case "content": return owner.equals(providerOwner.apply(uri.getAuthority()));
                default: return false;
            }
        } catch (URISyntaxException | RuntimeException e) { return false; }
    }
}
