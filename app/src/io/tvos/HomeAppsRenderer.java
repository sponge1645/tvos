package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.List;

/** Dock, drawer and installed-app surfaces. Layout, list ownership, input and springs stay in HomeView. */
final class HomeAppsRenderer {
    static final class Frame {
        List<HomeApps.Item> shelf, drawerItems, others;
        Spring[] tileFocus, dockSlide, drawerFocus, gridFocus;
        RectF[] tileRects, drawerRects;
        RectF platterRect, weatherCard, focusedCell;
        int dockLiftIndex, drawerInputs, gridIndex, w, h;
        long dockWiggleAt;
        float k, night, dockLift, scroll;
        boolean sheetOpen, weatherBottom;
    }
    private static final float TILE_RADIUS = 22, SHELF_PAD = 40, FOCUS_GROW = .2f, SHADOW_BLUR = 26, DEEP_BLUR = 30, ROW_TITLE = 518;
    private static final long DOCK_WIGGLE_MS = 300;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7, L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final int COLUMNS = HomeAccessibility.COLUMNS;
    private static final float GRID_W = HomeAccessibility.GRID_W, GRID_H = HomeAccessibility.GRID_H,
            GRID_GAP = HomeAccessibility.GRID_GAP, GRID_ROW = HomeAccessibility.GRID_ROW, GRID_TOP = HomeAccessibility.GRID_TOP, GRID_RADIUS = 22;
    private final Context context;
    private final Scene scene;
    private final Shadows shadows;
    private final HomeWidgets widgets;
    private final RoundedImages images;
    private final DockLogoRenderer dockLogos = new DockLogoRenderer();
    private Frame frame;
    private final RectF tmp = new RectF(), cell = new RectF(), gridCell = new RectF();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), tileFill = new Paint(Paint.ANTI_ALIAS_FLAG),
            sheen = new Paint(Paint.ANTI_ALIAS_FLAG), veil = new Paint(Paint.ANTI_ALIAS_FLAG),
            rim = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint rowTitle, drawerLabel, sheetTitle;
    private TextPaint gridLabel;
    private final TextPaint fitPaint = new TextPaint();
    private String[] gridLabels;
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private final Paint dockHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dockHighlightPath = new Path();
    private final RectF dockHighlightArc = new RectF();
    private float dockHighlightW = -1, dockHighlightH = -1, dockHighlightRadius = -1, dockHighlightDensity = -1;

    HomeAppsRenderer(Context context, Scene scene, Shadows shadows, HomeWidgets widgets, RoundedImages images) {
        this.context = context; this.scene = scene; this.shadows = shadows; this.widgets = widgets; this.images = images;
    }
    void resize(float k) {
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF); drawerLabel.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF); sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF)); gridLabel.setTextAlign(Paint.Align.CENTER);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE); rim.setStrokeWidth(2.5f * k);
        invalidateLabels();
    }
    void clear() {
        dockLogos.clear();
        if (frame != null) {
            frame.shelf = frame.drawerItems = frame.others = null;
            frame.tileRects = frame.drawerRects = null;
            frame.tileFocus = frame.dockSlide = frame.drawerFocus = frame.gridFocus = null;
            frame.platterRect = frame.weatherCard = frame.focusedCell = null;
        }
        frame = null;
    }
    void invalidateLabels() { gridLabels = null; }
    void invalidateDock() { tileShaders = new LinearGradient[0]; }
    void swapDock(int i, int j, int count) {
        if (tileShaders.length == count) { LinearGradient g = tileShaders[i]; tileShaders[i] = tileShaders[j]; tileShaders[j] = g; }
    }
    void restoreDock(List<HomeApps.Item> before, List<HomeApps.Item> current) {
        if (tileShaders.length != current.size()) return;
        LinearGradient[] restored = new LinearGradient[current.size()];
        for (int a = 0; a < restored.length; a++) { int b = current.indexOf(before.get(a)); restored[a] = tileShaders[b < 0 ? a : b]; }
        tileShaders = restored;
    }
    private int tone(int a, int b) { return blend(a,b,frame.night); }
    private float tone(float a, float b) { return a + (b - a) * frame.night; }
    private static int blend(int a, int b, float f) { return ActionSurface.blend(a,b,f); }

    void drawPlatter(Canvas c, Frame frame) {
        this.frame = frame;
        if (frame.shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * frame.k;
        tmp.set(frame.platterRect);
        tmp.offset(0, 18 * frame.k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * frame.k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, frame.platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * frame.k, tone(22, 28) * frame.k, tone(0.65f, 0.95f), 1);
    }

    void drawTiles(Canvas c, Frame frame) {
        this.frame = frame;
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < frame.shelf.size(); i++) {
            if (frame.tileFocus[i].value > most) {
                most = frame.tileFocus[i].value;
                top = i;
            }
        }
        if (frame.dockLiftIndex >= 0 && frame.dockLiftIndex < frame.shelf.size() && frame.dockLift > 0.02f) top = frame.dockLiftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < frame.shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float dx = i < frame.dockSlide.length ? frame.dockSlide[i].value : 0;
                float angle = i == frame.dockLiftIndex ? (float)Math.sin((SystemClock.uptimeMillis() - frame.dockWiggleAt) * 2 * Math.PI / DOCK_WIGGLE_MS) * 1.5f * frame.dockLift : 0;
                c.save();
                if (dx != 0) c.translate(dx, 0);
                if (angle != 0) c.rotate(angle, frame.tileRects[i].centerX(), frame.tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = frame.shelf.get(i);
        boolean lifted = i == frame.dockLiftIndex && frame.dockLift > 0.001f;
        if (lifted) {
            RectF b = frame.tileRects[i];
            c.save();
            float scale = 1 + 0.08f * frame.dockLift;
            c.scale(scale, scale, b.centerX(), b.centerY());
        }
        float f = frame.tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = frame.tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * frame.k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * frame.k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * frame.k, shadowDeep);
        c.restore();
        AppBranding.Style style = AppBranding.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != frame.shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[frame.shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style.top, style.bottom, Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style.mode);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) images.drawCropped(c, it.tile, cell, r);
        }
        if (it.logo != null || it.tile != null) {
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            drawDockHighlight(c, bw, bh, r0, fc);
            c.restore();
        }
        if (lifted) c.restore();
    }

    private void drawDockHighlight(Canvas c, float width, float height, float radius, float focus) {
        if (dockHighlightW != width || dockHighlightH != height
                || dockHighlightRadius != radius || dockHighlightDensity != frame.k) {
            dockHighlightW = width;
            dockHighlightH = height;
            dockHighlightRadius = radius;
            dockHighlightDensity = frame.k;
            // Keep both strokes inside the tile. Follow only the upper corners, with no bottom rim.
            float inset = 1.5f * frame.k;
            float left = -width / 2f + inset, right = width / 2f - inset;
            float top = -height / 2f + inset;
            float r = Math.max(0, Math.min(radius - inset, Math.min(width, height) / 2f - inset));
            dockHighlightPath.rewind();
            dockHighlightPath.moveTo(left, top + r);
            dockHighlightArc.set(left, top, left + 2 * r, top + 2 * r);
            dockHighlightPath.arcTo(dockHighlightArc, 180, 90);
            dockHighlightPath.lineTo(right - r, top);
            dockHighlightArc.set(right - 2 * r, top, right, top + 2 * r);
            dockHighlightPath.arcTo(dockHighlightArc, 270, 90);
            dockHighlightPaint.setStyle(Paint.Style.STROKE);
            dockHighlightPaint.setStrokeCap(Paint.Cap.ROUND);
            dockHighlightPaint.setShader(new LinearGradient(left, 0, right, 0,
                    new int[] {0x00FFFFFF, 0x70FFFFFF, 0xB8FFFFFF, 0x70FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.2f, 0.5f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        }
        // Broad, faint shoulder under a fine bright edge; changing focus needs no new shader or bitmap.
        float strength = 0.72f + 0.28f * Ui.clamp(focus);
        dockHighlightPaint.setStrokeWidth(2.8f * frame.k);
        dockHighlightPaint.setAlpha(Math.round(48 * strength));
        c.drawPath(dockHighlightPath, dockHighlightPaint);
        dockHighlightPaint.setStrokeWidth(1.1f * frame.k);
        dockHighlightPaint.setAlpha(Math.round(255 * strength));
        c.drawPath(dockHighlightPath, dockHighlightPaint);
    }

    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        dockLogos.draw(c, it, box, s, mode, frame.tileRects.length == 0 ? null : frame.tileRects[0], FOCUS_GROW);
    }

    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = frame.night;
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * frame.k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * frame.k, 0.72f, 0x1A000000, 10 * frame.k);
    }

    void drawDrawer(Canvas c, Frame frame) {
        this.frame = frame;
        if (frame.drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        float titleBase = ROW_TITLE * frame.k;
        if (frame.drawerInputs > 0) c.drawText(AppStrings.get(R.string.home_inputs), frame.drawerRects[0].left + 4 * frame.k, titleBase, rowTitle);
        if (frame.drawerInputs < frame.drawerItems.size()) c.drawText(AppStrings.get(R.string.home_shortcuts), frame.drawerRects[frame.drawerInputs].left + 4 * frame.k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < frame.drawerItems.size(); i++) if (frame.drawerFocus[i].value > 0.02f && (top < 0 || frame.drawerFocus[i].value > frame.drawerFocus[top].value)) top = i;
        for (int i = 0; i < frame.drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (frame.weatherBottom) drawWeatherCard(c, frame.weatherCard);
    }

    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = frame.drawerItems.get(i);
        float f = Ui.clamp(frame.drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = frame.drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * frame.k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * frame.k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * frame.k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * frame.k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(context, c, it.symbol, cx, cell.top + cell.height() * 0.4f, 37.4f * frame.k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * frame.k * s);
        float room = cell.width() - 16 * frame.k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * frame.k * s, 23 * frame.k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * frame.k);
    }

    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        widgets.weather(c, r, frame.night);
    }

    void drawSheet(Canvas c, Frame frame) {
        this.frame = frame;
        c.drawColor(0xA8000000);
        float shift = frame.scroll * GRID_ROW * frame.k;
        c.drawText(AppStrings.get(R.string.home_all_apps), frame.w / 2f, HomeLayout.HEADER_CY * frame.k + Ui.halfCap(sheetTitle) - shift, sheetTitle);
        int n = frame.others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(frame.others.get(i).label, gridLabel, (GRID_W - 10) * frame.k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * frame.k;
        float gridTop = GRID_TOP * frame.k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * frame.k - shift;
            if (y > frame.h || y + GRID_ROW * frame.k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * frame.k;
            gridCell.set(x, y, x + GRID_W * frame.k, y + GRID_H * frame.k);
            float f = frame.gridFocus[i].value;
            drawCard(c, frame.others.get(i).tile, gridCell, GRID_RADIUS * frame.k, f, 0.08f);
            if (frame.sheetOpen && i == frame.gridIndex) frame.focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * frame.k, gridLabel);
        }
    }

    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * frame.k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * frame.k, shadow);
        }
        if (image != null) images.draw(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * frame.k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * frame.k);
        }
    }
}
