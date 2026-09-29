package com.planj.phone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Place names from OpenStreetMap via Photon (photon.komoot.io): search as you type, and the
 * name of whatever is under the pin. Only the typed text and a map position are sent; saved
 * places never leave the phone. Every call blocks, so run them off the main thread.
 */
final class PlaceSearch {
    private static final String BASE = "https://photon.komoot.io";
    private static final String AGENT = "planj/" + BuildInfo.VERSION + " (personal place picker)";

    static final class Result {
        final String name, address;
        final double lat, lon;

        Result(String name, String address, double lat, double lon) {
            this.name = name;
            this.address = address;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private PlaceSearch() {}

    static final double REGION = 2.3; // degrees either side, roughly 250 km: "near me", not the world

    /**
     * Up to eight places matching the text within about 250 km. Two sources, merged:
     * OpenStreetMap (Photon) and the phone's own geocoder, which on most Android phones is
     * backed by Google's data and knows many more local businesses.
     */
    static List<Result> search(android.content.Context ctx, String query, double lat, double lon) throws IOException {
        List<Result> out = new ArrayList<>();
        IOException failure = null;
        try {
            out.addAll(photon(query, lat, lon));
        } catch (IOException e) {
            failure = e;
        }
        for (Result g : geocoder(ctx, query, lat, lon)) {
            boolean dup = false;
            for (Result r : out) {
                float[] d = new float[1];
                android.location.Location.distanceBetween(r.lat, r.lon, g.lat, g.lon, d);
                if (d[0] < 60) dup = true;
            }
            if (!dup) out.add(g);
        }
        if (out.isEmpty() && failure != null) throw failure;
        out.sort((a, b) -> Double.compare(dist(a, lat, lon), dist(b, lat, lon)));
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    private static double dist(Result r, double lat, double lon) {
        float[] d = new float[1];
        android.location.Location.distanceBetween(r.lat, r.lon, lat, lon, d);
        return d[0];
    }

    private static List<Result> photon(String query, double lat, double lon) throws IOException {
        String url = BASE + "/api/?limit=8&q=" + URLEncoder.encode(query, "UTF-8")
                + String.format(Locale.ROOT, "&lat=%.4f&lon=%.4f&bbox=%.4f,%.4f,%.4f,%.4f",
                lat, lon, lon - REGION, lat - REGION, lon + REGION, lat + REGION);
        return parse(get(url));
    }

    @SuppressWarnings("deprecation") // the listener form needs API 33; callers are on a worker thread
    private static List<Result> geocoder(android.content.Context ctx, String query, double lat, double lon) {
        List<Result> out = new ArrayList<>();
        if (!android.location.Geocoder.isPresent()) return out;
        try {
            List<android.location.Address> found = new android.location.Geocoder(ctx, Locale.getDefault())
                    .getFromLocationName(query, 6, lat - REGION, lon - REGION, lat + REGION, lon + REGION);
            if (found == null) return out;
            for (android.location.Address a : found) {
                if (!a.hasLatitude() || !a.hasLongitude()) continue;
                String line = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : "";
                String name = a.getFeatureName();
                // A bare house number is not a name; use the start of the address instead.
                if (name == null || name.matches("[0-9A-Za-z/-]{1,6}")) name = line.contains(",") ? line.substring(0, line.indexOf(',')) : line;
                if (line.startsWith(name + ", ")) line = line.substring(name.length() + 2);
                out.add(new Result(name, line, a.getLatitude(), a.getLongitude()));
            }
        } catch (IOException | IllegalArgumentException e) {
            // no network or no geocoder backend: OpenStreetMap results stand alone
        }
        return out;
    }

    /** What is at this spot: a building or shop name if there is one, else the street. */
    static Result reverse(double lat, double lon) throws IOException {
        List<Result> r = parse(get(BASE + String.format(Locale.ROOT, "/reverse?lat=%.6f&lon=%.6f&limit=1", lat, lon)));
        return r.isEmpty() ? null : new Result(r.get(0).name, r.get(0).address, lat, lon);
    }

    private static List<Result> parse(String body) throws IOException {
        List<Result> out = new ArrayList<>();
        try {
            JSONArray features = new JSONObject(body).getJSONArray("features");
            for (int i = 0; i < features.length(); i++) {
                JSONObject f = features.getJSONObject(i);
                JSONObject p = f.getJSONObject("properties");
                JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
                String street = join(p.optString("housenumber"), p.optString("street"));
                String name = p.optString("name");
                if (name.isEmpty()) name = street.isEmpty() ? p.optString("city", "Dropped pin") : street;
                Set<String> parts = new LinkedHashSet<>();
                for (String s : new String[]{street, p.optString("district"), p.optString("city"), p.optString("state")}) {
                    if (!s.isEmpty() && !s.equals(name)) parts.add(s);
                }
                out.add(new Result(name, String.join(", ", parts), c.getDouble(1), c.getDouble(0)));
            }
        } catch (JSONException e) {
            throw new IOException("unexpected answer from the place search");
        }
        return out;
    }

    private static String join(String number, String street) {
        return number.isEmpty() ? street : street.isEmpty() ? number : number + " " + street;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", AGENT);
            conn.setRequestProperty("Accept-Language", Locale.getDefault().getLanguage());
            if (conn.getResponseCode() / 100 != 2) throw new IOException("place search answered " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }
}
