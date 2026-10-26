package io.tvos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Bounded streaming transfers. No Activity references and no APK-sized allocations. */
final class ApkTransfer {
    static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1_500_000;

    private ApkTransfer() {}

    static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
    }

    static HttpURLConnection connection(String address, BooleanSupplier cancelled) throws IOException {
        URL current = new URL(address);
        for (int redirects = 0; redirects < 8; redirects++) {
            checkCancelled(cancelled);
            if (!"https".equals(current.getProtocol())) throw new IOException("HTTPS required");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("User-Agent", "tvOS-AndroidTV/1.0");
                connection.setRequestProperty("Accept", "application/octet-stream, application/vnd.github+json, */*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without Location");
                    current = new URL(current, location);
                    connection.disconnect();
                    continue;
                }
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                checkCancelled(cancelled);
                return connection;
            } catch (IOException | RuntimeException e) {
                connection.disconnect();
                throw e;
            }
        }
        throw new IOException("Too many redirects");
    }

    static void download(AppDownloadCatalog.Source source, File temporary,
                         BooleanSupplier cancelled, IntConsumer progress) throws IOException {
        boolean complete = false;
        try {
            HttpURLConnection connection = connection(source.url, cancelled);
            try {
                long expected = connection.getContentLengthLong();
                if (expected > MAX_APK_BYTES) throw new IOException("APK exceeds size limit");
                MessageDigest digest = digest();
                long received = 0;
                int lastPercent = -5;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        received += count;
                        if (received > MAX_APK_BYTES) throw new IOException("APK exceeds size limit");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        if (expected > 0) {
                            int percent = (int) Math.min(100, received * 100 / expected);
                            if (percent >= lastPercent + 5) { lastPercent = percent; progress.accept(percent); }
                        }
                    }
                }
                checkCancelled(cancelled);
                if (received < 10000 || expected >= 0 && received != expected)
                    throw new IOException("Incomplete or invalid APK size");
                if (source.sha256 != null && !source.sha256.equals(hex(digest.digest())))
                    throw new IOException(AppStrings.get(R.string.runtime_apk_sha_256_mismatch));
                complete = true;
            } finally {
                connection.disconnect();
            }
        } finally {
            if (!complete && temporary.exists()) temporary.delete();
        }
    }

    static String json(String address, BooleanSupplier cancelled) throws IOException {
        HttpURLConnection connection = connection(address, cancelled);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                if (output.size() + count > MAX_JSON_BYTES) throw new IOException("Metadata response too large");
                output.write(buffer, 0, count);
            }
            checkCancelled(cancelled);
            return output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits.charAt((bytes[i] & 255) >>> 4);
            result[i * 2 + 1] = digits.charAt(bytes[i] & 15);
        }
        return new String(result);
    }
}
