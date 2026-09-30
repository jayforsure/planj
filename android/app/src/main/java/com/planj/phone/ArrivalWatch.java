package com.planj.phone;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Catches arrivals when they happen instead of at the next quarter-hour sample:
 * joining a Wi-Fi network (with a location fix taken right then), Android's own alerts for
 * entering or leaving a marked place, and any location fix another app already asked for.
 * The quarter-hour samples keep running underneath as the fallback.
 */
public final class ArrivalWatch extends BroadcastReceiver {
    private static final String ACTION_WIFI = "com.planj.phone.ARRIVAL_WIFI";
    private static final String ACTION_NEAR = "com.planj.phone.ARRIVAL_NEAR";
    private static final String ACTION_FIX = "com.planj.phone.ARRIVAL_FIX";
    private static final String EXTRA_PLACE = "place";
    private static final String PREFS = "planj_arrival";

    private static PendingIntent pending(Context ctx, String action, int code, String place) {
        Intent i = new Intent(ctx, ArrivalWatch.class).setAction(action);
        if (place != null) i.putExtra(EXTRA_PLACE, place);
        // mutable: the system adds the fix, the network or entering/leaving to the intent
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    /**
     * Arms every watch, or clears them when places are off. Cheap and safe to call often.
     * The Wi-Fi watch is one-shot (Android fires it once, then drops it), so it is armed only
     * while the phone is off Wi-Fi: it then fires exactly on the next join, which is an arrival.
     * Whatever notices the phone left Wi-Fi (the quarter-hour job, a proximity or passive fix)
     * arms it again.
     */
    @SuppressWarnings("MissingPermission") // checked through Places.hasForeground/hasBackground
    static synchronized void arm(Context ctx) {
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        PendingIntent wifi = pending(ctx, ACTION_WIFI, 1, null), fix = pending(ctx, ACTION_FIX, 2, null);
        try {
            cm.unregisterNetworkCallback(wifi);
        } catch (RuntimeException ignored) {
            // was not registered
        }
        lm.removeUpdates(fix);
        for (String id : prefs.getStringSet("near", new HashSet<>())) lm.removeProximityAlert(pending(ctx, ACTION_NEAR, code(id), id));

        prefs.edit().putBoolean("wifi", false).apply();
        Set<String> armed = new HashSet<>();
        if (Places.enabled(ctx) && Places.hasForeground(ctx) && Places.hasBackground(ctx)) {
            armWifi(ctx);
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 30_000L, 25f, fix);
            List<Places.Place> places = Places.list(ctx);
            for (Places.Place p : places) {
                if (!p.marked()) continue;
                lm.addProximityAlert(p.lat, p.lon, (float) Places.radius(), -1, pending(ctx, ACTION_NEAR, code(p.id), p.id));
                armed.add(p.id);
            }
        }
        prefs.edit().putStringSet("near", armed).apply();
    }

    /** Arms the one-shot Wi-Fi watch if the phone is off Wi-Fi and it is not armed already. */
    static synchronized void armWifi(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("wifi", false) || UsageCollector.networkNow(ctx).startsWith("wifi:")) return;
        if (!Places.enabled(ctx) || !Places.hasForeground(ctx) || !Places.hasBackground(ctx)) return;
        ctx.getSystemService(ConnectivityManager.class).registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), pending(ctx, ACTION_WIFI, 1, null));
        prefs.edit().putBoolean("wifi", true).apply();
    }

    private static int code(String placeId) {
        return 1000 + Math.abs(placeId.hashCode() % 100_000);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                handle(ctx, intent);
            } catch (Exception e) {
                android.util.Log.w("planj", "arrival", e);
            } finally {
                result.finish();
            }
        }).start();
    }

    private static void handle(Context ctx, Intent intent) throws Exception {
        String action = intent.getAction();
        if (action == null || !Places.enabled(ctx)) return;
        long now = System.currentTimeMillis();
        String line = null;
        switch (action) {
            case ACTION_NEAR: {
                String place = intent.getStringExtra(EXTRA_PLACE);
                if (intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) {
                    line = Places.change(ctx, place, now); // inside a marked place: that's where we are
                } else {
                    line = Places.sample(ctx); // left it: find out where we are now
                }
                break;
            }
            case ACTION_WIFI:
                // fired once and dropped by Android; armed again after the phone leaves Wi-Fi
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("wifi", false).apply();
                // joined a Wi-Fi network: note the network now, and where we are, at this moment
                UsageCollector.collect(ctx);
                line = Places.sample(ctx);
                break;
            case ACTION_FIX: {
                Location loc = intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED);
                if (loc == null && Build.VERSION.SDK_INT >= 31) {
                    java.util.ArrayList<Location> many = intent.getParcelableArrayListExtra(LocationManager.KEY_LOCATIONS);
                    if (many != null && !many.isEmpty()) loc = many.get(many.size() - 1);
                }
                if (loc != null && now - loc.getTime() < 5 * 60_000L) line = Places.fromLocation(ctx, loc);
                break;
            }
            default:
                return;
        }
        if (line != null) UsageCollector.appendLines(ctx, List.of(line));
        if (!ACTION_WIFI.equals(action)) armWifi(ctx); // e.g. just left home: watch for the next Wi-Fi
    }
}
