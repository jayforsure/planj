package com.planj.phone;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The PC's own summary of each day (present time by category), as it last sent it. */
final class PcDays {
    static final class Seg {
        final int start, end; // minutes since local midnight
        final String cat;     // focus | entertainment | social | chat | other

        Seg(int start, int end, String cat) {
            this.start = start;
            this.end = end;
            this.cat = cat;
        }
    }

    static final class App {
        final String name, cat;
        final int minutes;

        App(String name, int minutes, String cat) {
            this.name = name;
            this.minutes = minutes;
            this.cat = cat;
        }
    }

    private PcDays() {}

    /** PC apps and recognised sites that day, most used first. Null when the PC hasn't reported. */
    static List<App> apps(Context ctx, LocalDate day) {
        File f = new File(dir(ctx), day + ".json");
        if (!f.exists()) return null;
        List<App> out = new ArrayList<>();
        try {
            JSONArray apps = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optJSONArray("apps");
            if (apps == null) return out;
            for (int i = 0; i < apps.length(); i++) {
                JSONArray a = apps.getJSONArray(i);
                out.add(new App(a.getString(0), a.getInt(1), a.getString(2)));
            }
        } catch (JSONException | IOException e) {
            return null;
        }
        return out;
    }

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "pc");
    }

    /** Keeps the newest summary per day: the live status every few seconds, the quarter-hourly one as backup. */
    static synchronized void save(Context ctx, String line) {
        try {
            JSONObject o = new JSONObject(line);
            String day = LocalDate.parse(o.getString("day")).toString();
            File d = dir(ctx);
            if (!d.isDirectory() && !d.mkdirs()) return;
            File f = new File(d, day + ".json");
            if (f.exists()) { // a summary that arrives late must not replace a newer one
                String at = o.optString("at"), had = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optString("at");
                if (!had.isEmpty() && (at.isEmpty() || at.compareTo(had) < 0)) return;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException | IOException | RuntimeException e) {
            // a malformed summary is skipped; the next one replaces it
        }
    }

    /** Null when the PC has not reported that day. */
    static List<Seg> load(Context ctx, LocalDate day) {
        File f = new File(dir(ctx), day + ".json");
        if (!f.exists()) return null;
        List<Seg> out = new ArrayList<>();
        try {
            JSONArray spans = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getJSONArray("spans");
            for (int i = 0; i < spans.length(); i++) {
                JSONArray s = spans.getJSONArray(i);
                out.add(new Seg(s.getInt(0), s.getInt(1), s.getString(2)));
            }
        } catch (JSONException | IOException e) {
            return null;
        }
        return out;
    }
}
