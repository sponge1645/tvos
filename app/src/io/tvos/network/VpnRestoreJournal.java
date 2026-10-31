package io.tvos.network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Durable restore intent; a separate lock file serializes Home and recovery processes. */
public final class VpnRestoreJournal {
    public interface Action { void run() throws IOException; }
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
                reconnect.run();
                if (!marker.delete()) throw new IOException("Cannot clear VPN restoration intent");
                return true;
            }
        }
    }
}
