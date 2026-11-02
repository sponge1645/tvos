package io.tvos.remote;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Small atomic files shared by the settings and accessibility processes. Off by default. */
public final class VolumeHoldStore {
    private VolumeHoldStore() { }
    public static boolean enabled(File directory) { return "1".equals(read(directory, "volume-hold.enabled")); }
    public static String status(File directory) { return read(directory, "volume-hold.status"); }
    public static void setEnabled(File directory, boolean enabled) throws IOException {
        write(directory, "volume-hold.enabled", enabled ? "1" : "0");
    }
    public static void setStatus(File directory, String status) throws IOException {
        if (!status.matches("OFF|WAITING|STARTING|READY|ERROR")) throw new IllegalArgumentException(status);
        write(directory, "volume-hold.status", status);
    }
    private static String read(File directory, String name) {
        try (FileInputStream in = new FileInputStream(new File(directory, name))) {
            byte[] bytes = new byte[16];
            int count = in.read(bytes);
            return count < 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8);
        } catch (IOException error) { return ""; }
    }
    private static void write(File directory, String name, String value) throws IOException {
        File temporary = File.createTempFile("volume-hold-", ".tmp", directory);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temporary.renameTo(new File(directory, name))) throw new IOException("Volume preference rename failed");
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }
}
