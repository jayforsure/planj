package com.planj.phone;

import android.app.Activity;
import android.os.Bundle;

import java.util.List;

/** The catalogue: every connector, the ones you connected first. Each opens its own page. */
public class ConnectorsActivity extends Activity {
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
        page.clear();
        page.title("Connectors");
        page.blurb("Everything planj learns from. Each one adds questions planj can forecast, or the answers that check them.");
        List<Connectors.Connector> on = Connectors.connected(this);
        if (!on.isEmpty()) {
            page.section("Connected");
            for (Connectors.Connector c : on) add(c, c.status(this));
        }
        boolean any = false;
        for (Connectors.Connector c : Connectors.ALL) {
            if (c.connected(this)) continue;
            if (!any) page.section("Available");
            any = true;
            add(c, ConnectorOdds.summary(c.id) + " · " + c.about.substring(0, 1).toLowerCase(java.util.Locale.ENGLISH) + c.about.substring(1));
        }
    }

    private void add(Connectors.Connector c, String subtitle) {
        ListRow r = page.link(c.glyph, c.name(this), subtitle, () -> c.open(this));
        Connectors.fillRow(this, r, c, subtitle);
    }
}
