package com.planj.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Connect TAR UMT. You sign in on TAR UMT's own page, shown here as it is, and planj reads the
 * pages about your studies and keeps them only on this phone. If you turn on automatic refresh,
 * planj keeps your login encrypted on this phone and checks twice a day by itself.
 */
public class TarcActivity extends Activity {
    static final String TARC_APP = "app.tarc.edu.my";

    private enum Mode { INTRO, SIGNIN, READING, DONE, WEEK, AUTO }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private LinearLayout stage;
    private View pages, siteBar;
    private TextView siteHost, progressText;
    private Mode mode = Mode.INTRO;
    private TarcReader reader;
    private TarcCreds.Login trying; // a login being checked on the Automatic refresh page

    @SuppressLint("SetJavaScriptEnabled") // TAR UMT's own sign-in needs its scripts
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tarc);
        web = findViewById(R.id.web);
        stage = findViewById(R.id.stage);
        pages = findViewById(R.id.pages);
        siteBar = findViewById(R.id.site_bar);
        siteHost = findViewById(R.id.site_host);
        findViewById(R.id.back).setOnClickListener(v -> onBackPressed());

        web.setBackgroundColor(0xFFFFFFFF);

        if (TarcStore.connected(this)) showDone();
        else showIntro();
    }

    // ---- pages ---------------------------------------------------------------

    private void showIntro() {
        mode = Mode.INTRO;
        showPages();
        icon();
        title("Connect TAR UMT");
        blurb("Your student intranet lets planj know your week: when classes are, what's due, when exams come, "
                + "and how your days line up with your grades.");
        section("What planj reads");
        row(R.drawable.ic_today, "Timetable and attendance", "Your classes, and each class you were marked at");
        row(R.drawable.ic_edit, "Exams and results", "Exam dates and venues, grades, coursework marks");
        row(R.drawable.ic_bell, "Deadlines and announcements", "What the dashboard says is due, and notices");
        section("What it never does");
        row(R.drawable.ic_lock, "Your password", "You sign in on TAR UMT's own page. planj keeps it only if you turn on automatic refresh");
        row(R.drawable.ic_shield, "Money and identity", "Billing, payments, profile and income pages are never opened");
        primary("Sign in to TAR UMT", () -> read(false));
    }

    /** Reads now: from the last session, signing in by itself if automatic refresh is on, else you sign in. */
    private void read(boolean fresh) {
        if (TarcReader.busy && reader == null) {
            toast("planj is reading TAR UMT in the background. Try again in a minute");
            return;
        }
        if (reader != null) reader.cancel();
        TarcCreds.Login saved = trying != null ? trying : TarcCreds.load(this);
        showReading(trying != null ? "Checking your login with TAR UMT, then reading your pages."
                : "Opening TAR UMT. This takes about a minute.");
        reader = new TarcReader(this, web, saved, listener);
        if (fresh) reader.startSignIn();
        else reader.start();
    }

    private final TarcReader.Listener listener = new TarcReader.Listener() {
        @Override
        public void progress(String what) {
            TarcActivity.this.progress(what);
        }

        @Override
        public void signInNeeded() {
            mode = Mode.SIGNIN;
            pages.setVisibility(View.GONE);
            siteBar.setVisibility(View.VISIBLE);
            web.setVisibility(View.VISIBLE);
            siteHost.setText("web.tarc.edu.my");
        }

        @Override
        public void reading() {
            if (mode == Mode.SIGNIN) showReading("Signed in. Reading your pages; this takes about a minute.");
        }

        @Override
        public void done(TarcReader.Result result) {
            reader = null;
            TarcCreds.Login checked = trying;
            trying = null;
            if (result == TarcReader.Result.OK) {
                if (checked != null) turnOn(checked);
                showDone();
            } else if (result == TarcReader.Result.REJECTED && checked != null) {
                showAuto("TAR UMT didn't accept that Login ID and password.", checked.id);
            } else if (result == TarcReader.Result.REJECTED) { // the saved login stopped working
                TarcCreds.clear(TarcActivity.this);
                TarcSync.cancel(TarcActivity.this);
                toast("Your saved password didn't work any more. Sign in on TAR UMT's page");
                read(false);
            } else {
                toast("Couldn't reach TAR UMT. Check your connection and try again");
                if (TarcStore.connected(TarcActivity.this)) showDone();
                else showIntro();
            }
        }
    };

    private void showReading(String note) {
        mode = Mode.READING;
        showPages();
        web.setVisibility(View.INVISIBLE);
        icon();
        title("Reading your pages");
        blurb(note);
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.topMargin = dp(28);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        stage.addView(bar, lp);
        progressText = text("", R.color.muted, 14);
        progressText.setGravity(Gravity.CENTER);
        ((LinearLayout.LayoutParams) progressText.getLayoutParams()).topMargin = dp(12);
    }

    private void showDone() {
        mode = Mode.DONE;
        showPages();
        icon();
        JSONObject st = TarcStore.state(this);
        title("TAR UMT connected");
        long read = st.optLong("read", 0);
        blurb(read == 0 ? "Connected. Everything stays on this phone." : "Read " + when(read) + ". Everything stays on this phone.");
        section("Found");
        int tt = TarcStore.rows(this, "timetable"), rs = TarcStore.rows(this, "results");
        row(R.drawable.ic_school, "Semester", semester(st));
        List<TarcParse.Course> courses = TarcTimetable.courses(this);
        ListRow week = row(R.drawable.ic_today, "Timetable", courses.isEmpty() ? (tt > 0 ? "Read, but no classes were listed" : "Not found on your portal yet")
                : courses.size() + " courses · " + TarcTimetable.lessonsPerWeek(courses) + " classes a week");
        if (!courses.isEmpty()) {
            week.setChevron(true);
            week.setClickable(true);
            week.setBackgroundResource(R.drawable.btn_text);
            week.setOnClickListener(v -> showWeek());
        }
        boolean hidden = false;
        for (JSONObject p : TarcStore.pages(this)) {
            if ("results".equals(p.optString("kind")) && TarcParse.resultsHidden(p.optString("html"))) hidden = true;
        }
        row(R.drawable.ic_target, "Results", hidden ? "Hidden by TAR UMT until you finish this semester's course evaluation"
                : rs > 0 ? "Read · " + rs + " rows" : "Not found on your portal yet");
        List<TarcParse.Exam> exams = TarcExams.read(this);
        row(R.drawable.ic_edit, "Exams", exams.isEmpty() ? "None on your exam timetable" : TarcExams.summary(exams));
        int more = 0;
        for (JSONObject p : TarcStore.pages(this)) {
            String k = p.optString("kind");
            if (!k.equals("home") && !k.equals("sessions") && !k.equals("timetable") && !k.equals("results") && !k.equals("exams")) more++;
        }
        if (more > 0) row(R.drawable.ic_journal, "More from your portal", more + " more pages: attendance, announcements, evaluation and others. Shown here as planj learns to read them");

        List<TarcParse.Deadline> due = TarcDue.soon(this, 60);
        if (!due.isEmpty()) {
            section("Due soon");
            for (TarcParse.Deadline d : due) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d.due);
                ListRow r = row(R.drawable.ic_bell, d.title, d.due.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)));
                r.setValue(days == 0 ? "Today" : days == 1 ? "1 day" : days + " days", days > 7);
            }
        }

        section("Keeping up to date");
        boolean auto = TarcCreds.saved(this);
        long checked = st.optLong("checked", 0);
        String stopped = st.optString("check");
        ListRow autoRow = row(R.drawable.ic_sync, "Refresh automatically", auto
                ? "On · checks twice a day" + (checked > 0 ? " · last " + when(checked) : "")
                : "REJECTED".equals(stopped) ? "Stopped: your saved password no longer works" : "Off · you tap Read again");
        autoRow.setChevron(true);
        autoRow.setClickable(true);
        autoRow.setBackgroundResource(R.drawable.btn_text);
        autoRow.setOnClickListener(v -> {
            if (!TarcCreds.saved(this)) showAuto(null, null);
            else Sheet.confirm(this, R.drawable.ic_sync, "Turn off automatic refresh?",
                    "Your saved TAR UMT login is deleted from this phone. You can still tap Read again any time.",
                    "Turn off", true, () -> {
                        TarcCreds.clear(this);
                        TarcSync.cancel(this);
                        showDone();
                    });
        });
        primary("Read again", () -> read(false));
        secondary("Disconnect", () -> Sheet.confirm(this, R.drawable.ic_close, "Disconnect TAR UMT?",
                "Everything planj read from TAR UMT is deleted from this phone, including a saved login, and planj is signed out of it.",
                "Disconnect", true, () -> {
                    TarcStore.disconnect(this);
                    showIntro();
                }));
    }

    /**
     * Automatic refresh: planj signs in by itself twice a day, which needs your login. It is
     * checked with TAR UMT first, and kept only if TAR UMT accepts it.
     */
    private void showAuto(String error, String id) {
        mode = Mode.AUTO;
        showPages();
        icon();
        title("Refresh automatically");
        blurb("planj signs in to TAR UMT by itself twice a day, reads your pages, and tells you when "
                + "something changes: results, exams, your timetable or a new deadline.");
        section("Your login");
        View form = getLayoutInflater().inflate(R.layout.tarc_login_form, stage, false);
        stage.addView(form);
        TextView err = form.findViewById(R.id.error);
        err.setText(error == null ? "" : error);
        err.setVisibility(error == null ? View.GONE : View.VISIBLE);
        android.widget.EditText idField = form.findViewById(R.id.tarc_id), pwField = form.findViewById(R.id.tarc_pw);
        if (id != null) idField.setText(id);
        android.widget.ImageButton eye = form.findViewById(R.id.tarc_pw_eye);
        eye.setOnClickListener(v -> {
            boolean hidden = pwField.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod;
            int at = pwField.getSelectionEnd();
            pwField.setTransformationMethod(hidden ? android.text.method.HideReturnsTransformationMethod.getInstance()
                    : android.text.method.PasswordTransformationMethod.getInstance());
            pwField.setSelection(Math.max(0, at));
            eye.setImageResource(hidden ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        });
        section("How it is kept");
        row(R.drawable.ic_lock, "Encrypted on this phone", "With a key locked inside this phone. Never synced, never in exports");
        row(R.drawable.ic_shield, "Only sent to TAR UMT", "Typed into TAR UMT's own sign-in page, nowhere else");
        row(R.drawable.ic_close, "Gone when you turn it off", "Turning it off or disconnecting deletes it");
        primary("Check and turn on", () -> {
            String i = idField.getText().toString().trim(), pw = pwField.getText().toString();
            if (i.isEmpty() || pw.isEmpty()) {
                err.setText(i.isEmpty() ? "Enter your Login ID" : "Enter your password");
                err.setVisibility(View.VISIBLE);
                return;
            }
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(pwField.getWindowToken(), 0);
            trying = new TarcCreds.Login(i, pw);
            // sign out of any open session first, so TAR UMT really checks this login
            android.webkit.CookieManager.getInstance().removeAllCookies(ok -> read(true));
        });
        if (id == null) idField.requestFocus();
        else pwField.requestFocus();
    }

    private void turnOn(TarcCreds.Login login) {
        try {
            TarcCreds.save(this, login);
            TarcSync.schedule(this);
            JSONObject st = TarcStore.state(this);
            st.remove("check");
            TarcStore.saveState(this, st);
            toast("Automatic refresh is on");
        } catch (Exception e) {
            toast("Couldn't keep your login on this phone, so automatic refresh is off");
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    /** "202605 · 15 Jun – 20 Sep · 14 weeks", and whether it has ended. */
    private static String semester(JSONObject st) {
        String code = st.optString("session");
        if (code.isEmpty()) return "Not found on your portal yet";
        try {
            java.time.LocalDate start = java.time.LocalDate.parse(st.optString("start"));
            java.time.LocalDate end = java.time.LocalDate.parse(st.optString("end"));
            String span = start.format(DAY_MONTH) + " – " + end.format(DAY_MONTH);
            if (end.isBefore(java.time.LocalDate.now())) return code + " · ended " + end.format(DAY_MONTH) + ". The next appears when TAR UMT releases it";
            return code + " · " + span + " · " + st.optInt("weeks") + " weeks";
        } catch (RuntimeException e) {
            return code;
        }
    }

    private static final java.time.format.DateTimeFormatter DAY_MONTH = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** "today at 21:40" or "21:40, 3 Oct". */
    private static String when(long ms) {
        java.time.ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault());
        if (z.toLocalDate().equals(java.time.LocalDate.now())) return "today at " + Fmt.clock(ms);
        return Fmt.clock(ms) + ", " + z.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
    }

    /** Your week: each day's classes, then each course with your attendance so far. */
    private void showWeek() {
        mode = Mode.WEEK;
        showPages();
        title("Your week");
        JSONObject st = TarcStore.state(this);
        blurb(st.optString("session").isEmpty() ? "From TAR UMT." : "Semester " + semester(st) + ".");
        List<TarcParse.Course> courses = TarcTimetable.courses(this);
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            List<String[]> lines = new ArrayList<>();
            List<int[]> order = new ArrayList<>();
            for (TarcParse.Course c : courses) {
                for (TarcParse.Lesson l : c.lessons) {
                    if (l.day != d) continue;
                    lines.add(new String[]{c.name, DayTimeline.clock(l.startMin) + "–" + DayTimeline.clock(l.endMin) + " · " + l.type + " · " + l.venue});
                    order.add(new int[]{l.startMin, lines.size() - 1});
                }
            }
            if (lines.isEmpty()) continue;
            order.sort((x, y) -> Integer.compare(x[0], y[0]));
            section(d.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
            for (int[] o : order) row(R.drawable.ic_today, lines.get(o[1])[0], lines.get(o[1])[1]);
        }
        section("Courses");
        for (TarcParse.Course c : courses) {
            ListRow r = row(R.drawable.ic_school, c.name, c.code + (c.attendance >= 0 ? " · attendance so far" : ""));
            if (c.attendance >= 0) r.setValue(Math.round(c.attendance) + "%", false);
        }
    }

    @Override
    public void onBackPressed() {
        if (mode == Mode.SIGNIN && web.canGoBack()) {
            web.goBack();
            return;
        }
        if (mode == Mode.WEEK || (mode == Mode.AUTO && TarcStore.connected(this))) {
            showDone();
            return;
        }
        if (mode == Mode.SIGNIN || mode == Mode.READING) {
            if (reader != null) reader.cancel();
            reader = null;
            trying = null;
            if (TarcStore.connected(this) && TarcStore.state(this).has("read")) showDone();
            else finish();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (reader != null) reader.cancel();
        web.destroy();
        super.onDestroy();
    }

    // ---- building pages --------------------------------------------------------

    private void showPages() {
        siteBar.setVisibility(View.GONE);
        pages.setVisibility(View.VISIBLE);
        stage.removeAllViews();
        stage.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_up));
    }

    private void progress(String s) {
        if (progressText != null) progressText.setText(s);
    }

    private void icon() {
        ImageView iv = new ImageView(this);
        Drawable app = AppPalette.icon(this, TARC_APP);
        float d = getResources().getDisplayMetrics().density;
        if (app != null) {
            iv.setImageDrawable(app);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * d);
                }
            });
        } else {
            iv.setImageResource(R.drawable.ic_school);
            iv.setBackgroundResource(R.drawable.icon_circle);
            iv.setPadding(dp(15), dp(15), dp(15), dp(15));
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.bottomMargin = dp(16);
        lp.topMargin = dp(8);
        stage.addView(iv, lp);
    }

    private void title(String s) {
        TextView t = new TextView(this, null, 0, R.style.Auth_Title);
        t.setText(s);
        stage.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void blurb(String s) {
        TextView t = new TextView(this, null, 0, R.style.Body);
        t.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        stage.addView(t, lp);
    }

    private void section(String s) {
        TextView t = new TextView(this, null, 0, R.style.Auth_Section);
        t.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(4);
        stage.addView(t, lp);
    }

    private ListRow row(int icon, String title, String subtitle) {
        ListRow r = new ListRow(this);
        r.setIcon(icon);
        r.setTitle(title);
        r.setSubtitle(subtitle);
        r.setSubtitleLines(2);
        r.setChevron(false);
        r.setClickable(false);
        r.setBackground(null);
        stage.addView(r);
        return r;
    }

    private TextView text(String s, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(color));
        t.setTextSize(sp);
        t.setLineSpacing(dp(3), 1f);
        stage.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void primary(String label, Runnable onClick) {
        Button b = new Button(this, null, 0, R.style.Pill_Primary);
        b.setText(label);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(28);
        stage.addView(b, lp);
    }

    private void secondary(String label, Runnable onClick) {
        Button b = new Button(this, null, 0, R.style.Pill_Secondary);
        b.setText(label);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(12);
        stage.addView(b, lp);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
