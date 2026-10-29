package io.tvos;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.List;

/** Header date/weather, tabs, search heading, clock/profile and action buttons. */
final class HomeHeaderRenderer {
    static final class Frame {
        float k, night, headingOpacity, vpnTint;
        int tab, networkState;
        int hiddenTab = -1;
        boolean showDate, weatherTop, searchMode, largePostersWithoutWidgets;
        String clockText, dateText, searchQuery;
        String[] tabs;
        RectF[] topRects;
        Spring[] topFocus;
        List<HomeApps.Item> lightButtons;
        Paint lDate, lTab, lTabOn;
    }
    private static final float MARGIN = HomeLayout.EDGE, L_BUTTON_CY = HomeLayout.HEADER_CY, L_TABBAR_TOP = HomeLayout.TAB_TOP, SHADOW_BLUR = 26;
    private static final int L_INK = 0xFF1D1D1F, WHITE = 0xFFF5F5F7;
    private final Context context;
    private final HomeData data;
    private final ActionSurface actionSurface;
    private final Shadows shadows;
    private final ProfileAvatar profileAvatar;
    private int profileAvatarIndex = -1;
    private Frame frame;
    private final Paint headerDateScratch = new Paint(), headerWeatherScratch = new Paint(), searchHeadingScratch = new Paint();
    private final Paint.FontMetrics headerMetricsScratch = new Paint.FontMetrics(), clockMetricsScratch = new Paint.FontMetrics();
    private final Paint clockCapsuleScratch = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF avatarShouldersScratch = new RectF(), cell = new RectF(), tmp = new RectF();

