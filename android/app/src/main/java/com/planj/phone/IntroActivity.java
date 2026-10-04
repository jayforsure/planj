package com.planj.phone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * How planj works, in three steps, for someone opening it the first time: what it is, the one
 * permission it needs, and what else they could add. Also reachable from You.
 */
public class IntroActivity extends Activity {
    private static final String PREFS = "planj_intro";
    private PageBuilder page;
    private int step;

    /** First time on a phone planj can't see yet. */
    static boolean needed(Context ctx) {
        return !ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("done", false) && !Connectors.usageAccess(ctx);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page);
        findViewById(R.id.back).setOnClickListener(v -> onBackPressed());
        page = new PageBuilder(this, findViewById(R.id.stage));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        page.clear();
        if (step == 0) {
            page.icon(getDrawable(R.mipmap.ic_launcher), 0);
            page.title("Know your odds");
            page.blurb("planj learns your days from what you already do, then tells you your chances tomorrow, and how to change them.");
            page.row(R.drawable.ic_phone, "Nothing to type", "It learns from your phone, and your PC or places if you add them");
            page.row(R.drawable.ic_moon, "Tonight's move", "The one thing tonight that changes tomorrow most, from your own nights");
            page.row(R.drawable.ic_target, "Checked every morning", "Every forecast is scored against what really happened");
            page.row(R.drawable.ic_lock, "Private", "Your data stays on your devices, encrypted between them");
            page.primary("Get started", () -> next(1));
        } else if (step == 1) {
            boolean ok = Connectors.usageAccess(this);
            page.icon(null, R.drawable.ic_phone);
            page.title("Let planj see your phone");
            page.blurb("planj needs Android's usage access: which apps you open, and when your screen is on or off. "
                    + "That's how it learns your days. It stays on this phone.");
            if (ok) {
                page.note("Done. planj can see your phone.");
                page.primary("Continue", () -> next(2));
            } else {
                page.note("Find planj in the list and switch it on, then come back.");
                page.primary("Allow usage access", () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
                page.secondary("Not now", () -> next(2));
            }
        } else {
            page.icon(null, R.drawable.ic_sync);
            page.title("Add more, if you like");
            page.blurb("Each one gives planj more to forecast. You can add them any time in You, under Connectors.");
            for (Connectors.Connector c : Connectors.ALL) {
                if (c.id.equals("phone")) continue;
                ListRow r = page.link(c.glyph, c.name(this), c.connected(this) ? c.status(this) : ConnectorOdds.summary(c.id), () -> c.open(this));
                Connectors.fillRow(this, r, c, c.connected(this) ? c.status(this) : ConnectorOdds.summary(c.id));
            }
            page.primary("Next: your goals", () -> {
                done();
                startActivity(new Intent(this, GoalsActivity.class));
            });
        }
    }

    private void next(int s) {
        step = s;
        render();
    }

    private void done() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("done", true).apply();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (step > 0) next(step - 1);
        else done();
    }
}
