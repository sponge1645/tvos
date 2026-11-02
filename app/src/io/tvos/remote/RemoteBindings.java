package io.tvos.remote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded atomic bindings shared by Home/settings and the accessibility process. */
public final class RemoteBindings {
    private RemoteBindings() { }
    public static final int MAX = 32;
    public static boolean allowed(int code) {
        return code > 0 && code <= 10000 && code != 3 && code != 4 && code != 26 && code != 223
                && code != 224 && code != 24 && code != 25 && code != 164
                && !(code >= 19 && code <= 22);
    }
    public static boolean ok(int code) { return code == 23 || code == 66 || code == 160; }
    public static boolean validAction(String action) {
        return action.length() <= 512 && (action.isEmpty() || action.matches("none|default|vpn|settings|play|next|previous|mute")
                || action.matches("app:[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"));
    }
    public static final class Binding {
        public final int code, scan;
        public final String device;
        public final boolean global;
        private final String[] actions;
        public Binding(int code, int scan, String device, boolean global, String[] actions) {
            if (!allowed(code) || scan < 0 || device.length() > 128 || actions.length != 3
                    || global && ok(code)) throw new IllegalArgumentException("Unsupported binding");
            for (int i = 0; i < 3; i++) if (!validAction(actions[i])
                    || "default".equals(actions[i]) && (global || i != 0 || !ok(code)))
                throw new IllegalArgumentException("Unsupported action");
            this.code = code; this.scan = scan; this.device = device; this.global = global;
            this.actions = actions.clone();
        }
        public String[] actions() { return actions.clone(); }
        public boolean nativeOnly() { return !global && "default".equals(actions[0]) && actions[1].isEmpty() && actions[2].isEmpty(); }
        public boolean matches(int code, int scan, String device) {
            return this.code == code && this.scan == scan && this.device.equals(device);
        }
    }
    public static List<Binding> read(File directory) {
        File file = new File(directory, "remote-bindings.bin");
        if (file.length() > 65536) return Collections.emptyList();
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != 1) return Collections.emptyList();
            int size = in.readInt();
            if (size < 0 || size > MAX) return Collections.emptyList();
            List<Binding> result = new ArrayList<>();
            for (int i = 0; i < size; i++) result.add(new Binding(in.readInt(), in.readInt(), in.readUTF(),
                    in.readBoolean(), new String[] {in.readUTF(), in.readUTF(), in.readUTF()}));
            if (in.read() != -1) return Collections.emptyList();
            return Collections.unmodifiableList(result);
        } catch (IOException | IllegalArgumentException error) { return Collections.emptyList(); }
    }
    public static void write(File directory, List<Binding> bindings) throws IOException {
        if (bindings.size() > MAX) throw new IllegalArgumentException("Too many bindings");
        File temp = File.createTempFile("remote-bindings-", ".tmp", directory);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp);
                 DataOutputStream out = new DataOutputStream(stream)) {
                out.writeInt(1); out.writeInt(bindings.size());
                for (Binding b : bindings) {
                    out.writeInt(b.code); out.writeInt(b.scan); out.writeUTF(b.device); out.writeBoolean(b.global);
                    for (String action : b.actions) out.writeUTF(action);
                }
                out.flush(); stream.getFD().sync();
            }
            if (!temp.renameTo(new File(directory, "remote-bindings.bin"))) throw new IOException("Binding rename failed");
        } finally { if (temp.exists() && !temp.delete()) temp.deleteOnExit(); }
    }
}
