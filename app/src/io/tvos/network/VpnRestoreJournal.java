package io.tvos.network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Durable restore intent; a separate lock file serializes Home and recovery processes. */
public final class VpnRestoreJournal {
    public interface Action { void run() throws IOException; }
    public static final int MAX_RESTORE_ATTEMPTS = 5;
    public static final class RestoreFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final boolean exhausted;
        private RestoreFailure(IOException cause, boolean exhausted) {
            super(cause.getMessage(), cause);
            this.exhausted = exhausted;
        }
    }
    private static final Object LOCAL_LOCK = new Object();
    private final File marker, lock;
    public VpnRestoreJournal(File directory) {
        marker = new File(directory, "region-vpn-restore");
        lock = new File(directory, "region-vpn-restore.lock");
    }
    public boolean pending() { return marker.isFile(); }
    public void pause(Action schedule, Action disconnect) throws IOException {
        synchronized (LOCAL_LOCK) {
            try (RandomAccessFile file = new RandomAccessFile(lock, "rw");
                    FileLock held = file.getChannel().lock()) {
                if (!held.isValid()) throw new IOException("VPN recovery lock unavailable");
                if (pending()) throw new IOException("VPN restoration already pending");
                try (FileOutputStream out = new FileOutputStream(marker)) {
                    out.write(1); out.getFD().sync();
                }
                try { schedule.run(); }
                catch (IOException e) { marker.delete(); throw e; }
                // Leave the marker if disconnect fails: recovery still checks actual connection state.
                disconnect.run();
            }
        }
    }
    public boolean restore(Action reconnect) throws IOException {
        synchronized (LOCAL_LOCK) {
            try (RandomAccessFile file = new RandomAccessFile(lock, "rw");
                    FileLock held = file.getChannel().lock()) {
                if (!held.isValid()) throw new IOException("VPN recovery lock unavailable");
                if (!pending()) return false;
                int attempts;
                try (RandomAccessFile intent = new RandomAccessFile(marker, "rw")) {
                    // Original markers contain only the version byte: no attempts yet.
                    attempts = intent.length() > 1 ? readAttempts(intent) : 0;
                    if (attempts < MAX_RESTORE_ATTEMPTS) {
                        intent.seek(1);
                        intent.write(attempts + 1);
                        intent.getFD().sync();
                    }
                }
                if (attempts >= MAX_RESTORE_ATTEMPTS) {
                    clear();
                    throw new RestoreFailure(new IOException("VPN restoration attempts exhausted"), true);
                }
                try { reconnect.run(); }
                catch (IOException e) {
                    boolean exhausted = attempts + 1 >= MAX_RESTORE_ATTEMPTS;
                    if (exhausted) clear();
                    throw new RestoreFailure(e, exhausted);
                }
                clear();
                return true;
            }
        }
    }
    private static int readAttempts(RandomAccessFile intent) throws IOException {
        intent.seek(1);
        return intent.readUnsignedByte();
    }
    private void clear() throws IOException {
        if (!marker.delete()) throw new IOException("Cannot clear VPN restoration intent");
    }
}
