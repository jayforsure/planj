package com.planj.phone;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * What planj read from TAR UMT, kept only on this phone: the timetable and results pages as
 * they were, and when they were read. Never synced; included in Export my data.
 */
final class TarcStore {
    private TarcStore() {}

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "tarc");
    }

    static boolean connected(Context ctx) {
        return new File(dir(ctx), "state.json").exists();
    }

    static JSONObject state(Context ctx) {
        try {
            return new JSONObject(new String(Files.readAllBytes(new File(dir(ctx), "state.json").toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void saveState(Context ctx, JSONObject state) throws IOException {
        write(new File(dir(ctx), "state.json"), state.toString());
    }

    /** Deletes the pages of the last read. */
    static void clearPages(Context ctx) {
        File[] old = new File(dir(ctx), "pages").listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    /** Starts a fresh read in a staging area, so a read that fails part way keeps the last one. */
    static void beginRead(Context ctx) {
        File[] old = new File(dir(ctx), "pages_new").listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    /** The read finished: its pages replace the last read's. */
    static synchronized void commitRead(Context ctx) {
        File staged = new File(dir(ctx), "pages_new"), live = new File(dir(ctx), "pages");
        File[] fresh = staged.listFiles();
        if (fresh == null || fresh.length == 0) return;
        clearPages(ctx);
        if (!live.isDirectory() && !live.mkdirs()) return;
        for (File f : fresh) f.renameTo(new File(live, f.getName()));
    }

    static void savePage(Context ctx, String kind, JSONObject page) throws IOException, org.json.JSONException {
        File d = new File(dir(ctx), "pages_new");
        int n = 0;
        while (new File(d, kind + "-" + n + ".json").exists()) n++;
        page.put("kind", kind);
        write(new File(d, kind + "-" + n + ".json"), page.toString());
    }

    static List<JSONObject> pages(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        File[] files = new File(dir(ctx), "pages").listFiles();
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try {
                out.add(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
                // a damaged page is skipped
            }
        }
        return out;
    }

    /** Rows of tables on the pages of one kind, roughly how much was found. */
    static int rows(Context ctx, String kind) {
        int n = 0;
        for (JSONObject p : pages(ctx)) {
            if (!kind.equals(p.optString("kind"))) continue;
            String html = p.optString("html").toLowerCase(java.util.Locale.ROOT);
            for (int i = html.indexOf("<tr"); i >= 0; i = html.indexOf("<tr", i + 3)) n++;
        }
        return n;
    }

    /** Forgets everything read, and signs planj's web view out of TAR UMT. */
    static void disconnect(Context ctx) {
        clearPages(ctx);
        beginRead(ctx); // empties the staging area too
        TarcCreds.clear(ctx);
        TarcSync.cancel(ctx);
        new File(dir(ctx), "state.json").delete();
        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        android.webkit.CookieManager.getInstance().flush();
    }

    /** For Export my data: each page read, as one line. */
    static void export(Context ctx, OutputStream out) throws IOException {
        for (JSONObject p : pages(ctx)) {
            try {
                p.put("event", "tarc_page");
            } catch (org.json.JSONException ignored) {
                // the line goes out without its label
            }
            out.write((p + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void write(File f, String s) throws IOException {
        File d = f.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
