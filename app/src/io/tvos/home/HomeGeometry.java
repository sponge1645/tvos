package io.tvos.home;

import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.function.IntPredicate;

/** Home region geometry only; callers supply visibility, order, labels and typography. */
public final class HomeGeometry {
    private static final float MARGIN = HomeLayout.EDGE, CONTENT_R = HomeLayout.RIGHT;
    private static final float SHELF_PAD = HomeLayout.DOCK_PAD, TILE_GAP = HomeLayout.DOCK_GAP;
    private static final float ROW_TOP = 540, ROW_H = 126, CARDS_H = HomeLayout.WEATHER_HEIGHT;
    private final Paint clockMeasure = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics clockMetrics = new Paint.FontMetrics();

    public void shelf(RectF platter, RectF[] tiles, int count, float w, float h, float k) {
        int n = count;
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float tw = HomeLayout.dockTileWidth(n, k);
        float th = tw / HomeLayout.DOCK_ASPECT;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float bottom = HomeLayout.dockBottom(h, k);
        float top = bottom - th - 2 * pad;
        platter.set(left - pad, top, left + width + pad, bottom);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tiles[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
    }

    public float drawer(RectF[] rects, int count, int inputCount, RectF weatherCard, float h, float k) {
        int n = count;
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = inputCount > 0 && inputCount < n ? 2 : 1;
        float tw = Math.max(1, (right - left - Math.max(0, n - groups) * gap
                - (groups - 1) * groupGap) / Math.max(1, n));
        float rowHeight = Math.max(ROW_H * k, Math.min(168 * k, tw * ROW_H / 230f));
        float extraHeight = n == 0 ? 0 : rowHeight - ROW_H * k;
        float x = left, top = ROW_TOP * k - extraHeight;
        for (int i = 0; i < n; i++) {
            if (i == inputCount && i > 0) x += groupGap - gap;
            rects[i].set(x, top, x + tw, top + rowHeight);
            x += tw + gap;
        }
        float cardsTop = HomeLayout.weatherTop(h, k);
        weatherCard.set(left, cardsTop, right, cardsTop + CARDS_H * k);
        return extraHeight;
    }

    public float tabs(RectF[] topRects, int[] order, IntPredicate enabled, String[] labels, Paint lTab, Paint lTabOn, boolean compact, float k) {
        return tabs(topRects, order, enabled, labels, lTab, lTabOn, compact, k, MARGIN * k, CONTENT_R * k);
    }

    /** One shared header row. Long genre labels yield space before the corner controls. */
    public float tabs(RectF[] topRects, int[] order, IntPredicate enabled, String[] labels,
            Paint lTab, Paint lTabOn, boolean compact, float k, float left, float right) {
        float pad = 26 * k, gap = 4 * k;
        float total = 0; int count = 0;
        for (int i : order) {
            if (!enabled.test(i)) { topRects[i].setEmpty(); continue; }
            tabWidths[i] = Math.max(lTabOn.measureText(labels[i]), lTab.measureText(labels[i])) + 2 * pad;
            total += tabWidths[i]; count++;
        }
        float available = Math.max(0, right - left - Math.max(0, count - 1) * gap);
        float cap = Float.MAX_VALUE;
        if (total > available) {
            float low = 0, high = total;
            for (int n = 0; n < 20; n++) {
                float mid = (low + high) / 2, sum = 0;
                for (int i : order) if (enabled.test(i)) sum += Math.min(tabWidths[i], mid);
                if (sum > available) high = mid; else low = mid;
            }
            cap = low;
        }
        float x = left, top = HomeLayout.TOP * k;
        for (int i : order) if (enabled.test(i)) {
            float width = Math.min(tabWidths[i], cap);
            topRects[i].set(x, top, x + width, top + HomeLayout.BUTTON * k);
            x += width + gap;
        }
        return (HomeLayout.TOP + HomeLayout.BUTTON) * k;
    }
    private final float[] tabWidths = new float[5];

    public void posters(RectF[] cardRects, boolean compact, float contentTop, float contentBottom, Paint lCardMeta, float k) {
        float regularW = ((CONTENT_R - MARGIN) * k - 5 * 18 * k) / 6f;
        // Keep compact-header posters 10% larger; center poster + all captions as one block.
        float posterW = regularW * (compact ? 1.10f : 1f);
        float posterH = posterW * 1.5f;
        float captionHeight = HomeLayout.POSTER_META_BASELINE * k + lCardMeta.descent() + 1.5f * k;
        float top = HomeLayout.centeredTop(contentTop, contentBottom, posterH + captionHeight);
        float spacing = ((CONTENT_R - MARGIN) * k - 5 * posterW) / 4f;
        float left = MARGIN * k;
        for (int i = 0; i < 5; i++) {
            float px = left + i * (posterW + spacing);
            cardRects[i].set(px, top, px + posterW, top + posterH);
        }
    }

    public void actions(RectF[] topRects, int[] buttons, IntPredicate enabled, String clockText, Typeface typeface,
            float b, float right, float cy, float gap, float k) {
        // Pack variable-width clock and fixed-width actions together in the configured order.
        for (int position = buttons.length - 1; position >= 0; position--) {
            int id = buttons[position], control = 5 + id;
            if (!enabled.test(id)) { topRects[control].setEmpty(); continue; }
            if (id == 4) {
                clockMeasure.setTypeface(typeface);
                float unit = k * HomeLayout.BUTTON_SCALE;
                clockMeasure.setTextSize(27 * unit);
                clockMeasure.getFontMetrics(clockMetrics);
                Paint.FontMetrics fm = clockMetrics;
                float glyphHeight = fm.descent - fm.ascent;
                float textInset = Math.max(5 * unit, (b - glyphHeight) / 2f);
                float avatarDiameter = b - 10 * unit;
                float capsuleWidth = textInset + 5 * unit + clockMeasure.measureText(clockText) + 10 * unit
                        + avatarDiameter + 5 * unit + 5 * unit;
                topRects[9].set(right - capsuleWidth, cy - b / 2f, right, cy + b / 2f);
                right = topRects[9].left - gap;
            } else {
                topRects[control].set(right - b, cy - b / 2, right, cy + b / 2);
                right -= b + gap;
            }
        }
    }
}
