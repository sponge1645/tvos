package io.tvos;

import io.tvos.artwork.RatingStyle;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

/** Poster cards, rating badges and focus-driven text marquee, with bounded reusable caches. */
final class PosterRenderer {
    private static final float DEEP_BLUR = 30;
    private final int capacity;
    private final Recs recs;
    private final Shadows shadows;
    private final RoundedImages images;
    private final RectF cell = new RectF(), tmp = new RectF();
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            sheen = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint ellipsizeScratch = new TextPaint();
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private float k, night, lastScale = -1;
    private Paint lCardTitle, lCardMeta;
    private int tab, cardPage;
    private boolean searchMode, cardsFocused;
    private int marqueeCard = -1, marqueePage = -1, marqueeTab = -1;
    private long marqueeSince;
    private boolean marqueeNeeded;
    private final PosterText posterText = new PosterText();
    private final RatingStyle ratingStyle;
    private final Paint ratingTextScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ratingFillScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ratingSheenScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ratingRimScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ratingBadgeScratch = new RectF();
    private final RectF ratingInsetScratch = new RectF();
    private final Paint questionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marqueeMaskScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode textEdgeMode = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);

    PosterRenderer(Recs recs, Shadows shadows, RoundedImages images, int capacity) {
        this.recs = recs; this.shadows = shadows; this.images = images; this.capacity = capacity;
        ratingStyle = new RatingStyle(capacity);
        rim.setStyle(Paint.Style.STROKE);
    }
    void clear() { posterText.clear(); ratingStyle.clear(); }
    boolean focus(int index, int page, int tab, long now) {
        if (index == marqueeCard && page == marqueePage && tab == marqueeTab) return false;
        marqueeCard = index; marqueePage = page; marqueeTab = tab; marqueeSince = now; marqueeNeeded = false;
        return true;
    }
    boolean marqueeActive(long now) { return marqueeCard >= 0 && marqueeNeeded && now - marqueeSince >= 1000; }
    long nextDelay(long now) { return marqueeActive(now) ? 50L : marqueeCard >= 0 && marqueeNeeded ? Math.max(1L, 1000L - (now - marqueeSince)) : 1000L; }
    void begin(float scale, float night, int tab, int page, boolean search, boolean focused, Paint title, Paint meta) {
        k = scale; this.night = night; this.tab = tab; cardPage = page; searchMode = search; cardsFocused = focused;
        lCardTitle = title; lCardMeta = meta; marqueeNeeded = false;
        if (lastScale != k) { shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL)); rim.setStrokeWidth(2.5f * k); lastScale = k; }
    }
    private int tone(int a, int b) { return blend(a,b,night); }
    private float tone(float a, float b) { return a + (b - a) * night; }
    private static int blend(int a, int b, float f) { return ActionSurface.blend(a,b,f); }

    void draw(Canvas c, Recs.Pick p, RectF base, float v, int i) {
        float f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        // Every card, including New Releases, displays its original portrait poster.
        Bitmap img = recs.poster(p);
        if (img != null) {
            images.draw(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != capacity) {
                lightSheens = new LinearGradient[capacity];
                lightRims = new LinearGradient[capacity];
                lightShaderW = new float[capacity];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        String title = p.title == null ? "" : p.title;
        drawCardMarquee(c, title, lCardTitle, base.left, base.bottom + HomeLayout.POSTER_TITLE_BASELINE * k, bw, i);
        if (p.id == -1) {
            questionPaint.set(lCardTitle);
            questionPaint.setTextSize(100 * k);
            questionPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText("?", cell.centerX(), cell.centerY() - (questionPaint.ascent() + questionPaint.descent()) / 2, questionPaint);
            return;
        }
        if (p.watchingKey != null) {
            drawCardMarquee(c, p.meta, lCardMeta, base.left,
                    base.bottom + HomeLayout.POSTER_META_BASELINE * k, bw, i);
            return;
        }
        PosterText.Entry text = posterText.get(p);
        String detail = text.detail(tab == 0 && !searchMode);
        float metaLeft = base.left;
        float metaWidth = bw;
        float metaBaseline = base.bottom + HomeLayout.POSTER_META_BASELINE * k;
        if (p.rating > 0.0) {
            String rating = text.rating;
            float pad = 6 * k;
            Paint ratingText = ratingTextScratch;
            ratingText.set(lCardMeta);
            ratingText.setTypeface(ratingStyle.bold(lCardMeta.getTypeface()));
            // Match the translucent glass controls without sacrificing contrast.
            // Use the actual day/night transition, not the metadata text color.
            ratingText.setColor(p.rating >= 6.0 && p.rating < 9.0 ? 0xFF000000 : 0xFFFFFFFF);
            ratingText.setShadowLayer(0.85f * k, 0f, 0.6f * k, 0x66000000);
            float badgeWidth = ratingText.measureText(rating) + 2 * pad;
            float badgeTop = metaBaseline + ratingText.ascent() - 1.5f * k;
            float badgeBottom = metaBaseline + ratingText.descent() + 1.5f * k;
            RectF badge = ratingBadgeScratch;
            badge.set(base.left, badgeTop, base.left + badgeWidth, badgeBottom);
            float badgeRadius = 4 * k;
            Paint badgeFill = ratingFillScratch;
            badgeFill.reset();
            badgeFill.setAntiAlias(true);
            badgeFill.setStyle(Paint.Style.FILL);
            // Smoke glass in light mode, frosted glass in dark mode.
            badgeFill.setColor(text.badgeColor);
            c.drawRoundRect(badge, badgeRadius, badgeRadius, badgeFill);
            // A subtle highlight and fine rim reproduce the upper glass buttons.
            Paint badgeSheen = ratingSheenScratch;
            badgeSheen.reset();
            badgeSheen.setAntiAlias(true);
            badgeSheen.setShader(ratingStyle.sheen(i, badge.left, badge.top, badge.bottom,
                    blend(0x20FFFFFF, 0x32FFFFFF, night)));
            c.drawRoundRect(badge, badgeRadius, badgeRadius, badgeSheen);
            Paint badgeRim = ratingRimScratch;
            badgeRim.reset();
            badgeRim.setAntiAlias(true);
            badgeRim.setStyle(Paint.Style.STROKE);
            badgeRim.setStrokeWidth(0.8f * k);
            badgeRim.setColor(blend(0x45FFFFFF, 0x66FFFFFF, night));
            ratingInsetScratch.set(badge.left + 0.4f * k, badge.top + 0.4f * k,
                    badge.right - 0.4f * k, badge.bottom - 0.4f * k);
            c.drawRoundRect(ratingInsetScratch, badgeRadius, badgeRadius, badgeRim);
            c.drawText(rating, base.left + pad, metaBaseline, ratingText);
            metaLeft += badgeWidth + 19 * k;
            metaWidth -= badgeWidth + 19 * k;
        }
        if (!detail.isEmpty() && metaWidth > 0) {
            drawCardMarquee(c, detail, lCardMeta, metaLeft, metaBaseline, metaWidth, i);
        }
    }

    private void drawCardMarquee(Canvas c, String value, Paint paint, float left, float baseline, float width, int i) {
        float textWidth = paint.measureText(value);
        boolean focusedOverflow = cardsFocused && i == marqueeCard
                && cardPage == marqueePage && tab == marqueeTab && textWidth > width;
        if (focusedOverflow) marqueeNeeded = true;
        if (focusedOverflow && SystemClock.uptimeMillis() - marqueeSince >= 1000) {
            // Ping-pong marquee: scroll left, pause, scroll right, pause, repeat.
            float overflow = textWidth - width;
            float travelMs = overflow / (42f * k) * 1000f;
            float pauseMs = 500f;
            float cycle = 2f * travelMs + 2f * pauseMs;
            float elapsed = (SystemClock.uptimeMillis() - marqueeSince - 1000) % cycle;
            float offset;
            if (elapsed < travelMs) offset = elapsed / travelMs * overflow;
            else if (elapsed < travelMs + pauseMs) offset = overflow;
            else if (elapsed < 2f * travelMs + pauseMs)
                offset = overflow - (elapsed - travelMs - pauseMs) / travelMs * overflow;
            else offset = 0f;
            // Render into a layer so the letters fade out instead of being cut at the edges.
            float top = baseline + paint.ascent() - 4 * k;
            float bottom = baseline + paint.descent() + 4 * k;
            int layer = c.saveLayer(left, top, left + width, bottom, null);
            c.clipRect(left, top, left + width, bottom);
            c.drawText(value, left - offset, baseline, paint);
            float edge = Math.min(20f * k, width * 0.18f);
            if (edge > 0f) {
                Paint mask = marqueeMaskScratch;
                mask.setXfermode(textEdgeMode);
                // Fade at the leading/trailing clipped edge, depending on the text position.
                boolean leftClipped = offset > 0.5f * k;
                boolean rightClipped = overflow - offset > 0.5f * k;
                mask.setShader(new LinearGradient(left, 0, left + width, 0,
                        new int[] {
                                leftClipped ? 0x00FFFFFF : 0xFFFFFFFF,
                                0xFFFFFFFF, 0xFFFFFFFF,
                                rightClipped ? 0x00FFFFFF : 0xFFFFFFFF
                        },
                        new float[] {0f, edge / width, 1f - edge / width, 1f},
                        Shader.TileMode.CLAMP));
                c.drawRect(left, top, left + width, bottom, mask);
                mask.setShader(null);
                mask.setXfermode(null);
            }
            c.restoreToCount(layer);
        } else {
            ellipsizeScratch.set(paint);
            c.drawText(TextUtils.ellipsize(value, ellipsizeScratch, width, TextUtils.TruncateAt.END).toString(), left, baseline, paint);
        }
    }
}
