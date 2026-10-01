package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * What a PC app or site counts as, in words and icons, and the person's own choices, which
 * are sent to the PC so it sorts that app or site their way, past included.
 */
final class PcCategories {
    static final String[] ORDER = {"focus", "entertainment", "social", "chat", "other"};
    private static final String PREFS = "planj_pc_cats";

    private PcCategories() {}

    static String label(String cat) {
        switch (cat) {
            case "focus": return "Focus";
            case "entertainment": return "Watching";
            case "social": return "Social";
            case "chat": return "Chat";
            default: return "Other";
        }
    }

    static String explain(String cat) {
        switch (cat) {
            case "focus": return "Counts toward your focus forecast";
            case "entertainment": return "Watching or playing, not focus";
            case "social": return "Social feeds, not focus";
            case "chat": return "Messages and calls, not focus";
            default: return "Neither focus nor watching";
        }
    }

    static int icon(String cat) {
        switch (cat) {
            case "focus": return R.drawable.ic_target;
            case "entertainment": return R.drawable.ic_play;
            case "social": return R.drawable.ic_people;
            case "chat": return R.drawable.ic_chat;
            default: return R.drawable.ic_apps;
        }
    }

    /** The person's choice for this name, if they made one on this phone. */
    static String chosen(Context ctx, String name) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null);
    }

    /**
     * Saves the choice here and sends it to the PC through the sealed mailbox. The PC applies
     * it when it next collects (within about five minutes), then re-sorts the last two weeks.
     */
    static void choose(Context ctx, String name, String cat) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(name, cat).apply();
        new Thread(() -> {
            try {
                String line = new JSONObject().put("t", Instant.now().toString()).put("event", "pc_rule")
                        .put("name", name).put("cat", cat).toString() + "\n";
                RelaySync.append(ctx, line.getBytes(StandardCharsets.UTF_8));
                RelaySync.upload(ctx);
            } catch (Exception e) {
                // stays queued; the next sync sends it
            }
        }).start();
    }
}
