package com.planj.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Add or edit a place the way map apps do: a map with a fixed pin you move the map under,
 * search that suggests real places as you type, and a panel to label and save it.
 */
public class PlacePickerActivity extends Activity {
    static final String EXTRA_ID = "place_id"; // present when editing

    private static final String[] KINDS = {"home", "school", "work", "other"};
    private static final String[] KIND_LABELS = {"Home", "School", "Work", "Other"};
    private static final int[] KIND_ICONS = {R.drawable.ic_home, R.drawable.ic_school, R.drawable.ic_work, R.drawable.ic_place};

    private WebView map;
    private EditText query, label;
    private LinearLayout suggestions, chips;
    private View suggestionsBox, clear, pin, remove, busy;
    private TextView name, address;
    private Button save;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private double lat = 3.139, lon = 101.6869; // Kuala Lumpur until we know better
    private String placeName, placeAddress;
    private String kind;
    private Places.Place editing;
    private boolean mapReady, suppressReverse;
    private int searchGeneration, reverseGeneration;
    private Runnable pendingSearch;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_place_picker);
        map = findViewById(R.id.map);
        query = findViewById(R.id.query);
        label = findViewById(R.id.label);
        suggestions = findViewById(R.id.suggestions);
        suggestionsBox = findViewById(R.id.suggestions_box);
        chips = findViewById(R.id.chips);
        clear = findViewById(R.id.clear);
        pin = findViewById(R.id.pin);
        remove = findViewById(R.id.remove);
        busy = findViewById(R.id.busy);
        name = findViewById(R.id.place_name);
        address = findViewById(R.id.place_address);
        save = findViewById(R.id.save);

        pin.setTranslationY(-dp(24)); // the tip, not the middle, marks the spot
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id != null) for (Places.Place p : Places.list(this)) if (p.id.equals(id)) editing = p;

        WebSettings ws = map.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setUserAgentString(ws.getUserAgentString() + " planj/" + BuildInfo.VERSION);
        map.setBackgroundColor(getColor(R.color.bg));
        map.addJavascriptInterface(new Bridge(), "Planj");
        map.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                mapReady = true;
                if (editing != null) {
                    moveTo(editing.lat, editing.lon, 17, editing.title(), editing.address);
                } else {
                    locate(false);
                }
            }
        });
        map.loadUrl("file:///android_asset/map.html");

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.locate).setOnClickListener(v -> locate(true));
        clear.setOnClickListener(v -> {
            query.setText("");
            hideSuggestions();
        });
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                scheduleSearch(s.toString().trim());
            }
        });
        query.setOnFocusChangeListener((v, has) -> {
            if (has) scheduleSearch(query.getText().toString().trim());
        });
        query.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                scheduleSearchNow(query.getText().toString().trim());
                return true;
            }
            return false;
        });

        buildChips();
        save.setOnClickListener(v -> save());
        if (editing != null) {
            remove.setVisibility(View.VISIBLE);
            remove.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setMessage("Remove " + editing.title() + "?")
                    .setPositiveButton("Remove", (d, w) -> {
                        try {
                            Places.remove(this, editing.id);
                        } catch (Exception ignored) {
                            // nothing to undo
                        }
                        finish();
                    })
                    .setNegativeButton("Cancel", null).show());
            if (editing.marked()) {
                label.setText("other".equals(editing.kind) ? editing.label : (editing.where != null ? editing.where : ""));
                selectKind(editing.kind != null ? editing.kind : "other");
                save.setText("Save changes");
            }
        }
        updateSave();
    }

    // ----- map -----

    private final class Bridge {
        @JavascriptInterface
        public void moving() {
            ui.post(() -> pin.animate().translationY(-dp(36)).setDuration(120).start());
        }

        @JavascriptInterface
        public void moved(double la, double lo, int zoom) {
            ui.post(() -> {
                pin.animate().translationY(-dp(24)).setDuration(160).start();
                lat = la;
                lon = lo;
                if (suppressReverse) {
                    suppressReverse = false;
                    return;
                }
                lookUpWhatIsHere();
            });
        }
    }

    private void moveTo(double la, double lo, int zoom, String n, String a) {
        lat = la;
        lon = lo;
        placeName = n;
        placeAddress = a;
        showPlace();
        if (!mapReady) return;
        suppressReverse = n != null; // a chosen result already has its name
        map.evaluateJavascript(String.format(Locale.ROOT, "go(%.7f,%.7f,%d)", la, lo, zoom), null);
    }

    private void locate(boolean userAsked) {
        if (!Places.hasForeground(this)) {
            if (userAsked) toast("Location isn't allowed. Turn places on in Settings.");
            return;
        }
        if (userAsked) toast("Finding you…");
        new Thread(() -> {
            Location loc = Places.fix(this);
            ui.post(() -> {
                if (loc == null) {
                    if (userAsked) toast("Couldn't get your location. Search instead.");
                    return;
                }
                moveTo(loc.getLatitude(), loc.getLongitude(), 17, null, null);
                lookUpWhatIsHere();
            });
        }).start();
    }

    private void lookUpWhatIsHere() {
        final int gen = ++reverseGeneration;
        final double la = lat, lo = lon;
        name.setText("Finding this place…");
        address.setText("");
        new Thread(() -> {
            PlaceSearch.Result r;
            try {
                r = PlaceSearch.reverse(la, lo);
            } catch (Exception e) {
                r = null;
            }
            PlaceSearch.Result res = r;
            ui.post(() -> {
                if (gen != reverseGeneration) return;
                placeName = res != null ? res.name : "Dropped pin";
                placeAddress = res != null ? res.address : String.format(Locale.ROOT, "%.5f, %.5f", la, lo);
                showPlace();
            });
        }).start();
    }

    private void showPlace() {
        name.setText(placeName != null ? placeName : "Move the map to the place");
        address.setText(placeAddress != null ? placeAddress : "");
        updateSave();
    }

    // ----- search -----

    private void scheduleSearch(String q) {
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        if (q.length() < 2) {
            showCurrentLocationOnly();
            return;
        }
        pendingSearch = () -> scheduleSearchNow(q);
        ui.postDelayed(pendingSearch, 350);
    }

    private void scheduleSearchNow(String q) {
        if (q.length() < 2) return;
        final int gen = ++searchGeneration;
        final double la = lat, lo = lon;
        new Thread(() -> {
            List<PlaceSearch.Result> found;
            try {
                found = PlaceSearch.search(this, q, la, lo);
            } catch (Exception e) {
                found = null;
            }
            List<PlaceSearch.Result> res = found;
            ui.post(() -> {
                if (gen != searchGeneration || !query.hasFocus()) return;
                suggestions.removeAllViews();
                suggestions.addView(currentLocationRow());
                if (res == null) {
                    suggestions.addView(note("Search isn't reachable right now"));
                } else if (res.isEmpty()) {
                    suggestions.addView(note("No places match “" + q + "”"));
                } else {
                    for (PlaceSearch.Result r : res) suggestions.addView(resultRow(r));
                }
                suggestionsBox.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    private void showCurrentLocationOnly() {
        if (!query.hasFocus()) return;
        suggestions.removeAllViews();
        suggestions.addView(currentLocationRow());
        suggestionsBox.setVisibility(View.VISIBLE);
    }

    private void hideSuggestions() {
        suggestionsBox.setVisibility(View.GONE);
        query.clearFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private View currentLocationRow() {
        View row = row(R.drawable.ic_mylocation, "Use my current location", null, true);
        row.setOnClickListener(v -> {
            hideSuggestions();
            locate(true);
        });
        return row;
    }

    private View resultRow(PlaceSearch.Result r) {
        View row = row(R.drawable.ic_place, r.name, r.address, false);
        row.setOnClickListener(v -> {
            hideSuggestions();
            query.setText(r.name);
            moveTo(r.lat, r.lon, 17, r.name, r.address);
        });
        return row;
    }

    private View row(int icon, String title, String sub, boolean accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackgroundResource(R.drawable.btn_text);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(accent ? R.color.accent : R.color.muted)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
        ip.setMarginEnd(dp(14));
        row.addView(ic, ip);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(accent ? R.color.accent : R.color.text));
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(getColor(R.color.muted));
            s.setTextSize(13);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(s);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(R.color.muted));
        t.setTextSize(14);
        t.setPadding(dp(52), dp(10), dp(16), dp(12));
        return t;
    }

    // ----- label and save -----

    private void buildChips() {
        for (int i = 0; i < KINDS.length; i++) {
            final String k = KINDS[i];
            TextView chip = new TextView(this);
            chip.setText(KIND_LABELS[i]);
            chip.setTag(k);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setTextColor(getColorStateList(R.color.label_chip_text));
            chip.setBackgroundResource(R.drawable.label_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            // four equal chips across the panel: every choice visible, nothing to scroll for
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            if (i < KINDS.length - 1) lp.setMarginEnd(dp(8));
            chip.setOnClickListener(v -> selectKind(k));
            chips.addView(chip, lp);
        }
        label.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { updateSave(); }
        });
    }

    private void selectKind(String k) {
        kind = k;
        for (int i = 0; i < chips.getChildCount(); i++) {
            View c = chips.getChildAt(i);
            c.setSelected(k.equals(c.getTag()));
        }
        // Every label gets a name field: for Other it is the label itself ("Gym"); for Home,
        // School and Work it is the place's own name ("EAN"), shown under the label.
        label.setVisibility(View.VISIBLE);
        label.setHint("other".equals(k) ? "Name, e.g. Gym" : "Place name (optional), e.g. EAN");
        if (label.getText().length() == 0 && placeName != null && !placeName.equals("Dropped pin")) {
            label.setText(placeName);
        }
        updateSave();
    }

    private void updateSave() {
        boolean ok = kind != null && placeName != null
                && (!"other".equals(kind) || label.getText().toString().trim().length() > 0);
        save.setEnabled(ok);
    }

    private void save() {
        String typed = label.getText().toString().trim();
        String title = "other".equals(kind) ? typed : KIND_LABELS[java.util.Arrays.asList(KINDS).indexOf(kind)];
        String where = "other".equals(kind) ? placeName : (typed.isEmpty() ? placeName : typed);
        try {
            if (editing != null) {
                Places.update(this, editing.id, title, kind, lat, lon, placeAddress, where);
            } else {
                Places.mark(this, title, kind, lat, lon, placeAddress, where);
            }
            toast(title + " saved");
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            toast("Could not save it");
        }
    }

    @Override
    public void onBackPressed() {
        if (suggestionsBox.getVisibility() == View.VISIBLE) {
            hideSuggestions();
            return;
        }
        super.onBackPressed();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static Intent edit(Activity from, String id) {
        return new Intent(from, PlacePickerActivity.class).putExtra(EXTRA_ID, id);
    }
}
