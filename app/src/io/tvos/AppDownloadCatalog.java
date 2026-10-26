package io.tvos;

import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** Reviewed bootstrap artifacts and signer pins. Provenance and update procedure: docs/APK-SOURCES.md. */
final class AppDownloadCatalog {
    private AppDownloadCatalog() {}

    static final class Source {
        final String url;
        final String sha256;

        Source(String url, String sha256) {
            if (!url.startsWith("https://") || sha256 != null && !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid APK source");
            this.url = url;
            this.sha256 = sha256;
        }
    }

    static final class Entry {
        final String name, packageName, signerSha256, repository;
        final Source bootstrap;
        final boolean fixedArtifact;
        final long reviewedVersionCode;

        Entry(String name, String packageName, String signer, Source bootstrap, String repository, boolean fixed, long code) {
            if (signer != null && !signer.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid signer pin");
            this.name = name;
            this.packageName = packageName;
            this.signerSha256 = signer;
            this.bootstrap = bootstrap;
            this.repository = repository;
            this.fixedArtifact = fixed;
            this.reviewedVersionCode = code;
        }
    }

    private static Source source(String url, String sha256) { return new Source(url, sha256); }

    private static final Entry[] ENTRIES = {
        new Entry("AirPlay", "io.github.jqssun.airplay",
                "64ca1fa39ddf9e0e89c57d73fb681d2a5b35ae20f071771e2eb401b5032ef1a9",
                source("https://github.com/jqssun/android-airplay-server/releases/download/v0.0.36/app-release.apk",
                        "b8c7c595ed93f91562feedce92c27800f08f9c84df424f6ac2045e82324cf2a4"),
                "jqssun/android-airplay-server", false, 36),
        new Entry("Apple Music", "com.applemusicktv",
                "e1d2be1a073146ea4d159a96f6e9133a3605c2580b890a37335fd6d54db4bfb5",
                source("https://github.com/JObersi10/apple-music-tv/releases/download/v1.2/AM-TV-1.2.apk",
                        "27d44b8b2586a8d2eeb2b15ffd9ace15ccf4b02c74cbcf9fb783d1e38752b3d0"), null, true, 3),
        new Entry("TizenTube", "io.gh.reisxd.tizentube.cobalt",
                "378ea3935af1b95c32f6ce193f4ff7f420cdc2d7160c8c866d28d6a91042ec9a",
                source("https://github.com/reisxd/TizenTubeCobalt/releases/download/v2.0.3/cobalt-arm.apk",
                        "72a3527f520db3a1b414916ad96481244e451c41e272ae33344ae5e79abb37dd"),
                "reisxd/TizenTubeCobalt", false, 203),
        new Entry("INCY", "llc.itdev.incy",
                "e1430d81de511c72253a6a409910e609686f084e3944eab6f154999ac7128b13",
                source("https://github.com/INCY-DEV/incy-platforms/releases/download/latest/Incy.apk",
                        "67c8253b3dc5fd2a0a0b27d5d4736834fa43e276a1a54d3282f5df0935092836"), null, true, 381),
        new Entry("Seena", "com.seena.app",
                "731e85d4ed0224a0aea4d58058b5d73f82cb4d4e2dcf8dc9ab54b0c5ef20c59d",
                source("https://seena.su/updates/Seena-1.9.0-armeabi-v7a.apk",
                        "56e36b3a5de896407cc30f1ea3ce9cbdc2cb82da9caee8b17f28fc54c71ce0fa"), null, true, 1900),
        new Entry("Lampa", "top.rootu.lampa",
                "8adaaa27bf99293ec260fe372e77d142ac9166fe339f348f1148a3bf987d9872",
                source("https://github.com/lampa-app/LAMPA/releases/download/v1.13.5/app-lite-release.apk",
                        "4d53f2038cb99dc7a05f346e943dd6ec7cedd25e42b878ce383cb4e31d6c24c8"),
                "lampa-app/LAMPA", false, 581),
        // Explicitly accepted mirror snapshot; pins identify this artifact, not the mirror's publisher.
        new Entry("KinoPub", "watch.kino",
                "e3e5f1d63be9737e932a5851f64838b64730f9b68998754fbff78dd9350b9568",
                source("https://raw.githubusercontent.com/kotlinbestuser/apk/6f6691147168550aca587564c291bef15358503b/kino-watch-2.19.apk",
                        "5cf6002f897f34b32db28c60bf0a6e5dc9015761af7591ca800e7c48d183bcd8"),
                "kotlinbestuser/apk", false, 169)
    };

