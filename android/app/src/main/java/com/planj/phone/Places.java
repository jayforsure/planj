package com.planj.phone;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in places. A rough location fix every quarter hour is matched to a place within
 * RADIUS metres. Only each place's centre is kept, in a private file on this phone that is
 * never synced; what gets recorded and synced is just "place 3 from 19:04".
 */
final class Places {
    private static final String PREFS = "planj_places";
    private static final double RADIUS = 150;        // metres: one place
    private static final float MAX_ACCURACY = 250;   // metres: a vaguer fix is ignored

    private Places() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File store(Context ctx) {
        return new File(ctx.getFilesDir(), "places.json");
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean("on", false);
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("on", on).apply();
    }

    static boolean hasForeground(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasBackground(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static int count(Context ctx) {
        return read(ctx).length();
    }

    /** Deletes every place and stops sampling. Recorded "place" events stay as bare numbers. */
    static void forget(Context ctx) {
        store(ctx).delete();
        prefs(ctx).edit().remove("last").apply();
    }

    private static JSONArray read(Context ctx) {
        try {
            return new JSONArray(new String(Files.readAllBytes(store(ctx).toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private static void write(Context ctx, JSONArray places) throws IOException {
        File tmp = new File(store(ctx).getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(places.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(store(ctx))) throw new IOException("could not save places");
    }

    /**
     * Takes one fix and returns the event line to record if the place changed, else null.
     * Blocks for up to 20 seconds, so call it off the main thread.
     */
    static String sample(Context ctx) {
        if (!enabled(ctx) || !hasForeground(ctx)) return null;
        Location loc = fix(ctx);
        if (loc == null || !loc.hasAccuracy() || loc.getAccuracy() > MAX_ACCURACY) return null;
        String place;
        try {
            place = match(ctx, loc);
        } catch (IOException | JSONException e) {
            return null;
        }
        if (place.equals(prefs(ctx).getString("last", null))) return null;
        prefs(ctx).edit().putString("last", place).apply();
        try {
            return new JSONObject().put("t", java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString())
                    .put("event", "place").put("app", place).toString();
        } catch (JSONException e) {
            return null;
        }
    }

    @SuppressWarnings("MissingPermission") // checked in sample()
    private static Location fix(Context ctx) {
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        String provider = Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)
                ? LocationManager.FUSED_PROVIDER : LocationManager.NETWORK_PROVIDER;
        if (!lm.isProviderEnabled(provider)) provider = LocationManager.GPS_PROVIDER;
        if (!lm.isProviderEnabled(provider)) return null;
        if (Build.VERSION.SDK_INT >= 30) {
            Location[] got = new Location[1];
            CountDownLatch done = new CountDownLatch(1);
            CancellationSignal cancel = new CancellationSignal();
            lm.getCurrentLocation(provider, cancel, ctx.getMainExecutor(), l -> {
                got[0] = l;
                done.countDown();
            });
            try {
                if (!done.await(20, TimeUnit.SECONDS)) cancel.cancel();
            } catch (InterruptedException e) {
                cancel.cancel();
            }
            if (got[0] != null) return got[0];
        }
        Location last = lm.getLastKnownLocation(provider);
        // An old fix says where the phone was, not where it is.
        return last != null && System.currentTimeMillis() - last.getTime() < 20 * 60_000L ? last : null;
    }

    /** The nearest known place within RADIUS, nudged toward this fix; else a new place. */
    private static String match(Context ctx, Location loc) throws IOException, JSONException {
        JSONArray places = read(ctx);
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        float[] d = new float[1];
        for (int i = 0; i < places.length(); i++) {
            JSONObject p = places.getJSONObject(i);
            Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), p.getDouble("lat"), p.getDouble("lon"), d);
            if (d[0] < bestDist) {
                bestDist = d[0];
                best = i;
            }
        }
        if (best >= 0 && bestDist <= RADIUS) {
            JSONObject p = places.getJSONObject(best);
            int n = Math.min(p.optInt("n", 1), 50); // a running mean that keeps adapting slightly
            p.put("lat", (p.getDouble("lat") * n + loc.getLatitude()) / (n + 1));
            p.put("lon", (p.getDouble("lon") * n + loc.getLongitude()) / (n + 1));
            p.put("n", n + 1);
            write(ctx, places);
            return p.getString("id");
        }
        int next = places.length() + 1;
        for (int i = 0; i < places.length(); i++) {
            next = Math.max(next, Integer.parseInt(places.getJSONObject(i).getString("id").substring(1)) + 1);
        }
        JSONObject p = new JSONObject().put("id", "p" + next).put("lat", loc.getLatitude()).put("lon", loc.getLongitude()).put("n", 1);
        places.put(p);
        write(ctx, places);
        return p.getString("id");
    }

    /** "p3" -> "Place 3" */
    static String label(String id) {
        return id != null && id.startsWith("p") ? "Place " + id.substring(1) : "Place";
    }

    static List<String> permissionsToAsk() {
        return List.of(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
    }
}
