package io.tvos.artwork;

import java.io.File;
import java.util.Arrays;

/** Shared compressed-artwork LRU. Decoded bitmap budgets remain with each renderer. */
public final class ArtworkDiskCache {
    private ArtworkDiskCache() { }
    public static synchronized void trim(File directory, long maxBytes, int maxFiles) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long total = 0; int count = 0;
        long stale = System.currentTimeMillis() - 60*60_000L;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (file.getName().endsWith(".part")) {
                if (file.lastModified() < stale) file.delete();
            } else { total += file.length(); count++; }
        }
        if (total <= maxBytes && count <= maxFiles) return;
        Arrays.sort(files, (a,b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            if (total <= maxBytes && count <= maxFiles) break;
            if (!file.isFile() || file.getName().endsWith(".part")) continue;
            long bytes = file.length();
            if (file.delete()) { total -= bytes; count--; }
        }
    }
}
