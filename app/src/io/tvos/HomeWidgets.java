package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.function.Consumer;

/** Weather content and bounded drawing caches; no glass or navigation ownership. */
final class HomeWidgets {
    private static final int L_INK=0xFF1D1D1F,L_DIM=0xFF6E6E73;
    private final Context context;
    private final HomeData data;
    private float k=1,night;
    private Paint cardTitle,cardText,cardDim,bigTemp,hourPaint,hourTemp;
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp=new RectF();
    HomeWidgets(Context context,HomeData data){this.context=context.getApplicationContext();this.data=data;}
    void resize(float scale){k=scale;
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 31.2f * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        clear();
    }
    void clear(){weatherSnapshot.clear();}
    private void prepare(float night){
        this.night=night;
        cardTitle.setColor(tone(L_DIM,0xB3FFFFFF));cardText.setColor(drawerInk(.95f));
        cardDim.setColor(tone(L_DIM,0xADFFFFFF));
        bigTemp.setColor(drawerInk(1));hourPaint.setColor(tone(L_DIM,0xADFFFFFF));
        hourTemp.setColor(drawerInk(.95f));
    }
    void weather(Canvas c,RectF r,float night){prepare(night);
        if(Ui.LEGACY)weatherSnapshot.draw(c,r,drawerCardsKey(),cc->drawWeather(cc,r));else drawWeather(c,r);
    }

    private float dark(){return night;}
    private int tone(int light,int dark){return blend(light,dark,night);}
    private int drawerInk(float alpha){return Ui.alpha(tone(L_INK,0xFFFFFFFF),alpha);}
    private HomeData.Weather textWeather;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final Snapshot weatherSnapshot=new Snapshot();

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(AppStrings.get(R.string.home_no_weather_data_right_now), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(context, symbol, 52 * k);
        Symbols.draw(context, c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 54 * k, hourPaint);
            String hourSymbol = Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i));
            float iconSize = 36 * k;
            float iconHeight = Symbols.height(context, hourSymbol, iconSize);
            // Center the combined icon + temperature on the card, independently of the hour heading.
            float iconY = r.centerY() - (54 * k - iconHeight / 2) / 2;
            float temperatureBase = iconHeight == 0 ? r.centerY() + Ui.halfCap(hourTemp) : iconY + 54 * k;
            Symbols.draw(context, c, hourSymbol, hc, iconY, iconSize, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, temperatureBase, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? AppStrings.get(R.string.home_weather) : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = String.format(Ui.locale(), AppStrings.get(R.string.home_weather_high_low),
                    HomeData.degrees(wx.max), HomeData.degrees(wx.min));
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = AppStrings.get(R.string.home_sunrise) + Ui.time(wx.sunrise);
            sunsetText = AppStrings.get(R.string.home_sunset) + Ui.time(wx.sunset);
        }

    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(context, symbol, size);
        Symbols.draw(context, c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    private String drawerCardsKey() {
        return data.weatherAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark());
    }

    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void clear() { bitmap = null; key = ""; }

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

}
