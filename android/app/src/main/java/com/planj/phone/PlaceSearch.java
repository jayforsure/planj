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

    /** Up to six places matching the text, nearest to (biasLat, biasLon) first. */
    static List<Result> search(String query, double biasLat, double biasLon) throws IOException {
        String url = BASE + "/api/?limit=6&q=" + URLEncoder.encode(query, "UTF-8")
                + String.format(Locale.ROOT, "&lat=%.4f&lon=%.4f", biasLat, biasLon);
        return parse(get(url));
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
