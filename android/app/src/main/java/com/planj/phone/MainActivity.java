package com.planj.phone;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.DateFormat;
import java.time.LocalDate;
import java.util.Date;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 1;

    private TextView status;
    private Button grant;
    private Button export;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("planj");
        title.setTextSize(28);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        grant = new Button(this);
        grant.setText("Grant usage access");
        grant.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        root.addView(grant);

        export = new Button(this);
        export.setText("Export data");
        export.setOnClickListener(v -> startExport());
        root.addView(export);

        TextView privacy = new TextView(this);
        privacy.setText("Only which app is open, screen on/off and unlocks are saved — never notifications, "
                + "messages, websites or what you type. Everything stays on this phone until you export it.");
        privacy.setTextSize(13);
        privacy.setPadding(0, pad, 0, 0);
        privacy.setGravity(Gravity.START);
        root.addView(privacy);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (hasUsageAccess()) {
            SnapshotJob.schedule(this);
            new Thread(() -> {
                try {
                    UsageCollector.collect(this);
                } catch (Exception e) {
                    toast("Could not read usage: " + e.getMessage());
                }
                runOnUiThread(this::refresh);
            }).start();
        }
    }

    private void refresh() {
        boolean granted = hasUsageAccess();
        grant.setVisibility(granted ? Button.GONE : Button.VISIBLE);
        export.setEnabled(granted);
        if (!granted) {
            status.setText("To start, allow planj to read app usage.\nTap the button, find planj in the list and switch it on.");
            return;
        }
        long last = UsageCollector.lastEventMs(this);
        status.setText("Tracking is on.\n" + UsageCollector.savedCount(this) + " events saved"
                + (last > 0 ? "\nLatest: " + DateFormat.getDateTimeInstance().format(new Date(last)) : ""));
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, "planj-phone-" + LocalDate.now() + ".jsonl");
        startActivityForResult(intent, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                UsageCollector.collect(this);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    UsageCollector.export(this, out);
                }
                toast("Exported");
            } catch (Exception e) {
                toast("Export failed: " + e.getMessage());
            }
            runOnUiThread(this::refresh);
        }).start();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