    HomeHeaderRenderer(Context context, HomeData data, ActionSurface actionSurface, Shadows shadows) {
        this.context = context; this.data = data; this.actionSurface = actionSurface; this.shadows = shadows;
        profileAvatar = new ProfileAvatar(context);
    }
    void resume() {
        int next = ProfileAvatar.selected(context);
        if (next != profileAvatarIndex) profileAvatar.clear();
        profileAvatarIndex = next;
    }
    void resize(float k) { shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL)); }
    void clearFrame() {
        if (frame != null) {
            frame.topRects = null; frame.topFocus = null; frame.lightButtons = null;
            frame.lDate = frame.lTab = frame.lTabOn = null;
            frame.clockText = frame.dateText = frame.searchQuery = null; frame.tabs = null;
        }
        frame = null;
    }
    private int tone(int a, int b) { return blend(a,b,frame.night); }
    private float tone(float a, float b) { return a + (b - a) * frame.night; }
    private static int blend(int a, int b, float f) { return ActionSurface.blend(a,b,f); }

    void drawDateWeather(Canvas c, Frame frame) {
        this.frame = frame;
        final float x = MARGIN * frame.k;
        final float cy = L_BUTTON_CY * frame.k;
        Paint datePaint = headerDateScratch;
        datePaint.set(frame.lDate);
        datePaint.setTextSize(29 * frame.k);
        datePaint.setColor(tone(L_INK, WHITE));
        Paint.FontMetrics fm = headerMetricsScratch;
        datePaint.getFontMetrics(fm);
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        float nextX = x;
        if (frame.showDate) {
            String date = frame.dateText;
            if (!date.isEmpty()) date = date.substring(0, 1).toUpperCase(Ui.locale()) + date.substring(1);
            c.drawText(date, x - Ui.leftBearing(datePaint, date), baseline, datePaint);
            nextX = x + datePaint.measureText(date) + 30 * frame.k;
        }
        HomeData.Weather wx = data.weather;
        if (!frame.weatherTop || Double.isNaN(wx.temp)) return;
        Paint weatherPaint = headerWeatherScratch;
        weatherPaint.set(datePaint);
        weatherPaint.setTextSize(27 * frame.k);
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float iconSize = 28 * frame.k;
        float sw = Symbols.width(context, symbol, iconSize);
        Symbols.draw(context, c, symbol, nextX + sw / 2f - 3f,
                cy, iconSize, weatherPaint.getColor());
        c.drawText(HomeData.degrees(wx.temp), nextX + sw + 10 * frame.k, baseline, weatherPaint);
    }

    private float drawActionSurface(Canvas c, RectF bounds, float focus) {
        float scale = actionSurface.draw(c, bounds, focus, frame.night, frame.k);
        cell.set(actionSurface.bounds);
        return scale;
    }

    private void drawClockProfileCapsule(Canvas c) {
        if (!Prefs.topButtonEnabled(4)) return;
        float unit = frame.k * HomeLayout.BUTTON_SCALE;
        RectF capsule = frame.topRects[9];
        if (capsule.isEmpty()) return;
        float clockFocus = Ui.clamp(frame.topFocus[9].value);
        float scale = drawActionSurface(c, capsule, clockFocus);
        int saved = c.save();
        c.scale(scale, scale, capsule.centerX(), capsule.centerY());
        Paint p = clockCapsuleScratch;
        p.reset();
        p.setAntiAlias(true);
        // Dark text on the white focused capsule in both themes, just like Search.
        p.setColor(blend(tone(L_INK, 0xEBFFFFFF), L_INK, clockFocus));
        p.setTypeface(Ui.displayMedium);
        p.setTextSize(27 * unit);
        String time = frame.clockText;
        Paint.FontMetrics clockFm = clockMetricsScratch;
        p.getFontMetrics(clockFm);
        float clockInset = Math.max(5 * unit, (capsule.height() - (clockFm.descent - clockFm.ascent)) / 2f);
        float clockBaseline = capsule.centerY() - (clockFm.ascent + clockFm.descent) / 2f;
        c.drawText(time, capsule.left + clockInset + 5 * unit, clockBaseline, p);
        float avatarRadius = Math.max(1f, capsule.height() / 2f - 5 * unit);
        float ax = capsule.right - 5 * unit - avatarRadius, ay = capsule.centerY();
        if (profileAvatarIndex >= 0) {
            profileAvatar.draw(c, profileAvatarIndex, ax, ay, avatarRadius);
            c.restoreToCount(saved);
            return;
        }
        p.setColor(tone(0xFFD5E1E8, 0xFF485662));
        c.drawCircle(ax, ay, avatarRadius, p);
        p.setColor(tone(0xFF41505D, 0xFFF1F3F5));
        c.drawCircle(ax, ay - 7 * unit, 7 * unit, p);
        RectF shoulders = avatarShouldersScratch;
        shoulders.set(ax - 13 * unit, ay + 3 * unit, ax + 13 * unit, ay + 16 * unit);
        c.drawRoundRect(shoulders, 11 * unit, 11 * unit, p);
        c.restoreToCount(saved);
    }

    void drawButtons(Canvas c, Frame frame) {
        float unit = frame.k * HomeLayout.BUTTON_SCALE;
        this.frame = frame;
        drawClockProfileCapsule(c);
        if (Prefs.topButtonEnabled(5)) {
            float networkFocus = Ui.clamp(frame.topFocus[10].value);
            float networkScale = drawActionSurface(c, frame.topRects[10], networkFocus);
            String networkSymbol = frame.networkState == NetworkStatus.WIFI ? "wifi"
                    : frame.networkState == NetworkStatus.ETHERNET ? "ethernet" : "nonetwork";
            Symbols.draw(context, c, networkSymbol, cell.centerX(), cell.centerY(),
                    30 * unit * networkScale, blend(tone(L_INK, 0xEBFFFFFF), L_INK, networkFocus));
        }
        for (int i = 0; i < frame.lightButtons.size(); i++) {
            if (!Prefs.topButtonEnabled(i)) continue;
            RectF r = frame.topRects[5 + i];
            float f = Ui.clamp(frame.topFocus[5 + i].value);
            float s = drawActionSurface(c, r, f);
            Symbols.draw(context, c, frame.lightButtons.get(i).symbol, cell.centerX(), cell.centerY(),
                    27f * 1.15f * unit * s,
                    blend(blend(tone(L_INK, 0xEBFFFFFF), L_INK, f), 0xFF618FEF,
                            "vpn".equals(frame.lightButtons.get(i).key) ? frame.vpnTint : 0));

        }
    }

    void drawTabs(Canvas c, Frame frame) {
        this.frame = frame;
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(frame.topFocus[frame.tab].value);
        for (int i = 0; i < 5 && !frame.searchMode; i++) {
            if (!Prefs.categoryEnabled(i) || i == frame.hiddenTab) continue;
            RectF r = frame.topRects[i];
            if (i == frame.tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * frame.k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * frame.k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == frame.tab ? frame.lTabOn : frame.lTab;
            if (i == frame.tab) frame.lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(frame.tabs[i], r.centerX() - p.measureText(frame.tabs[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (frame.searchMode && frame.headingOpacity > 0.001f) {
            Paint heading = searchHeadingScratch;
            heading.set(frame.lTabOn);
            heading.setColor(Ui.alpha(tone(L_INK, WHITE), Ui.clamp(frame.headingOpacity)));
            c.drawText((AppStrings.get(R.string.home_search_heading_prefix)) + frame.searchQuery, MARGIN * frame.k,
                    (frame.largePostersWithoutWidgets ? HomeLayout.TOP + 48 : L_TABBAR_TOP + 48) * frame.k, heading);
        }
    }
}
