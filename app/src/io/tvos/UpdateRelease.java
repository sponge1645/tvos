package io.tvos;

import java.util.Locale;
import org.json.JSONObject;

/** A release manifest never supplies an arbitrary download URL. Assets are pinned to a release tag. */
final class UpdateRelease {
    static final String REPOSITORY = "https://github.com/sponge1645/tvos";
    static final String LATEST = REPOSITORY + "/releases/download/ota-v1/update.json";
    final String versionName, tag, sha256;
    final long versionCode, size;

    private UpdateRelease(String name, long code, String tag, String digest, long size) {
        this.versionName = name;
        this.versionCode = code;
        this.tag = tag;
        this.sha256 = digest;
        this.size = size;
    }

    static UpdateRelease parse(JSONObject json) throws Exception {
        String name = json.getString("versionName");
        String tag = json.getString("tag");
        String digest = json.getString("sha256").toLowerCase(Locale.ROOT);
        long code = json.getLong("versionCode"), size = json.getLong("size");
        if (!"io.tvos".equals(json.getString("packageName"))
                || !"tvOS.apk".equals(json.getString("asset"))
                || !name.matches("[A-Za-z0-9.+_-]{1,32}")
                || !tag.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
                || tag.equals(".") || tag.equals("..")
                || !digest.matches("[0-9a-f]{64}")
                || code < 1 || code > 2100000000L || size < 10000 || size > 256L * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid update manifest");
        }
        return new UpdateRelease(name, code, tag, digest, size);
    }

    String apkUrl() { return REPOSITORY + "/releases/download/" + tag + "/tvOS.apk"; }
}
