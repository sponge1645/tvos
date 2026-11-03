package io.tvos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Deletes only this application's data; installed code/native libraries remain intact. */
final class AppDataReset {
    static final String MARKER = ".reset-in-progress";
    private AppDataReset() { }
    static boolean clear(File root) throws IOException {
        if (root == null || !root.isDirectory()) throw new IOException("Missing application data directory");
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Application data unavailable");
        boolean ok = true;
        for (File child : children) {
            if (child.getName().equals("lib") || child.getName().equals(MARKER)) continue;
            ok &= remove(child);
        }
        return ok;
    }
    static boolean remove(File file) {
        boolean ok = true;
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) ok &= remove(child);
        }
        return file.delete() && ok;
    }
}