    static Entry get(int index) { return index >= 0 && index < ENTRIES.length ? ENTRIES[index] : null; }

    static Source resolve(Entry app, boolean installed, String[] abis, BooleanSupplier cancelled) throws Exception {
        if (!installed || app.fixedArtifact) {
            if (app.bootstrap == null) throw new IOException(AppStrings.get(R.string.runtime_no_verified_apk_for_first_installation_install_the_app_from_a_tru));
            if ("TizenTube".equals(app.name)) {
                if (supports(abis, "armeabi-v7a")) return app.bootstrap;
                if (supports(abis, "arm64-v8a")) return source(
                        "https://github.com/reisxd/TizenTubeCobalt/releases/download/v2.0.3/cobalt-arm64.apk",
                        "4e3df211abb243cb06b06ba3676fe62c3d5b59baa47c1a0050cb180b3493e539");
                throw new IOException("No TizenTube APK for this CPU");
            }
            return app.bootstrap;
        }
        if ("KinoPub".equals(app.name)) return kinoPub(cancelled);
        JSONArray releases = new JSONArray(ApkTransfer.json(
                "https://api.github.com/repos/" + app.repository + "/releases?per_page=12", cancelled));
        for (int r = 0; r < releases.length(); r++) {
            ApkTransfer.checkCancelled(cancelled);
            JSONObject release = releases.getJSONObject(r);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            String best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                int score = assetScore(asset.optString("name"), abis);
                if (score > bestScore) { bestScore = score; best = asset.optString("browser_download_url"); }
            }
            if (best != null && bestScore != Integer.MIN_VALUE) return source(best, null);
        }
        throw new IOException(AppStrings.get(R.string.runtime_no_compatible_apk_in_releases));
    }

    static int assetScore(String filename, String[] abis) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (!name.endsWith(".apk") || name.contains("debug")) return Integer.MIN_VALUE;
        if (name.contains("arm64") || name.contains("aarch64"))
            return supports(abis, "arm64-v8a") ? 5 : Integer.MIN_VALUE;
        if (name.contains("x86_64")) return supports(abis, "x86_64") ? 5 : Integer.MIN_VALUE;
        if (name.contains("x86")) return supports(abis, "x86") ? 6 : Integer.MIN_VALUE;
        if (name.contains("armeabi") || name.contains("armv7") || name.contains("-arm."))
            return supports(abis, "armeabi-v7a") ? 6 : Integer.MIN_VALUE;
        return name.contains("universal") ? 4 : 0;
    }

    private static boolean supports(String[] abis, String wanted) {
        for (String abi : abis) if (wanted.equals(abi)) return true;
        return false;
    }

    private static Source kinoPub(BooleanSupplier cancelled) throws Exception {
        JSONArray files = new JSONArray(ApkTransfer.json(
                "https://api.github.com/repos/kotlinbestuser/apk/contents", cancelled));
        Pattern pattern = Pattern.compile("kino-watch-([0-9]+(?:\\.[0-9]+){0,3})\\.apk");
        String best = null, bestVersion = null;
        for (int i = 0; i < files.length(); i++) {
            ApkTransfer.checkCancelled(cancelled);
            String name = files.getJSONObject(i).optString("name");
            Matcher match = pattern.matcher(name);
            if (!match.matches()) continue;
            String version = match.group(1);
            Integer order = AppVersions.compare(version, best == null ? "0" : bestVersion);
            if (order != null && (best == null || order > 0)) { best = name; bestVersion = version; }
        }
        if (best == null) throw new IOException("KinoPub APK not found");
        return source("https://raw.githubusercontent.com/kotlinbestuser/apk/HEAD/" + best, null);
    }
}
