package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import java.io.InputStream;

/** Small offline Apple emoji images; gradients are drawn separately at any size. */
final class ProfileAvatar {
    static final int COUNT = 18, PAGE_SIZE = 9;
    private static final String KEY = "profile_avatar_v2";
    private static final int[] LEGACY = {1, 3, 11, 4, 8, 0, 6};
    private static final String[] FILES = {"robot", "fox", "woman", "frog", "popcorn", "pig_face",
            "panda", "videocassette", "clapper_board", "monkey_face", "dog_face", "cat_face",
            "lion", "rabbit_face", "hot_dog", "man", "statue_of_liberty", "pile_of_poo"};
    private static final int[] TOP = {0xFFE5ECED, 0xFFF4CA85, 0xFFFFDEA0, 0xFFD4E887, 0xFFFFEB83, 0xFFF9CCD9,
            0xFFFFFFFF, 0xFF909493, 0xFF9B9D9E, 0xFFC6BA9B, 0xFFFFFFFF, 0xFFFFE989,
            0xFFF7D28B, 0xFFF4F2F4, 0xFFF8D59D, 0xFFFFDDA1, 0xFFBCE1D5, 0xFFCCA586};
    private static final int[] BOTTOM = {0xFF91A7AD, 0xFFC28035, 0xFFD3A467, 0xFF7B9F42, 0xFFEFD15B, 0xFFD98FA7,
            0xFFCCD0D1, 0xFF424644, 0xFF494B4D, 0xFF8C826B, 0xFFD5D1CA, 0xFFE7C34F,
            0xFFC28A3D, 0xFFCBC7CE, 0xFFD19456, 0xFFD3A064, 0xFF669F91, 0xFF8B5B3E};
    private static final int[] NAMES = {R.string.runtime_robot, R.string.runtime_fox, R.string.runtime_woman, R.string.runtime_frog, R.string.runtime_popcorn, R.string.runtime_pig, R.string.runtime_panda, R.string.runtime_videocassette, R.string.runtime_clapper_board, R.string.runtime_monkey, R.string.runtime_dog, R.string.runtime_cat, R.string.runtime_lion, R.string.runtime_rabbit, R.string.runtime_hot_dog, R.string.runtime_man, R.string.runtime_statue_of_liberty, R.string.runtime_poo};

    private final Context context;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF imageRect = new RectF(-0.76f, -0.76f, 0.76f, 0.76f);
    private final Bitmap[] images = new Bitmap[COUNT];
    private final Shader[] gradients = new Shader[COUNT];

    ProfileAvatar(Context context) { this.context = context.getApplicationContext(); }

    static int selected(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        int id = prefs.getInt(KEY, -1);
        if (!prefs.contains(KEY)) {
            int old = prefs.getInt("profile_avatar", -1);
            if (old >= 0 && old < LEGACY.length) id = LEGACY[old];
        }
        return id >= 0 && id < COUNT ? id : -1;
    }

    static void select(Context context, int id) {
        if (id < 0 || id >= COUNT) throw new IllegalArgumentException("Invalid avatar");
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt(KEY, id).apply();
    }

    static String name(int id) { return AppStrings.get(NAMES[id]); }

    void prepare(int page) {
        for (int i = 0; i < COUNT; i++) {
            if (i / PAGE_SIZE == page) load(i);
            else images[i] = null;
        }
    }

    void prepareTransition(int page) {
        for (int i = page * PAGE_SIZE; i < (page + 1) * PAGE_SIZE; i++) load(i);
    }

    private void load(int id) {
        if (images[id] != null) return;
        try (InputStream stream = context.getAssets().open("avatars/" + FILES[id] + ".webp")) {
            images[id] = BitmapFactory.decodeStream(stream);
        } catch (java.io.IOException e) { android.util.Log.w("ProfileAvatar", "Avatar image unavailable", e); }
        if (gradients[id] == null) gradients[id] = new LinearGradient(0, -1, 0, 1,
                TOP[id], BOTTOM[id], Shader.TileMode.CLAMP);
    }

    void clear() { for (int i = 0; i < COUNT; i++) images[i] = null; }

    void draw(Canvas c, int id, float x, float y, float radius) {
        draw(c, id, x, y, radius, 1);
    }

    void draw(Canvas c, int id, float x, float y, float radius, float emojiScale) {
        load(id);
        int saved = c.save();
        c.translate(x, y);
        c.scale(radius, radius);
        paint.setShader(gradients[id]);
        c.drawCircle(0, 0, 1, paint);
        paint.setShader(null);
        c.scale(emojiScale, emojiScale);
        if (images[id] != null) c.drawBitmap(images[id], null, imageRect, paint);
        c.restoreToCount(saved);
    }
}
