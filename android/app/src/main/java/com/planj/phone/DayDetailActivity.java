package com.planj.phone;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.time.LocalDate;

/** Every app used on one day, most used first. */
public class DayDetailActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day);
        LocalDate day = LocalDate.parse(getIntent().getStringExtra("day"));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        DayUsage usage = DayUsage.load(this, day);
        long sleep = DayUsage.estimateSleepMs(this, day);
        ((TextView) findViewById(R.id.title)).setText(day.equals(LocalDate.now()) ? "Today" : Fmt.longDate(day));
        ((TextView) findViewById(R.id.subtitle)).setText(Fmt.duration(usage.screenMs) + " on screen · "
                + usage.unlocks + " unlocks" + (sleep > 0 ? " · slept ≈ " + Fmt.shortDuration(sleep) : ""));
        AppRows.fill(this, findViewById(R.id.list), usage, Integer.MAX_VALUE);
    }
}
