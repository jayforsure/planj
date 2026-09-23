package com.planj.phone;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tick apps for one of two lists: "private" (recorded only as Private) or "deep"
 * (premium: what is on screen inside them is measured, on this phone only).
 */
public class AppPickerActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_PRIVATE = "private", MODE_DEEP = "deep";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day);
        boolean deep = MODE_DEEP.equals(getIntent().getStringExtra(EXTRA_MODE));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.title)).setText(deep ? "Deep tracking" : "Private apps");
        ((TextView) findViewById(R.id.subtitle)).setText(deep
                ? "In ticked apps, planj notes what you watch or read — the video, the author, the site — and how long. Details stay on this phone."
                : "Ticked apps are recorded only as \"Private\" — time counts, the app is never named.");

        LinearLayout list = findViewById(R.id.list);
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>(pm.queryIntentActivities(launcher, 0));
        apps.sort((a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        float density = getResources().getDisplayMetrics().density;

        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (deep && PrivateMode.isPrivateApp(this, pkg)) continue; // never deep-trace a private app
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (8 * density), 0, (int) (8 * density));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(info.loadIcon(pm));
            int size = (int) (34 * density);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
            ip.rightMargin = (int) (14 * density);
            row.addView(icon, ip);

            TextView name = new TextView(this);
            name.setText(info.loadLabel(pm));
            name.setTextColor(getColor(R.color.text));
            name.setTextSize(15);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            CheckBox box = new CheckBox(this);
            box.setChecked(deep ? ContentStore.deepApps(this).contains(pkg) : PrivateMode.isPrivateApp(this, pkg));
            box.setOnCheckedChangeListener((b, checked) -> {
                if (deep) {
                    Set<String> now = ContentStore.deepApps(this);
                    if (checked) now.add(pkg); else now.remove(pkg);
                    ContentStore.setDeepApps(this, now);
                } else {
                    Set<String> now = PrivateMode.privateApps(this);
                    // Store the exact package, and drop any default needle it matched so unticking works.
                    now.removeIf(needle -> pkg.toLowerCase().contains(needle.toLowerCase()));
                    if (checked) now.add(pkg);
                    PrivateMode.setPrivateApps(this, now);
                }
            });
            row.addView(box);
            row.setOnClickListener(v -> box.toggle());
            list.addView(row);
        }
    }
}
