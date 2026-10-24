package io.tvos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.List;

/** Lightweight profile landing page. The home activity pauses while this page is visible. */
public final class ProfileActivity extends Activity {
    private ProfileView profile;

    static void open(Activity activity, int[] frost) {
        Intent intent = new Intent(activity, ProfileActivity.class);
        // Only the already blurred 192x108 pixels cross the activity boundary, never a full screenshot.
        if (frost != null) intent.putExtra("profile_frost", frost);
        activity.startActivity(intent);
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Watchdog.install(this);
        Ui.init(this);
        int[] frost = getIntent().getIntArrayExtra("profile_frost");
        getIntent().removeExtra("profile_frost");
        profile = new ProfileView(frost, state);
        setContentView(profile);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() { super.onResume(); profile.resume(); }
    @Override protected void onPause() { profile.pause(); super.onPause(); }
    @Override protected void onDestroy() { profile.release(); super.onDestroy(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        profile.save(state);
        super.onSaveInstanceState(state);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private final class ProfileView extends View implements Choreographer.FrameCallback {
        private final Scene scene = new Scene();
        private final Shadows shadows = new Shadows();
        private final ActionSurface surface = new ActionSurface(scene, shadows);
        private final ProfileAvatar avatars = new ProfileAvatar(ProfileActivity.this);
        private final Spring[] buttons = {new Spring(0.26f, 0.85f, 1), new Spring(0.26f, 0.85f, 0)};
        private final Spring[] avatarFocus = new Spring[ProfileAvatar.COUNT];
        private final Spring chooser = new Spring(0.38f, 1f, 0);
        private final Spring entrance = new Spring(0.55f, 1f, 0);
        private final RectF[] buttonRects = {new RectF(270, 650, 830, 722), new RectF(270, 755, 830, 827)};
        private final RectF scratch = new RectF();
        private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint buttonText = Ui.text(Ui.displayMedium, 28, 0xFFFFFFFF);
        private final TextPaint heading = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Spoken spoken;
        private StaticLayout headingLayout, bodyLayout;
        private Bitmap background;
        private Click click;
        private int selected, focus, avatarIndex, page, previousPage;
        private final Spring pageTransition = new Spring(0.24f, 1f, 1);
        private boolean choosing, running, scheduled, okDown, backDown, night;
        private long lastFrame;
        private float k = 1, ox, oy;
        // Slot zero is the current avatar; satellites match the reference composition.
        private final float[] xs = {1410, 1179, 1618, 1410, 1632, 1460, 1196, 1129, 1684};
        private final float[] ys = {515, 296, 302, 230, 681, 815, 764, 527, 500};
        private final float[] radii = {170, 98, 78, 65, 57, 84, 108, 61, 54};

        ProfileView(int[] frost, Bundle state) {
            super(ProfileActivity.this);
            selected = Math.max(0, ProfileAvatar.selected(getContext()));
            avatarIndex = selected;
            if (state != null) {
                choosing = state.getBoolean("choosing", false);
                avatarIndex = Math.max(0, Math.min(ProfileAvatar.COUNT - 1, state.getInt("avatar", selected)));
                focus = Math.max(0, Math.min(1, state.getInt("focus", 0)));
            }
            page = previousPage = avatarIndex / ProfileAvatar.PAGE_SIZE;
            avatars.prepare(page);
            for (int i = 0; i < avatarFocus.length; i++) avatarFocus[i] = new Spring(0.26f, 0.85f, 0);
            scene.setSize(1920, 1080);
            scene.setMotion(0, 0.2f);
            night = HomeData.get(getContext()).isNight(System.currentTimeMillis());
            makeBackground(frost);
            scene.applyPalette();
            heading.setTypeface(Ui.displaySemibold);
            heading.setTextSize(64);
            body.setTypeface(Ui.displayRegular);
            body.setTextSize(31);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(4);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setContentDescription(Ui.ru ? "Профиль tvOS" : "tvOS profile");
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    if (choosing) {
                        for (int i = page * 9 + 1; i <= page * 9 + 9; i++) out.add(i);
                        out.add(19); out.add(20);
                    } else { out.add(1); out.add(2); }
                }
                @Override public String text(int id) {
                    if (choosing && id > 18) return id == 19 ? (Ui.ru ? "Страница 1" : "Page 1") : (Ui.ru ? "Страница 2" : "Page 2");
                    if (choosing) return ProfileAvatar.name(id - 1) + (id - 1 == selected
                            ? (Ui.ru ? ", выбран" : ", selected") : "");
                    return buttonLabel(id - 1);
                }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > (choosing ? 20 : 2)) return false;
                    if (choosing && id <= 18 && (id - 1) / 9 != page) return false;
                    if (choosing && id > 18) scratch.set(1340 + (id - 19) * 80, 905, 1420 + (id - 19) * 80, 985);
                    else
                    if (choosing) avatarRect(id - 1, scratch); else scratch.set(buttonRects[id - 1]);
                    out.set(Math.round(ox + scratch.left * k), Math.round(oy + scratch.top * k),
                            Math.round(ox + scratch.right * k), Math.round(oy + scratch.bottom * k));
                    return true;
                }
                @Override public int focused() { return (choosing ? avatarIndex : focus) + 1; }
                @Override public void focus(int id) {
                    if (id < 1 || id > (choosing ? 20 : 2)) return;
                    if (choosing && id > 18) { changePage(id - 19); return; }
                    if (choosing) { changePage((id - 1) / 9); avatarIndex = id - 1; } else focus = id - 1;
                    retarget(false); wake();
                }
                @Override public void click(int id) { focus(id); if (!choosing || id <= 18) activate(); }
            });
            updateText();
            retarget(true);
        }

        private void makeBackground(int[] frost) {
            background = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(background);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (frost != null && frost.length == Backdrop.FW * Backdrop.FH) {
                background.setPixels(frost, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
                c.drawColor(night ? 0x3512252D : 0x24FFFFFF);
            } else {
                p.setShader(new LinearGradient(0, 0, 192, 108, night ? 0xFF193940 : 0xFFC8E5EB,
                        night ? 0xFF17252C : 0xFFE8E4E9, Shader.TileMode.CLAMP));
                c.drawPaint(p);
            }
            p.setShader(new RadialGradient(100, 115, 90,
                    night ? 0x604D272D : 0x60DCA9B0, 0, Shader.TileMode.CLAMP));
            c.drawPaint(p);
            scene.setFrost(pixels(background));
            Bitmap glass = Bitmap.createScaledBitmap(background, Backdrop.GW, Backdrop.GH, true);
            scene.setGlassImage(pixels(glass));
            glass.recycle();
        }

        private int[] pixels(Bitmap bitmap) {
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            return pixels;
        }

        private String buttonLabel(int index) {
            return index == 0 ? (Ui.ru ? "Выбрать аватар" : "Choose avatar")
                    : (Ui.ru ? "На главный экран" : "Back to Home");
        }

        private void updateText() {
            String title = choosing ? (Ui.ru ? "Выберите\nсвой аватар" : "Choose\nyour avatar")
                    : (Ui.ru ? "Ваш профиль\nв tvOS" : "Your profile\non tvOS");
            String description = choosing
                    ? (Ui.ru ? "Выберите значок стрелками и нажмите ОК."
                            : "Use the arrows to choose, then press OK.")
                    : (Ui.ru ? "Добавьте немного индивидуальности.\nВаш аватар появится рядом с часами\nна главном экране."
                            : "Make yourself at home.\nYour avatar will appear beside the clock\non the Home screen.");
            headingLayout = StaticLayout.Builder.obtain(title, 0, title.length(), heading, 760)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
            bodyLayout = StaticLayout.Builder.obtain(description, 0, description.length(), body, 800)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(5, 1).build();
        }

        void save(Bundle state) {
            state.putBoolean("choosing", choosing);
            state.putInt("avatar", avatarIndex);
            state.putInt("focus", focus);
        }

        void resume() {
            if (running) return;
            running = true;
            click = new Click(getContext());
            lastFrame = 0;
            wake();
            requestFocus();
            spoken.readAgain();
        }

        void pause() {
            running = false;
            okDown = backDown = false;
            Choreographer.getInstance().removeFrameCallback(this);
            scheduled = false;
            shadows.clear();
            if (click != null) { click.release(); click = null; }
        }

        void release() { pause(); avatars.clear(); background = null; headingLayout = bodyLayout = null; }

        private void wake() {
            if (!running || scheduled) return;
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public void doFrame(long now) {
            scheduled = false;
            if (!running) return;
            float dt = lastFrame == 0 ? 1f / 60 : Math.min(0.05f, (now - lastFrame) / 1e9f);
            lastFrame = now;
            boolean moving = entrance.step(dt) | chooser.step(dt) | pageTransition.step(dt);
            for (Spring spring : buttons) moving |= spring.step(dt);
            for (Spring spring : avatarFocus) moving |= spring.step(dt);
            invalidate();
            if (moving) wake(); else { lastFrame = 0; avatars.prepare(page); }
        }

        private void retarget(boolean snap) {
            boolean reduced = getContext().getSharedPreferences("settings", MODE_PRIVATE).getBoolean("reduce_motion", false);
            chooser.target = choosing ? 1 : 0;
            entrance.target = 1;
            for (int i = 0; i < buttons.length; i++) buttons[i].target = !choosing && i == focus ? 1 : 0;
            for (int i = 0; i < avatarFocus.length; i++) avatarFocus[i].target = choosing && i == avatarIndex ? 1 : 0;
            if (snap || reduced) {
                pageTransition.snap(1);
                chooser.snap(chooser.target);
                for (Spring spring : buttons) spring.snap(spring.target);
                for (Spring spring : avatarFocus) spring.snap(spring.target);
            }
            if (reduced) entrance.snap(1);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            k = Math.min(w / 1920f, h / 1080f);
            ox = (w - 1920 * k) / 2;
            oy = (h - 1080 * k) / 2;
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(night ? 0xFF17252C : 0xFFE4EEF0);
            int save = c.save();
            c.translate(ox, oy);
            c.scale(k, k);
            scratch.set(0, 0, 1920, 1080);
            c.drawBitmap(background, null, scratch, photo);
            int ink = night ? 0xFFF5F5F7 : 0xFF1D1D1F;
            heading.setColor(ink);
            body.setColor(Ui.alpha(ink, 0.72f));
            int textSave = c.save();
            c.translate(170, 290);
            headingLayout.draw(c);
            c.restoreToCount(textSave);
            textSave = c.save();
            c.translate(150, 475);
            bodyLayout.draw(c);
            c.restoreToCount(textSave);
            if (!choosing) for (int i = 0; i < buttons.length; i++) drawButton(c, i);
            float intro = Ui.clamp(entrance.value);
            int bubbles = c.save();
            c.scale(0.9f + 0.1f * intro, 0.9f + 0.1f * intro, 1410, 530);
            if (previousPage != page && pageTransition.value < 0.999f) {
                int layer = c.saveLayerAlpha(1000, 100, 1880, 920, Math.round(255 * (1 - Ui.clamp(pageTransition.value))));
                drawPage(c, previousPage);
                c.restoreToCount(layer);
            }
            if (pageTransition.value < 0.999f) {
                int layer = c.saveLayerAlpha(1000, 100, 1880, 920, Math.round(255 * Ui.clamp(pageTransition.value)));
                drawPage(c, page);
                c.restoreToCount(layer);
            } else drawPage(c, page);
            c.restoreToCount(bubbles);
            if (choosing) {
                for (int i = 0; i < 2; i++) {
                    rim.setStyle(Paint.Style.FILL);
                    rim.setColor(Ui.alpha(ink, i == page ? 0.85f : 0.25f));
                    c.drawCircle(1380 + i * 80, 945, i == page ? 7 : 5, rim);
                }
                rim.setStyle(Paint.Style.STROKE);
            }
            c.restoreToCount(save);
        }

        private void drawButton(Canvas c, int index) {
            RectF rect = buttonRects[index];
            float f = Ui.clamp(buttons[index].value);
            float scale = surface.draw(c, rect, f, night ? 1 : 0, 1);
            buttonText.setColor(ActionSurface.ink(night ? 1 : 0, f));
            String text = buttonLabel(index);
            int saved = c.save();
            c.scale(scale, scale, rect.centerX(), rect.centerY());
            c.drawText(text, rect.centerX() - buttonText.measureText(text) / 2,
                    rect.centerY() + Ui.halfCap(buttonText), buttonText);
            c.restoreToCount(saved);
        }

        private void avatarRect(int index, RectF out) {
            int local = index % 9;
            int center = selected / 9 == index / 9 ? selected % 9 : 0;
            int slot = local == center ? 0 : local < center ? local + 1 : local;
            float t = Ui.clamp(chooser.value);
            float x = xs[slot] + (1180 + local % 3 * 230 - xs[slot]) * t;
            float y = ys[slot] + (320 + local / 3 * 230 - ys[slot]) * t;
            float radius = radii[slot] + (82 - radii[slot]) * t;
            radius *= 1 + 0.1f * Ui.clamp(avatarFocus[index].value);
            out.set(x - radius, y - radius, x + radius, y + radius);
        }

        private void drawPage(Canvas c, int pageIndex) {
            int center = selected / 9 == pageIndex ? selected : pageIndex * 9;
            for (int i = pageIndex * 9; i < pageIndex * 9 + 9; i++) if (i != center) drawAvatar(c, i);
            drawAvatar(c, center);
        }

        private void changePage(int next) {
            if (next < 0 || next > 1 || page == next) return;
            previousPage = page;
            page = next;
            avatarIndex = page * 9 + avatarIndex % 9;
            avatars.prepareTransition(page);
            pageTransition.snap(0);
            pageTransition.target = 1;
            retarget(false); wake(); spoken.readAgain();
        }

        private void drawAvatar(Canvas c, int index) {
            avatarRect(index, scratch);
            float x = scratch.centerX(), y = scratch.centerY(), radius = scratch.width() / 2;
            avatars.draw(c, index, x, y, radius);
            float focus = Ui.clamp(avatarFocus[index].value);
            if (focus > 0.01f) {
                rim.setColor(Ui.alpha(night ? 0xFFFFFFFF : 0xFF304D5A, focus));
                c.drawCircle(x, y, radius + 9, rim);
            }
        }

        private void activate() {
            if (click != null) click.open();
            if (choosing) {
                selected = avatarIndex;
                ProfileAvatar.select(getContext(), selected);
                choosing = false;
            } else if (focus == 0) {
                choosing = true;
                avatarIndex = selected;
            } else { finish(); return; }
            updateText(); retarget(false); wake(); spoken.readAgain();
        }

        private void back() {
            if (!choosing) { finish(); return; }
            choosing = false;
            changePage(selected / 9);
            updateText(); retarget(false); wake(); spoken.readAgain();
        }

        private void move(int dx, int dy) {
            int before = choosing ? avatarIndex : focus;
            if (choosing) {
                int local = avatarIndex % 9;
                if (dy != 0) {
                    int row = local / 3 + dy;
                    if (row >= 0 && row < 3) avatarIndex = page * 9 + row * 3 + local % 3;
                } else {
                    int column = local % 3 + dx;
                    if (column < 0 || column > 2) {
                        int next = page + dx;
                        if (next >= 0 && next < 2) {
                            changePage(next);
                            avatarIndex = page * 9 + local / 3 * 3 + (dx > 0 ? 0 : 2);
                        }
                    } else avatarIndex += dx;
                }
            } else if (dy != 0) focus = Math.max(0, Math.min(1, focus + dy));
            if (before == (choosing ? avatarIndex : focus)) return;
            if (click != null) click.move();
            retarget(false); wake(); spoken.focusMoved();
        }

        @Override public boolean onKeyDown(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) { move(0, -1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { move(0, 1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { move(-1, 0); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { move(1, 0); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getRepeatCount() == 0) okDown = true;
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { backDown = true; return true; }
            return super.onKeyDown(code, event);
        }

        @Override public boolean onKeyUp(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (okDown && !event.isCanceled()) activate();
                okDown = false; return true;
            }
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (backDown && !event.isCanceled()) back();
                backDown = false; return true;
            }
            return super.onKeyUp(code, event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (event.getAction() != MotionEvent.ACTION_UP) return super.onTouchEvent(event);
            float x = (event.getX() - ox) / k, y = (event.getY() - oy) / k;
            if (choosing && y >= 905 && y <= 985 && x >= 1340 && x <= 1500) {
                changePage(x < 1420 ? 0 : 1); performClick(); return true;
            }
            for (int i = choosing ? page * 9 : 0; i < (choosing ? page * 9 + 9 : 2); i++) {
                if (choosing) avatarRect(i, scratch); else scratch.set(buttonRects[i]);
                if (!scratch.contains(x, y)) continue;
                if (choosing) avatarIndex = i; else focus = i;
                performClick(); activate(); return true;
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return spoken; }
    }
}
