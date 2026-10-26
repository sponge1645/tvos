package io.tvos;

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

/** Random-movie strip drawing and reusable graphics scratch; spin state stays in RouletteController. */
final class RouletteRenderer {
    interface Images { void draw(Canvas canvas, Bitmap bitmap, RectF bounds, float radius); }
    private static final float MARGIN = 80, CONTENT_R = 1840, DEEP_BLUR = 30;
    private static final int L_INK = 0xFF1D1D1F, WHITE = 0xFFF5F5F7, SMOKE = 0xFF111113;
    private final RouletteController rouletteWork;
    private final Recs recs;
    private final Scene scene;
    private final Shadows shadows;
    private final ActionSurface actionSurface;
    private final Images images;
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint ellipsizeScratch = new TextPaint();
    private final PorterDuffXfermode textEdgeMode = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
    private float night, shadowScale = -1;
    private final Paint rouletteTitleScratch = new Paint();
    private final Paint rouletteHintScratch = new Paint();
    private final Paint rouletteWinnerSheenScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rouletteWinnerRimScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rouletteEdgeScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float rouletteEdgeLeft = Float.NaN, rouletteEdgeRight = Float.NaN;
    private final RectF rouletteHintOneScratch = new RectF();
    private final RectF rouletteHintTwoScratch = new RectF();
    private final RectF roulettePosterRect = new RectF();
    private final RectF rouletteShadowRect = new RectF();
    private final RectF rouletteGhostRect = new RectF();
    private final Paint rouletteGhostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    { rouletteGhostPaint.setAlpha(25); }
    private final RectF rouletteButtonRect = new RectF();
    private final Paint rouletteTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    RouletteRenderer(RouletteController work, Recs recs, Scene scene, Shadows shadows,
            ActionSurface actionSurface, Images images) {
        rouletteWork = work;
        this.recs = recs;
        this.scene = scene;
        this.shadows = shadows;
        this.actionSurface = actionSurface;
        this.images = images;
    }

    private int tone(int light, int dark) { return ActionSurface.blend(light, dark, night); }
    private float tone(float light, float dark) { return light + (dark - light) * night; }

    static void buttonBounds(RectF out, float k) {
        float cx = (MARGIN + CONTENT_R) * k / 2f;
        float cy = 480 * k;
        out.set(cx - 295 * k, cy - 63 * k, cx + 295 * k, cy + 63 * k);
    }

