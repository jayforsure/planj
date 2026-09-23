package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What was watched, read or opened inside opted-in apps. Kept only on this phone, in
 * content/<day>.jsonl. What syncs is a per-topic summary of finished days.
 */
final class ContentStore {
    static final class Item {
        final String app, kind, text, action;
        final long startMs, dwellMs;

        Item(String app, String kind, String text, String action, long startMs, long dwellMs) {
            this.app = app;
            this.kind = kind;
            this.text = text;
            this.action = action;
            this.startMs = startMs;
            this.dwellMs = dwellMs;
        }
    }

    private static final String PREFS = "planj_deep";
    private static final Map<String, String[]> TOPICS = new LinkedHashMap<>();

    static {
        TOPICS.put("finance", new String[]{"stock", "crypto", "bitcoin", "btc", "eth", "trading", "trader", "forex", "invest", "market", "fund", "bursa", "klse", "股", "投资", "基金"});
        TOPICS.put("fitness", new String[]{"gym", "workout", "fitness", "run", "diet", "muscle", "protein", "健身"});
        TOPICS.put("food", new String[]{"recipe", "food", "cook", "eat", "restaurant", "cafe", "makan", "美食"});
        TOPICS.put("tech", new String[]{"code", "coding", "program", "software", "developer", "python", "javascript", " ai ", "chatgpt", "claude", "android", "startup"});
        TOPICS.put("anime", new String[]{"anime", "one piece", "manga", "luffy", "naruto", "jujutsu", "demon slayer", "动漫"});
        TOPICS.put("gaming", new String[]{"game", "gaming", "valorant", "league of legends", "genshin", "mobile legends", "esports"});
        TOPICS.put("study", new String[]{"exam", "lecture", "study", "university", "tutorial", "course", "assignment", "revision"});
        TOPICS.put("news", new String[]{"news", "breaking", "politic", "election", "minister", "government"});
        TOPICS.put("shopping", new String[]{"sale", "deal", "shopee", "lazada", "unboxing", "review", "haul"});
        TOPICS.put("travel", new String[]{"travel", "trip", "japan", "tokyo", "flight", "hotel", "旅行"});
    }

    private ContentStore() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> deepApps(Context ctx) {
        return new java.util.HashSet<>(prefs(ctx).getStringSet("apps", java.util.Collections.emptySet()));
    }

    static void setDeepApps(Context ctx, Set<String> apps) {
        prefs(ctx).edit().putStringSet("apps", new java.util.HashSet<>(apps)).apply();
    }

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "content");
    }

    static String topic(String text) {
        String low = " " + text.toLowerCase() + " ";
        for (Map.Entry<String, String[]> e : TOPICS.entrySet()) {
            for (String needle : e.getValue()) {
                if (low.contains(needle)) return e.getKey();
            }
        }
        return "other";
    }

    static synchronized void append(Context ctx, Item item) {
        try {
            JSONObject o = new JSONObject()
                    .put("t", Instant.ofEpochMilli(item.startMs).toString())
                    .put("app", item.app)
                    .put("kind", item.kind)
                    .put("text", item.text)
                    .put("dwell_ms", item.dwellMs);
            if (item.action != null) o.put("action", item.action);
            File d = dir(ctx);
            if (!d.isDirectory() && !d.mkdirs()) return;
            String day = Instant.ofEpochMilli(item.startMs).atZone(ZoneId.systemDefault()).toLocalDate().toString();
            try (OutputStream out = new FileOutputStream(new File(d, day + ".jsonl"), true)) {
                out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException | IOException e) {
            // best effort; a lost item is better than a crash inside an accessibility service
        }
    }

    static synchronized List<Item> forDay(Context ctx, LocalDate day) {
        List<Item> out = new ArrayList<>();
        File file = new File(dir(ctx), day + ".jsonl");
        try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    JSONObject o = new JSONObject(line);
                    out.add(new Item(o.getString("app"), o.getString("kind"), o.getString("text"),
                            o.optString("action", null), Instant.parse(o.getString("t")).toEpochMilli(), o.optLong("dwell_ms", 0)));
                } catch (JSONException | RuntimeException e) {
                    // skip a bad line
                }
            }
        } catch (IOException e) {
            // no content that day
        }
        return out;
    }

    /**
     * Once a day, turns yesterday's items into per-app, per-topic totals and appends them to the
     * event log so they sync. Titles and authors stay here.
     */
    static synchronized List<String> summaryLines(Context ctx) throws IOException {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        SharedPreferences p = prefs(ctx);
        if (yesterday.toString().equals(p.getString("summarised", null))) return List.of();
        Map<String, long[]> totals = new HashMap<>(); // app|topic -> {ms, items, actions}
        for (Item it : forDay(ctx, yesterday)) {
            String key = it.app + "|" + topic(it.text);
            long[] t = totals.computeIfAbsent(key, k -> new long[3]);
            t[0] += it.dwellMs;
            t[1]++;
            if (it.action != null) t[2]++;
        }
        List<String> lines = new ArrayList<>();
        try {
            for (Map.Entry<String, long[]> e : totals.entrySet()) {
                String[] parts = e.getKey().split("\\|", 2);
                JSONObject detail = new JSONObject().put("topic", parts[1]).put("ms", e.getValue()[0])
                        .put("items", e.getValue()[1]).put("actions", e.getValue()[2]).put("day", yesterday.toString());
                lines.add(new JSONObject().put("t", yesterday.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toString())
                        .put("event", "content_topic").put("app", parts[0]).put("detail", detail).toString());
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        p.edit().putString("summarised", yesterday.toString()).apply();
        return lines;
    }
}
