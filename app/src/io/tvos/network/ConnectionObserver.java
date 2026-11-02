package io.tvos.network;

import java.net.HttpURLConnection;

/** Explicit transport lifecycle; closing carries identity and never uses a null sentinel. */
public interface ConnectionObserver {
    void opened(HttpURLConnection connection);
    void closed(HttpURLConnection connection);
    ConnectionObserver NONE = new ConnectionObserver() {
        @Override public void opened(HttpURLConnection connection) { }
        @Override public void closed(HttpURLConnection connection) { }
    };
}
