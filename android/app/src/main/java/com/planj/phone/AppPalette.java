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

    static String label(Context ctx, String pkg) {
        if (pkg.equals(UsageCollector.PRIVATE_APP)) return "Private";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException e) {
            String[] parts = pkg.split("\\.");
            String last = parts[parts.length - 1];
            return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
        }
    }

    static Drawable icon(Context ctx, String pkg) {
        if (pkg.equals(UsageCollector.PRIVATE_APP)) return ctx.getDrawable(R.drawable.ic_private);
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
