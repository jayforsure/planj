package com.planj.phone;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;

/** One stable colour, name and icon per app, so charts read the same way every day. */
final class AppPalette {
    private static final Map<String, Integer> BRAND = new LinkedHashMap<>();
    // Distinct, warm-compatible fallbacks for apps without a known brand colour.
    private static final int[] FALLBACK = {
            0xFF8AB4F8, 0xFFF28B82, 0xFF81C995, 0xFFFDD663, 0xFFC58AF9,
            0xFF78D9EC, 0xFFFFA26B, 0xFFA8DAB5, 0xFFE6A1C9, 0xFFB5BDA8,
    };

    static {
        BRAND.put("instagram", 0xFFE1306C);
        BRAND.put("whatsapp", 0xFF25D366);
        BRAND.put("youtube", 0xFFFF3D3D);
        BRAND.put("xingin", 0xFFFF2442);
        BRAND.put("shopee", 0xFFEE4D2D);
        BRAND.put("pinduoduo", 0xFFE02E24);
        BRAND.put("tradingview", 0xFF2962FF);
        BRAND.put("linkedin", 0xFF0A66C2);
        BRAND.put("twitter", 0xFF8899A6);
        BRAND.put("telegram", 0xFF26A5E4);
        BRAND.put("chrome", 0xFF4285F4);
        BRAND.put("spotify", 0xFF1DB954);
        BRAND.put("tiktok", 0xFF69C9D0);
        BRAND.put("discord", 0xFF5865F2);
        BRAND.put("gmail", 0xFFEA4335);
        BRAND.put("maps", 0xFF34A853);
        BRAND.put("netflix", 0xFFE50914);
        BRAND.put("reddit", 0xFFFF4500);
        BRAND.put("snapchat", 0xFFFFFC00);
        BRAND.put("facebook", 0xFF1877F2);
        BRAND.put("messenger", 0xFF0084FF);
        BRAND.put("planj", 0xFF2EC4B6);
    }

    private AppPalette() {}

    static boolean isSystemShell(String pkg) {
        return pkg.contains("launcher") || pkg.contains("systemui");
    }

    static int color(String pkg) {
        if (pkg.equals(UsageCollector.PRIVATE_APP)) return 0xFF5A544E;
        String low = pkg.toLowerCase();
        for (Map.Entry<String, Integer> e : BRAND.entrySet()) {
            if (low.contains(e.getKey())) return e.getValue();
        }
        return FALLBACK[Math.floorMod(low.hashCode(), FALLBACK.length)];
    }

    /**
     * The app's own name. It is kept the first time it is read, so an app you later uninstall
     * is still listed by its name, not "com.example.app".
     */
    static String label(Context ctx, String pkg) {
        if (pkg.equals(UsageCollector.PRIVATE_APP)) return "Private";
        android.content.SharedPreferences kept = ctx.getSharedPreferences("planj_app_names", Context.MODE_PRIVATE);
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            String name = String.valueOf(pm.getApplicationLabel(info));
            if (!name.equals(kept.getString(pkg, null))) kept.edit().putString(pkg, name).apply();
            return name;
        } catch (PackageManager.NameNotFoundException e) {
            String name = kept.getString(pkg, null);
            if (name != null) return name;
            String[] parts = pkg.split("\\.");
            String last = parts[parts.length - 1];
            return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
        }
    }

    /** The app's own icon; a copy is kept the first time, for apps uninstalled later. */
    static Drawable icon(Context ctx, String pkg) {
        if (pkg.equals(UsageCollector.PRIVATE_APP)) return ctx.getDrawable(R.drawable.ic_private);
        java.io.File kept = new java.io.File(new java.io.File(ctx.getFilesDir(), "appicons"), pkg.replaceAll("[^A-Za-z0-9._]", "_") + ".png");
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            if (!kept.exists()) keep(d, kept);
            return d;
        } catch (PackageManager.NameNotFoundException e) {
            if (!kept.exists()) return null;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(kept.getPath());
            return b == null ? null : new android.graphics.drawable.BitmapDrawable(ctx.getResources(), b);
        }
    }

    private static void keep(Drawable d, java.io.File out) {
        try {
            java.io.File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, 144, 144);
            d.draw(c);
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo);
            }
        } catch (Exception ignored) {
            // kept next time
        }
    }
}
