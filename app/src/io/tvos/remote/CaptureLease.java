package io.tvos.remote;

/** A failed or hidden settings process cannot leave an indefinite key-capture request behind. */
public final class CaptureLease {
    private String token = "";
    private long until;
    public void update(String next, long expiry, long now) {
        if (next == null || !next.matches("[a-f0-9-]{36}")) return;
        if (expiry == 0) { if (next.equals(token)) clear(); return; }
        if (expiry <= now || expiry > now + 5000) return;
        token = next; until = expiry;
    }
    public String token(long now) { if (now >= until) clear(); return token; }
    public void clear() { token = ""; until = 0; }
}
