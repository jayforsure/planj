package com.planj.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.net.Uri;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** The person's photo (kept only on this phone) and display name, shown as a circle. */
final class Avatar {
    private static final String PREFS = "planj_profile";

    private Avatar() {}

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "avatar.jpg");
    }

    static String name(Context ctx) {
        String n = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", null);
        if (n != null && !n.isEmpty()) return n;
        String e = AccountStore.email(ctx);
        return e == null ? "You" : e.substring(0, e.indexOf('@') < 0 ? e.length() : e.indexOf('@'));
    }

    static void setName(Context ctx, String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", name.trim()).apply();
    }

    /** Stores a picked image as a 512px square JPEG. Returns false when it could not be read. */
    static boolean save(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null) return false;
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap small = Bitmap.createScaledBitmap(square, 512, 512, true);
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                small.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Fills the circle with the photo, or the initial on the accent colour when there is none. */
    static void show(Context ctx, ImageView image, TextView initial) {
        circle(image);
        File f = file(ctx);
        Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (b != null) {
            image.setImageBitmap(b);
            image.setVisibility(View.VISIBLE);
            if (initial != null) initial.setVisibility(View.GONE);
        } else {
            image.setVisibility(View.GONE);
            if (initial != null) {
                initial.setVisibility(View.VISIBLE);
                String n = name(ctx);
                initial.setText(n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase());
            }
        }
    }

    static void circle(View v) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
    }
}
