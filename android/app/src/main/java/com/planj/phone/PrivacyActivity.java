package com.planj.phone;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.OutputStream;
import java.time.LocalDate;

/** Privacy: pause recording, keep some apps private, take a copy, and how it all works. */
public class PrivacyActivity extends Activity {
    private static final int REQ_EXPORT = 51;
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
        render();
    }

    private void render() {
        boolean p = PrivateMode.isOn(this);
        page.clear();
        page.icon(null, R.drawable.ic_shield);
        page.title("Privacy");
        page.blurb("What planj records stays on your devices, and is encrypted between them.");
        page.section("Recording");
        page.link(R.drawable.ic_private, "Private mode", p ? "On since " + Fmt.clock(PrivateMode.since(this)) + " · nothing is recorded"
                : "Off · tap to pause all recording", () -> {
            PrivateMode.set(this, !p);
            Toast.makeText(this, !p ? "Private — nothing is recorded until you turn it off" : "Recording again", Toast.LENGTH_LONG).show();
            render();
        });
        page.link(R.drawable.ic_lock, "Private apps", "Recorded only as “Private”",
                () -> startActivity(new Intent(this, AppPickerActivity.class)));
        page.link(R.drawable.ic_star, "Quick Settings tile", "Turn private mode on from anywhere", this::addTile);
        page.section("Your data");
        page.link(R.drawable.ic_export, "Export a copy", "Everything planj holds, as a file you keep", () ->
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "planj-phone-" + LocalDate.now() + ".jsonl"), REQ_EXPORT));
        page.link(R.drawable.ic_shield, "How privacy works", "What's recorded, and what never is", () ->
                startActivity(new Intent(this, AccountActivity.class).putExtra(AccountActivity.EXTRA_SCREEN, "privacy")));
    }

    private void addTile() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new android.content.ComponentName(this, PrivateTileService.class), "planj Private",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_private),
                    getMainExecutor(), result -> {});
        } else {
            Toast.makeText(this, "Swipe down, tap the pencil, and drag “planj Private” into your tiles", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            String msg;
            try {
                UsageCollector.collect(this);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    UsageCollector.export(this, out);
                    TarcStore.export(this, out);
                }
                msg = "Exported";
            } catch (Exception e) {
                msg = "Export failed: " + e.getMessage();
            }
            String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }
}
