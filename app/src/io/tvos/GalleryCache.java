package io.tvos;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gallery playlist and disk budget. Only files borrowed by an active decoder are pinned. */
final class GalleryCache {
    static final int MAX_PHOTOS = 80;
    static final long MAX_BYTES = 128L * 1024 * 1024;
    private final File folder;
    private final int maxPhotos;
    private final long maxBytes;
    private final List<File> ready = new ArrayList<>();
    private final Map<File, Integer> borrowed = new HashMap<>();
    private int next;

    GalleryCache(File folder) { this(folder, MAX_PHOTOS, MAX_BYTES); }

    GalleryCache(File folder, int maxPhotos, long maxBytes) {
        this.folder = folder;
        this.maxPhotos = maxPhotos;
        this.maxBytes = maxBytes;
        folder.mkdirs();
        File[] existing = photos();
        for (File file : existing) if (file.length() > 80_000) ready.add(file);
        prune();
        Collections.shuffle(ready);
    }

    synchronized boolean hasImages() { return !ready.isEmpty(); }

    /** Caller must release the file after decoding, including failures and canceled work. */
    synchronized File next() {
        ready.removeIf(file -> !file.isFile());
        if (ready.isEmpty()) return null;
        if (next >= ready.size()) { next = 0; Collections.shuffle(ready); }
        File file = ready.get(next++);
        borrowed.put(file, borrowed.getOrDefault(file, 0) + 1);
        file.setLastModified(System.currentTimeMillis());
        return file;
    }

    synchronized void release(File file) {
        Integer count = borrowed.get(file);
        if (count == null) return;
        if (count == 1) borrowed.remove(file);
        else borrowed.put(file, count - 1);
    }

    synchronized boolean add(File file) {
        if (!file.isFile() || file.length() <= 80_000) return false;
        if (!ready.contains(file)) ready.add(file);
        return true;
    }

    synchronized void prune() {
        File[] files = photos();
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long bytes = 0;
        for (File file : files) bytes += storedBytes(file);
        int count = files.length;
        for (File file : files) {
            if (count <= maxPhotos && bytes <= maxBytes) break;
            if (borrowed.containsKey(file)) continue;
            long size = storedBytes(file);
            if (!file.delete()) continue;
            count--;
            bytes -= size;
            int at = ready.indexOf(file);
            if (at >= 0) {
                ready.remove(at);
                if (at < next) next--;
            }
            metadata(file).delete();
        }
    }

    private File metadata(File photo) { return new File(folder, photo.getName().replace(".jpg", ".json")); }
    private long storedBytes(File photo) { return photo.length() + metadata(photo).length(); }

    private File[] photos() {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".jpg"));
        return files == null ? new File[0] : files;
    }
}
