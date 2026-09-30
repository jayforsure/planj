package com.planj.phone;

import android.app.Activity;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Places you marked (home, school, work, your own labels) and the ones planj noticed. */
public class PlacesActivity extends Activity {
    private static final String[] KINDS = {"home", "school", "work", "other"};
    private static final String[] KIND_LABELS = {"Home", "School", "Work", "Other…"};

    private LinearLayout marked, noticed;
    private View noticedTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_places);
        marked = findViewById(R.id.marked);
        noticed = findViewById(R.id.noticed);
        noticedTitle = findViewById(R.id.noticed_title);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.row_add).setOnClickListener(v -> startActivity(new android.content.Intent(this, PlacePickerActivity.class)));
        setUpOverview();
        findViewById(R.id.row_pause).setOnClickListener(v -> {
            Places.setEnabled(this, !Places.enabled(this));
            rearm();
            render();
        });
        findViewById(R.id.row_forget).setOnClickListener(v -> Sheet.confirm(this, R.drawable.ic_trash, "Forget every place?",
                "Marked and noticed places are all deleted from this phone, and routines tied to them start over.",
                "Forget all places", true, () -> {
                    Places.forget(this);
                    rearm();
                    render();
                }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        rearm(); // after marking, moving or removing a place in the picker
    }

    /** Arrival alerts follow the marked places, so they are re-armed whenever places change. */
    private void rearm() {
        new Thread(() -> {
            try {
                ArrivalWatch.arm(this);
            } catch (Exception e) {
                android.util.Log.w("planj", "arrival watch", e);
            }
        }).start();
    }

    private android.webkit.WebView overview;
    private boolean overviewReady;

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void setUpOverview() {
        overview = findViewById(R.id.overview);
        View card = findViewById(R.id.map_card);
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 24 * getResources().getDisplayMetrics().density);
            }
        });
        android.webkit.WebSettings ws = overview.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setUserAgentString(ws.getUserAgentString() + " planj/" + BuildInfo.VERSION);
        overview.setBackgroundColor(getColor(R.color.bg));
        overview.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                overviewReady = true;
                view.evaluateJavascript("still()", null);
                showPins();
            }
        });
        overview.loadUrl("file:///android_asset/map.html");
    }

    /** Pins the marked places on the map card; hides the card until there is one. */
    private void showPins() {
        org.json.JSONArray list = new org.json.JSONArray();
        for (Places.Place p : Places.list(this)) {
            if (!p.marked()) continue;
            try {
                list.put(new org.json.JSONObject().put("lat", p.lat).put("lon", p.lon).put("name", p.title()));
            } catch (org.json.JSONException ignored) {
                // skip
            }
        }
        findViewById(R.id.map_card).setVisibility(list.length() == 0 ? View.GONE : View.VISIBLE);
        if (overviewReady && list.length() > 0) overview.evaluateJavascript("pins(" + list + ")", null);
    }

    private void render() {
        List<Places.Place> all = Places.list(this);
        marked.removeAllViews();
        noticed.removeAllViews();
        int nMarked = 0, nNoticed = 0;
        for (Places.Place p : all) {
            ListRow row = new ListRow(this, null);
            row.setTitle(p.title());
            row.setSubtitle(p.marked() ? markedDetail(p) : detail(p));
            row.setIcon(icon(p));
            row.setOnClickListener(v -> placeTapped(p));
            if (p.marked()) {
                marked.addView(row);
                nMarked++;
            } else {
                noticed.addView(row);
                nNoticed++;
            }
        }
        noticedTitle.setVisibility(nNoticed == 0 ? View.GONE : View.VISIBLE);
        showPins();
        ((ListRow) findViewById(R.id.row_pause)).setTitle(Places.enabled(this) ? "Pause places" : "Resume places");
        ((android.widget.TextView) findViewById(R.id.summary)).setText(!Places.enabled(this) ? "Paused"
                : nMarked + " marked · " + nNoticed + " noticed");
    }

    /** Work: "Menara EAN · Jalan Tun Razak, Kuala Lumpur" — the place's own name, then where it is. */
    private static String markedDetail(Places.Place p) {
        List<String> parts = new ArrayList<>();
        if (p.where != null && !p.where.isEmpty() && !p.where.equalsIgnoreCase(p.label)) parts.add(p.where);
        if (p.address != null && !p.address.isEmpty()) parts.add(p.address);
        return parts.isEmpty() ? detail(p) : String.join(" · ", parts);
    }

    private static int icon(Places.Place p) {
        if (p.kind == null) return R.drawable.ic_place;
        switch (p.kind) {
            case "home": return R.drawable.ic_home;
            case "school": return R.drawable.ic_school;
            case "work": return R.drawable.ic_work;
            default: return R.drawable.ic_place;
        }
    }

    /** "12 h · last here today 12:23" */
    private static String detail(Places.Place p) {
        String time = p.samples < 4 ? "under 1 h" : (p.samples / 4) + " h";
        if (p.seenMs == 0) return p.marked() ? "Not visited yet" : time;
        LocalDate seen = Instant.ofEpochMilli(p.seenMs).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        String when = seen.equals(today) ? "today " + Fmt.clock(p.seenMs)
                : seen.equals(today.minusDays(1)) ? "yesterday"
                : seen.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH));
        return time + " · last here " + when;
    }

    private void placeTapped(Places.Place p) {
        if (p.marked()) {
            startActivity(PlacePickerActivity.edit(this, p.id));
            return;
        }
        Sheet.choose(this, p.title(), detail(p),
                new Sheet.Option(R.drawable.ic_add, "Save as a place", false, () -> startActivity(PlacePickerActivity.edit(this, p.id))),
                new Sheet.Option(R.drawable.ic_trash, "Forget this place", true, () -> confirmRemove(p)));
    }

    private void confirmRemove(Places.Place p) {
        Sheet.confirm(this, R.drawable.ic_trash, "Forget " + p.title() + "?",
                "If you go there again it will be noticed as a new place.", "Forget it", true, () -> {
                    try {
                        Places.remove(this, p.id);
                    } catch (Exception e) {
                        toast("Could not remove it");
                    }
                    rearm();
                    render();
                });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
