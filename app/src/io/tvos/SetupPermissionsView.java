package io.tvos;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import io.tvos.settings.SettingsBackground;
import io.tvos.setup.SetupArtwork;
import io.tvos.settings.SettingsRowRenderer;

/** Three short access explanations and two optional choices, drawn in the setup palette. */
final class SetupPermissionsView extends View {
    private final SettingsBackground background = new SettingsBackground();
    private final Shadows shadows = new Shadows();
    private final SettingsRowRenderer buttons = new SettingsRowRenderer(shadows::draw);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path[] icons = {SetupArtwork.location(), SetupArtwork.remote(), SetupArtwork.tv()};
    private final Spring[] focus = {new Spring(.28f, .85f, 1), new Spring(.28f, .85f, 0)};
    private final Runnable allow, deny;
    private final String title, intro, summary;
    private final String[] names, first, second, choices;
    private final boolean reduced;
    private int selected;
    private boolean pressed, confirmed;
    private final long start = SystemClock.uptimeMillis();
    private long frame = start;
    SetupPermissionsView(Context c, Runnable allow, Runnable deny) {
        super(c); this.allow = allow; this.deny = deny;
        title = AppStrings.get(R.string.settings_setup_permissions);
        intro = AppStrings.get(R.string.settings_setup_permissions_hint);
        names = new String[]{AppStrings.get(R.string.settings_setup_location), AppStrings.get(R.string.settings_setup_remote_access), AppStrings.get(R.string.settings_setup_history_access)};
        first = new String[]{AppStrings.get(R.string.settings_setup_location_line1),AppStrings.get(R.string.settings_setup_remote_line1),AppStrings.get(R.string.settings_setup_history_line1)};
        second = new String[]{AppStrings.get(R.string.settings_setup_location_line2),AppStrings.get(R.string.settings_setup_remote_line2),AppStrings.get(R.string.settings_setup_history_line2)};
        choices = new String[]{AppStrings.get(R.string.settings_setup_allow),AppStrings.get(R.string.settings_setup_deny)};
        StringBuilder spoken = new StringBuilder(title).append(". ").append(intro);
        for (int i = 0; i < names.length; i++) spoken.append(". ").append(names[i]).append(": ").append(first[i]).append(' ').append(second[i]);
        summary = spoken.toString();
        reduced = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false);
        setFocusable(true); setFocusableInTouchMode(true); describe(); setOnClickListener(v -> confirm());
    }
    private void describe() { setContentDescription(summary + ", " + choices[selected]); }
    private void confirm() { if (!confirmed) { confirmed = true; if (selected == 0) allow.run(); else deny.run(); } }
    private long exitStart = -1;
    private Runnable afterExit;
    void leave(Runnable action) {
        if (exitStart >= 0) return;
        if (reduced) action.run();
        else { afterExit = action; exitStart = SystemClock.uptimeMillis(); invalidate(); }
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); boolean light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        background.draw(c, getWidth(), getHeight(), light);
        long time = SystemClock.uptimeMillis();
        if (exitStart >= 0 && time - exitStart >= 280) {
            Runnable action = afterExit; afterExit = null; if (action != null) post(action); return;
        }
        float k = Math.min(getWidth()/1920f,getHeight()/1080f);
        float alpha = reduced ? 1 : Math.min(1, Math.max(0, (SystemClock.uptimeMillis() - start) / 260f));
        if (exitStart >= 0) alpha *= Math.max(0, 1 - (time - exitStart) / 220f);
        int saved = c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * alpha)); c.translate((getWidth()-1920*k)/2,(getHeight()-1080*k)/2); c.scale(k,k);
        int color = light ? 0xFF6E7E94 : 0xFF95B3BE;
        text.setColor(color); text.setTypeface(Ui.displayBold); text.setTextAlign(Paint.Align.CENTER); text.setTextSize(80);
        c.drawText(title,960,225,text); text.setTypeface(Ui.displaySemibold); text.setTextSize(36); c.drawText(intro,960,310,text);
        for (int i=0;i<3;i++) {
            float x=270+i*470; rect.set(x,405,x+440,690); card.setColor(light?0x226E7E94:0x3395B3BE); c.drawRoundRect(rect,26,26,card);
            icon.setColor(color); int mark=c.save(); c.translate(x+35,430); float symbolScale = i == 0 ? 56/237f : i == 1 ? 64/275f : 60/310f; c.scale(symbolScale,symbolScale); c.drawPath(icons[i],icon); c.restoreToCount(mark);
            text.setTextAlign(Paint.Align.LEFT); text.setColor(color); text.setTextSize(32); c.drawText(names[i],x+30,540,text);
            text.setTextSize(28); line(c, first[i], x+30, 595); line(c, second[i], x+30, 635);
        }
        long now=SystemClock.uptimeMillis(); float dt=Math.min(.05f,Math.max(0,(now-frame)/1000f));frame=now; boolean moving=false;
        text.setTextAlign(Paint.Align.CENTER); text.setTextSize(40);
        for(int i=0;i<2;i++) {
            focus[i].target=i==selected?1:0;if(reduced)focus[i].snap(focus[i].target);else moving|=focus[i].step(dt);
            float f=Math.max(0,Math.min(1,focus[i].value)),cy=800+i*100;
            if(f>.001f){rect.set(568.4444f,cy-30,1284,cy+30);buttons.drawFocused(c,rect,1,light,f);}
            text.setColor(io.tvos.settings.SettingsListRenderer.blend(color,0xFF1C1C1E,f));c.drawText(choices[i],960,cy+14,text);
        }
        c.restoreToCount(saved);if((exitStart>=0||moving||alpha<1)&&hasWindowFocus())postInvalidateOnAnimation();
    }
    private void line(Canvas canvas, String value, float x, float y) {
        text.setTextSize(28); float width = text.measureText(value);
        if (width > 380) text.setTextSize(28 * 380 / width);
        canvas.drawText(value, x, y, text);
    }
    @Override public void onWindowFocusChanged(boolean f){super.onWindowFocusChanged(f);pressed=false;if(f)invalidate();}
    @Override protected void onDetachedFromWindow(){background.clear();shadows.clear();super.onDetachedFromWindow();}
    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){int next=key==KeyEvent.KEYCODE_DPAD_UP?0:1;if(next!=selected){focus[selected].snap(0);selected=next;}describe();invalidate();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER){if(e.getRepeatCount()==0)pressed=true;return true;}return super.onKeyDown(key,e);
    }
    @Override public boolean onKeyUp(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)return true;
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER){boolean choose=pressed&&!e.isCanceled();pressed=false;if(choose)confirm();return true;}return super.onKeyUp(key,e);
    }
}
