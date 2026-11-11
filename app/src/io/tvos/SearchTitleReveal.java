package io.tvos;

/** Per-title logo deadline and reversible opacity transitions; no timers while settled. */
final class SearchTitleReveal {
    private static final long WAIT_MS = 3000, FADE_MS = 300;
    private long deadline, transition;
    private float textFrom, logoFrom, textTarget, logoTarget;
    private float text, logo;

    void open(long now) {
        deadline = now + WAIT_MS; transition = now;
        text = logo = textFrom = logoFrom = textTarget = logoTarget = 0;
    }

    void update(long now, boolean hasLogo, boolean reduced) {
        advance(now);
        float nextText = !hasLogo && now >= deadline ? 1 : 0;
        float nextLogo = hasLogo ? 1 : 0;
        if (nextText != textTarget || nextLogo != logoTarget) {
            textFrom = text; logoFrom = logo; transition = now;
            textTarget = nextText; logoTarget = nextLogo;
        }
        if (reduced) {
            text = textFrom = textTarget; logo = logoFrom = logoTarget;
        }
    }

    private void advance(long now) {
        float t = Math.max(0, Math.min(1, (now - transition) / (float) FADE_MS));
        float smooth = t * t * (3 - 2 * t);
        text = textFrom + (textTarget - textFrom) * smooth;
        logo = logoFrom + (logoTarget - logoFrom) * smooth;
    }

    float textAlpha() { return text; }
    float logoAlpha() { return logo; }

    long nextDelay(long now) {
        if (text != textTarget || logo != logoTarget) return 16;
        return textTarget == 0 && logoTarget == 0 && now < deadline ? deadline - now : 0;
    }
}
