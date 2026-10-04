package com.planj.phone;

import android.app.Activity;
import android.os.Bundle;

import java.util.HashSet;
import java.util.Set;

/** Pick what you want more of; planj's odds follow these. */
public class GoalsActivity extends Activity {
    private PageBuilder page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        page = new PageBuilder(this, findViewById(R.id.stage));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // back from connecting something, its goal is ready
    }

    private void render() {
        page.clear();
        page.icon(null, R.drawable.ic_target);
        page.title("Your goals");
        page.blurb("Pick what you want more of. planj gives you the odds of each, and what you can do tonight to change them.");
        Set<String> on = new HashSet<>();
        for (Goals.Goal g : Goals.chosen(this)) on.add(g.id);
        page.section("What you want more of");
        for (Goals.Goal g : Goals.ALL) {
            boolean ready = g.ready(this);
            Connectors.Connector c = g.needs == null ? null : Connectors.get(g.needs);
            ListRow r = page.link(g.icon, g.name, ready ? g.about : "Connect " + c.name(this) + " first", () -> {
                if (!ready) {
                    c.open(this);
                    return;
                }
                Set<String> next = new HashSet<>(on);
                if (!next.remove(g.id)) next.add(g.id);
                if (next.isEmpty()) {
                    android.widget.Toast.makeText(this, "Keep at least one goal", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                Goals.set(this, next);
                render();
            });
            if (ready) r.setChecked(on.contains(g.id));
        }
        page.note("Odds are worked out from your own days, and checked against what happened the next morning.");
    }
}
