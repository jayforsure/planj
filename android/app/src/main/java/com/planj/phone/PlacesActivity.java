package com.planj.phone;

import android.app.Activity;
import android.app.AlertDialog;
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
        findViewById(R.id.row_add).setOnClickListener(v -> chooseKind(null));
        findViewById(R.id.row_pause).setOnClickListener(v -> {
            Places.setEnabled(this, !Places.enabled(this));
            render();
        });
        findViewById(R.id.row_forget).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Delete every place, marked and noticed? Routines tied to them start over.")
                .setPositiveButton("Forget", (d, w) -> {
                    Places.forget(this);
                    render();
                })
                .setNegativeButton("Cancel", null).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        List<Places.Place> all = Places.list(this);
        marked.removeAllViews();
        noticed.removeAllViews();
        int nMarked = 0, nNoticed = 0;
        for (Places.Place p : all) {
            ListRow row = new ListRow(this, null);
            row.setTitle(p.title());
            row.setSubtitle(detail(p));
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
        ((ListRow) findViewById(R.id.row_pause)).setTitle(Places.enabled(this) ? "Pause places" : "Resume places");
        ((android.widget.TextView) findViewById(R.id.summary)).setText(!Places.enabled(this) ? "Paused"
                : nMarked + " marked · " + nNoticed + " noticed");
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
        List<String> options = new ArrayList<>();
        options.add(p.marked() ? "Rename" : "Save as a place");
        options.add(p.marked() ? "Remove" : "Forget this place");
        new AlertDialog.Builder(this)
                .setTitle(p.title())
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == 0) chooseKind(p);
                    else confirmRemove(p);
                })
                .show();
    }

    private void confirmRemove(Places.Place p) {
        new AlertDialog.Builder(this)
                .setMessage("Remove " + p.title() + "? If you go there again it will be noticed as a new place.")
                .setPositiveButton("Remove", (d, w) -> {
                    try {
                        Places.remove(this, p.id);
                    } catch (Exception e) {
                        toast("Could not remove it");
                    }
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Home, School, Work or your own label; then for a new place, where it is. */
    private void chooseKind(Places.Place existing) {
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add a place" : existing.title())
                .setItems(KIND_LABELS, (d, which) -> {
                    String kind = KINDS[which];
                    if (kind.equals("other")) {
                        askLabel(existing);
                    } else {
                        String label = KIND_LABELS[which];
                        if (existing != null) saveExisting(existing, label, kind);
                        else chooseWhere(label, kind);
                    }
                })
                .show();
    }

    private void askLabel(Places.Place existing) {
        EditText in = new EditText(this);
        in.setHint("Gym, Mum's place, Library…");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        in.setSingleLine(true);
        if (existing != null && existing.marked()) in.setText(existing.label);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("Name this place")
                .setView(in)
                .setPositiveButton("Next", (d, w) -> {
                    String label = in.getText().toString().trim();
                    if (label.isEmpty()) return;
                    if (existing != null) saveExisting(existing, label, "other");
                    else chooseWhere(label, "other");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveExisting(Places.Place p, String label, String kind) {
        try {
            Places.rename(this, p.id, label, kind);
            toast("Saved as " + label);
        } catch (Exception e) {
            toast("Could not save it");
        }
        render();
    }

    private void chooseWhere(String label, String kind) {
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(new String[]{"I'm here now", "Search an address"}, (d, which) -> {
                    if (which == 0) markHere(label, kind);
                    else searchAddress(label, kind);
                })
                .show();
    }

    private void markHere(String label, String kind) {
        if (!Places.hasForeground(this)) {
            toast("Turn on places first, in Settings");
            return;
        }
        toast("Finding where you are…");
        new Thread(() -> {
            Location loc = Places.fix(this);
            runOnUiThread(() -> {
                if (loc == null || (loc.hasAccuracy() && loc.getAccuracy() > 250)) {
                    toast("Couldn't get a clear location. Try again outdoors, or search the address.");
                    return;
                }
                save(label, kind, loc.getLatitude(), loc.getLongitude());
            });
        }).start();
    }

    private void searchAddress(String label, String kind) {
        EditText in = new EditText(this);
        in.setHint("Street, building or area");
        in.setSingleLine(true);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("Where is " + label + "?")
                .setView(in)
                .setPositiveButton("Search", (d, w) -> {
                    String q = in.getText().toString().trim();
                    if (!q.isEmpty()) lookup(label, kind, q);
                })
                .setNegativeButton("Cancel", null).show();
    }

    @SuppressWarnings("deprecation") // the listener form needs API 33; this runs on a worker thread
    private void lookup(String label, String kind, String query) {
        if (!Geocoder.isPresent()) {
            toast("Address search isn't available on this phone. Use \u201cI'm here now\u201d instead.");
            return;
        }
        new Thread(() -> {
            List<Address> found;
            try {
                found = new Geocoder(this, Locale.getDefault()).getFromLocationName(query, 5);
            } catch (Exception e) {
                found = null;
            }
            List<Address> results = found;
            runOnUiThread(() -> {
                if (results == null || results.isEmpty()) {
                    toast("Nothing found for \u201c" + query + "\u201d");
                    return;
                }
                String[] lines = new String[results.size()];
                for (int i = 0; i < results.size(); i++) {
                    Address a = results.get(i);
                    lines[i] = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : a.getFeatureName();
                }
                new AlertDialog.Builder(this)
                        .setTitle("Which one?")
                        .setItems(lines, (d, which) -> save(label, kind, results.get(which).getLatitude(), results.get(which).getLongitude()))
                        .show();
            });
        }).start();
    }

    private void save(String label, String kind, double lat, double lon) {
        try {
            Places.mark(this, label, kind, lat, lon);
            toast(label + " saved");
        } catch (Exception e) {
            toast("Could not save it");
        }
        render();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
