package com.planj.phone;

import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

final class SettingsTab {
    private final MainActivity a;
    private final View root;
    private final ListRow priv, reminder, connectors;

    SettingsTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_settings, container, false);
        container.addView(root);
        priv = root.findViewById(R.id.row_private);
        reminder = root.findViewById(R.id.row_reminder);
        connectors = root.findViewById(R.id.row_connectors);
        connectors.setOnClickListener(v -> a.startActivity(new Intent(a, ConnectorsActivity.class)));
        priv.setOnClickListener(v -> a.setPrivate(!PrivateMode.isOn(a)));
        root.findViewById(R.id.row_private_apps).setOnClickListener(v -> a.startActivity(new Intent(a, AppPickerActivity.class)));
        reminder.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())));
        root.findViewById(R.id.row_export).setOnClickListener(v -> a.startExport());
        root.findViewById(R.id.row_privacy).setOnClickListener(v -> a.startActivity(new Intent(a, AccountActivity.class)
                .putExtra(AccountActivity.EXTRA_SCREEN, "privacy")));

        ListRow tile = root.findViewById(R.id.row_tile);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            tile.setOnClickListener(v -> a.getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new android.content.ComponentName(a, PrivateTileService.class), "planj Private",
                    android.graphics.drawable.Icon.createWithResource(a, R.drawable.ic_private),
                    a.getMainExecutor(), result -> {}));
        } else {
            tile.setOnClickListener(v -> a.toast("Swipe down, tap the pencil, and drag “planj Private” into your tiles"));
        }

        TextView version = root.findViewById(R.id.version);
        try {
            version.setText("planj " + a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName);
        } catch (Exception e) {
            version.setText("planj");
        }
    }

    View view() {
        return root;
    }

    void refresh(boolean granted) {
        // the connectors you connected, each opening its own page; then the catalogue
        android.widget.LinearLayout list = root.findViewById(R.id.connected_list);
        list.removeAllViews();
        java.util.List<Connectors.Connector> on = Connectors.connected(a);
        for (Connectors.Connector c : on) {
            ListRow r = new ListRow(a);
            Connectors.fillRow(a, r, c, c.status(a));
            r.setOnClickListener(v -> c.open(a));
            list.addView(r);
        }
        connectors.setTitle(on.size() == Connectors.ALL.size() ? "Browse connectors" : "Add connectors");
        connectors.setSubtitle(on.size() == Connectors.ALL.size() ? null : (Connectors.ALL.size() - on.size()) + " more you can connect");
        boolean p = PrivateMode.isOn(a);
        priv.setSubtitle(p ? "On since " + Fmt.clock(PrivateMode.since(a)) : "Off");
        priv.setTint(a.getColor(p ? R.color.accent : R.color.text));
        reminder.setSubtitle(MoodReminder.enabled(a) ? "21:30" : "Off");
    }
}
