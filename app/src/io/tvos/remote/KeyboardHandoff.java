package io.tvos.remote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure handoff policy. Never restores over a keyboard the user selected manually. */
public final class KeyboardHandoff {
    public enum Result { WAIT, RESTORE, RELEASE }
    private static final Pattern SHOWN = Pattern.compile("\\bmInputShown=(true|false)\\b");
    private final String keyboard;
    private final long started;
    private long hiddenSince = -1;
    private boolean seen;
    public KeyboardHandoff(String keyboard, long now) { this.keyboard = keyboard; started = now; }
    public static Boolean shown(String dump) {
        Matcher match = SHOWN.matcher(dump);
        return match.find() ? Boolean.valueOf(match.group(1)) : null;
    }
    public Result step(String selected, String dump, long now, boolean interactive) {
        if (!keyboard.equals(selected)) return Result.RELEASE;
        if (!interactive) return Result.RESTORE;
        Boolean visible = shown(dump);
        if (visible == null) return Result.RESTORE; // Unknown firmware format: do not leave a delegation running.
        if (now - started < 1000) return Result.WAIT; // Ignore the old IME's visibility during binding.
        if (visible) { seen = true; hiddenSince = -1; }
        else if (seen) {
            if (hiddenSince < 0) hiddenSince = now;
            if (now - hiddenSince >= 700) return Result.RESTORE;
        }
        if (!seen && now - started >= 10000) return Result.RESTORE;
        // Stop monitoring after 30 minutes without interrupting visible text entry.
        if (now - started >= 1800000) return Result.RELEASE;
        return Result.WAIT;
    }
    public static String readTarget(File directory) {
        File file = new File(directory, "text-keyboard.bin");
        if (file.length() > 2048) return "";
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            String id = input.readUTF();
            return input.read() == -1 && validId(id) ? id : "";
        } catch (IOException error) { return ""; }
    }
    public static void writeTarget(File directory, String id) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("Invalid input method");
        File temp = File.createTempFile("text-keyboard-", ".tmp", directory);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp); DataOutputStream output = new DataOutputStream(stream)) {
                output.writeUTF(id); output.flush(); stream.getFD().sync();
            }
            if (!temp.renameTo(new File(directory, "text-keyboard.bin"))) throw new IOException("Keyboard target rename failed");
        } finally { if (temp.exists() && !temp.delete()) temp.deleteOnExit(); }
    }
    public static boolean validId(String id) {
        return id != null && id.length() <= 512 && id.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
    }
}