    void draw(Canvas c, float k, float night, Paint lCardTitle, float buttonFocus, float cardFocus) {
        this.night = night;
        if (shadowScale != k) {
            shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
            shadowScale = k;
        }
        final float cx = (MARGIN + CONTENT_R) * k / 2f;
        final float cy = 480 * k;
        if (!rouletteWork.spinning() && !rouletteWork.finished()) {
            buttonBounds(rouletteButtonRect, k);
            RectF button = rouletteButtonRect;
            float focus = Ui.clamp(buttonFocus);
            float scale = actionSurface.draw(c, button, focus, night, k);
            rouletteTextPaint.set(lCardTitle);
            Paint p = rouletteTextPaint;
            p.setTextSize(40.8f * k);
            p.setColor(ActionSurface.blend(tone(L_INK, 0xEBFFFFFF), L_INK, focus));
            String label = rouletteWork.loading() ? (AppStrings.get(R.string.home_roulette_loading))
                    : (AppStrings.get(R.string.home_roulette_title));
            int saved = c.save();
            c.scale(scale, scale, cx, cy);
            c.drawText(label, cx - p.measureText(label) / 2, cy + Ui.halfCap(p), p);
            c.restoreToCount(saved);
            return;
        }
        if (rouletteWork.picks().isEmpty()) return;
        final float elapsed = Math.min(1f, (SystemClock.uptimeMillis() - rouletteWork.started()) / 5000f);
        // 5 seconds at the same average poster speed, with smooth acceleration and braking.
        // Integrate a trapezoidal speed profile to avoid sudden jumps in position.
        final float accel = 0.08f, brake = 0.16f;
        final float cruiseEnd = 1f - brake;
        final float area = 1f - (accel + brake) * 0.5f;
        final float distance;
        if (elapsed < accel) {
            distance = elapsed * elapsed / (2f * accel);
        } else if (elapsed < cruiseEnd) {
            distance = accel * 0.5f + elapsed - accel;
        } else {
            float t = elapsed - cruiseEnd;
            distance = accel * 0.5f + cruiseEnd - accel + t - t * t / (2f * brake);
        }
        final float totalStrides = 57f;
        final float travel = totalStrides * distance / area;
        final float cardW = 238 * k, cardH = 357 * k, gap = 28 * k;
        // Short posters while spinning; the winning poster grows as the strip settles.
        final float grow = rouletteWork.finished() ? 1f : Math.max(0f, Math.min(1f, (elapsed - 0.91f) / 0.09f));
        final float winnerGrow = grow * grow * (3f - 2f * grow);
        // Focus is independent of the result: moving up/down removes the rim,
        // returning to the poster restores it without changing the winner.
        final float winnerFocus = rouletteWork.finished() ? Ui.clamp(cardFocus) : 0f;
        final float stride = cardW + gap;
        final int count = rouletteWork.picks().size();
        final float left = MARGIN * k, right = CONTENT_R * k;
        // The strip starts at the right edge and scrolls left; fade both ends like the title marquee.
        // Leave room for the focused card's blurred shadow below the poster.
        final float layerTop = cy - cardH / 2 - 55 * k;
        final float layerBottom = cy + cardH / 2 + 85 * k;
        rouletteWork.preparePosters(Math.round(totalStrides - travel));
        c.saveLayer(left, layerTop, right, layerBottom, null);
        for (int i = -62; i <= 5; i++) {
            float pos = i + totalStrides - travel;
            // Scale both dimensions together: keep the original 238:357 poster aspect ratio.
            float posterScale = (255f + (i == 0 ? 102f * winnerGrow : 0f)) / 357f;
            float posterWidth = cardW * posterScale;
            float posterHeight = cardH * posterScale;
            float posterX = cx + pos * stride;
            if (posterX + posterWidth / 2 < left || posterX - posterWidth / 2 > right) continue;
            RectF rect = roulettePosterRect;
            rect.set(posterX - posterWidth / 2, cy - posterHeight / 2,
                    posterX + posterWidth / 2, cy + posterHeight / 2);
            Recs.Pick pick = rouletteWork.picks().get(Math.floorMod(rouletteWork.winner() + i, count));
            Bitmap bmp = recs.poster(pick);
            // Same resting shadow as unselected cards in drawLightCard().
            // The winning card gradually receives the stronger focused shadow.
            float shadowFocus = i == 0 ? winnerGrow * winnerFocus : 0f;
            RectF shadowRect = rouletteShadowRect;
            shadowRect.set(rect);
            shadowRect.offset(0, (10f + 14f * shadowFocus) * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000,
                    tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * shadowFocus));
            shadows.draw(c, shadowRect, 18 * k, DEEP_BLUR * k, shadowDeep);
            if (bmp != null) {
                // Lightweight directional trails only while moving.
                float speed = rouletteWork.spinning() ? (elapsed < accel ? elapsed / accel
                        : elapsed > cruiseEnd ? (1f - elapsed) / brake : 1f) * 12f : 0f;
                if (speed > 0.5f) {
                    for (int trail = 2; trail >= 1; trail--) {
                        Paint ghostPaint = rouletteGhostPaint;
                        c.saveLayer(rect.left - 50 * k, rect.top, rect.right, rect.bottom, ghostPaint);
                        RectF ghost = rouletteGhostRect;
                        ghost.set(rect);
                        ghost.offset(trail * speed * 4 * k, 0);
                        images.draw(c, bmp, ghost, 16 * k);
                        c.restore();
                    }
                }
                images.draw(c, bmp, rect, 16 * k);
            } else {
                fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
                c.drawRoundRect(rect, 16 * k, 16 * k, fill);
            }
            // Same highlight sheen and white gradient rim as drawLightCard().
            if (i == 0 && winnerFocus > 0.01f) {
                Paint winnerSheen = rouletteWinnerSheenScratch;
                winnerSheen.setShader(new LinearGradient(rect.left, rect.top,
                        rect.centerX(), rect.bottom,
                        0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                winnerSheen.setAlpha(Math.round(255f * winnerFocus));
                c.drawRoundRect(rect, 16 * k, 16 * k, winnerSheen);
                Paint winnerRim = rouletteWinnerRimScratch;
                winnerRim.setStyle(Paint.Style.STROKE);
                winnerRim.setStrokeWidth(2.5f * k);
                winnerRim.setShader(new LinearGradient(rect.centerX(), rect.top,
                        rect.centerX(), rect.bottom,
                        0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP));
                winnerRim.setAlpha(Math.round(255f * winnerFocus));
                c.drawRoundRect(rect, 16 * k, 16 * k, winnerRim);
            }
        }
        Paint edgeMask = rouletteEdgeScratch;
        if (rouletteEdgeLeft != left || rouletteEdgeRight != right) {
            edgeMask.setShader(new LinearGradient(left, 0, right, 0,
                    new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.10f, 0.90f, 1f}, Shader.TileMode.CLAMP));
            rouletteEdgeLeft = left;
            rouletteEdgeRight = right;
        }
        edgeMask.setXfermode(textEdgeMode);
        c.drawRect(left, layerTop, right, layerBottom, edgeMask);
        edgeMask.setXfermode(null);
        c.restore();
        if (rouletteWork.finished()) {
            Recs.Pick chosen = rouletteWork.picks().get(rouletteWork.winner());
            Paint p = rouletteTitleScratch;
            p.set(lCardTitle);
            p.setColor(tone(L_INK, WHITE));
            p.setTextSize(32 * k);
            ellipsizeScratch.set(p);
            String name = android.text.TextUtils.ellipsize(chosen.title,
                    ellipsizeScratch, 850 * k,
                    android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(name, cx - p.measureText(name) / 2, cy + 230 * k, p);
            // Two compact glass hints instead of a single unframed caption.
            Paint hintText = rouletteHintScratch;
            hintText.set(lCardTitle);
            hintText.setTextSize(23 * k);
            hintText.setColor(tone(L_INK, WHITE));
            String hintWatch = AppStrings.get(R.string.home_ok_watch);
            String hintAgain = AppStrings.get(R.string.home_right_spin_again);
            float hintPad = 24 * k;
            float hintGap = 16 * k;
            float hintH = 58 * k;
            float hintW1 = hintText.measureText(hintWatch) + 2 * hintPad;
            float hintW2 = hintText.measureText(hintAgain) + 2 * hintPad;
            float hintX = cx - (hintW1 + hintGap + hintW2) / 2f;
            float hintY = cy + 251 * k;
            RectF hintOne = rouletteHintOneScratch;
            hintOne.set(hintX, hintY, hintX + hintW1, hintY + hintH);
            RectF hintTwo = rouletteHintTwoScratch;
            hintTwo.set(hintOne.right + hintGap, hintY,
                    hintOne.right + hintGap + hintW2, hintY + hintH);
            scene.liquid(c, hintOne, 20 * k, tone(0xFFFFFFFF, SMOKE),
                    tone(0.52f, 0.48f), tone(0.02f, 0.02f), 8 * k, 5 * k,
                    tone(0.62f, 0.48f), 1f);
            scene.liquid(c, hintTwo, 20 * k, tone(0xFFFFFFFF, SMOKE),
                    tone(0.52f, 0.48f), tone(0.02f, 0.02f), 8 * k, 5 * k,
                    tone(0.62f, 0.48f), 1f);
            float hintBase = hintY + hintH / 2f + Ui.halfCap(hintText);
            c.drawText(hintWatch, hintOne.centerX() - hintText.measureText(hintWatch) / 2f,
                    hintBase, hintText);
            c.drawText(hintAgain, hintTwo.centerX() - hintText.measureText(hintAgain) / 2f,
                    hintBase, hintText);
        }
    }
}
