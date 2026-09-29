package com.planj.phone;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Every PC app and web service for one day. */
public class PcAppsActivity extends Activity {
    static final String EXTRA_DAY = "day";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pc_apps);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        String d = getIntent().getStringExtra(EXTRA_DAY);
        LocalDate day = d == null ? LocalDate.now() : LocalDate.parse(d);
        List<PcDays.App> apps = PcDays.apps(this, day);
        int total = 0;
        if (apps != null) for (PcDays.App a : apps) total += a.minutes;
        String when = day.equals(LocalDate.now()) ? "Today" : day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH));
        ((TextView) findViewById(R.id.summary)).setText(when + " · " + Fmt.shortDuration(total * 60_000L));
        PcAppRows.fill(this, findViewById(R.id.list), apps, Integer.MAX_VALUE);
    }
}
