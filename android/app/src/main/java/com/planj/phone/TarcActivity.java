package com.planj.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Connect TAR UMT. You sign in on TAR UMT's own page, shown here as it is: planj never reads,
 * fills or keeps your password, it only notices when you are signed in. Then it opens your
 * timetable and results pages, reads them on this phone, and keeps them only here.
 */
public class TarcActivity extends Activity {
    static final String TARC_APP = "app.tarc.edu.my";
    private static final String LOGIN = "https://web.tarc.edu.my/portal/login.jsp";
    /** The portal's own menu items for what planj reads, and what each is kept as. */
    private static final String[][] WANTED = {
            {"My Timetable", "timetable"},
            {"Overall Result", "results"},
            {"Academic Transcript", "results"},
            {"Exam Timetable", "exams"},
    };

    private enum Mode { INTRO, SIGNIN, READING, DONE }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private LinearLayout stage;
    private View pages, siteBar;
    private TextView siteHost, progressText;
    private Mode mode = Mode.INTRO;
    private final Deque<String[]> queue = new ArrayDeque<>(); // {kind, url}
    private String[] pending;
    private int readSeq;

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

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setSaveFormData(false); // nothing typed here is remembered by planj's web view
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        web.setBackgroundColor(0xFFFFFFFF);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !tarc(req.getUrl()); // only TAR UMT's own pages open here
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageFinished(url);
            }
        });

        if (TarcStore.connected(this)) showDone();
        else showIntro();
    }

    private static boolean tarc(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(u.getScheme()) && (host.endsWith("tarc.edu.my") || host.endsWith("tarumt.edu.my"));
    }

    // ---- pages ---------------------------------------------------------------

    private void showIntro() {
        mode = Mode.INTRO;
        showPages();
        icon();
        title("Connect TAR UMT");
        blurb("Your timetable and results let planj know your week: when classes are, when exams come, "
                + "and how your days line up with your grades.");
        section("What planj reads");
        row(R.drawable.ic_today, "Timetable", "Your classes: day, time, subject and room");
        row(R.drawable.ic_target, "Results", "Your grades each semester");
        row(R.drawable.ic_edit, "Exams", "Your exam dates, times and venues");
        section("What it never does");
        row(R.drawable.ic_lock, "Your password", "You sign in on TAR UMT's own page. planj never sees or keeps it");
        row(R.drawable.ic_shield, "Anything else", "Only those pages are read, and they stay on this phone");
        primary("Sign in to TAR UMT", this::showSignIn);
    }

    private void showSignIn() {
        mode = Mode.SIGNIN;
        pages.setVisibility(View.GONE);
        siteBar.setVisibility(View.VISIBLE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(LOGIN);
    }

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
        row(R.drawable.ic_today, "Timetable", tt > 0 ? "Read · " + tt + " rows" : "Not found on your portal yet");
        row(R.drawable.ic_target, "Results", rs > 0 ? "Read · " + rs + " rows" : "Not found on your portal yet");
        List<TarcParse.Exam> exams = TarcExams.read(this);
        row(R.drawable.ic_edit, "Exams", exams.isEmpty() ? "None on your exam timetable" : TarcExams.summary(exams));
        if (tt == 0 || rs == 0) {
            TextView note = text("Your portal's pages were saved, so planj can learn where these live. "
                    + "They are part of Export my data.", R.color.muted, 13);
            ((LinearLayout.LayoutParams) note.getLayoutParams()).topMargin = dp(8);
        }
        primary("Read again", this::readAgain);
        secondary("Disconnect", () -> Sheet.confirm(this, R.drawable.ic_close, "Disconnect TAR UMT?",
                "Everything planj read from TAR UMT is deleted from this phone, and planj is signed out of it.",
                "Disconnect", true, () -> {
                    TarcStore.disconnect(this);
                    showIntro();
                }));
    }

    /** "today at 21:40" or "21:40, 3 Oct". */
    private static String when(long ms) {
        java.time.ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault());
        if (z.toLocalDate().equals(java.time.LocalDate.now())) return "today at " + Fmt.clock(ms);
        return Fmt.clock(ms) + ", " + z.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
    }

    // ---- signing in and reading ------------------------------------------------

    private void pageFinished(String url) {
        Uri u = Uri.parse(url);
        siteHost.setText(u.getHost() == null ? "" : u.getHost());
        if (mode == Mode.SIGNIN && tarc(u) && signedIn(url)) {
            showReading("Your timetable and results, from TAR UMT. This takes a few seconds.");
            startRead(url);
        } else if (mode == Mode.READING) {
            if (!signedIn(url)) { // the session ended: sign in again
                showSignIn();
                return;
            }
            if (pending == null) startRead(url);
            else capture(pending);
        }
    }

    private static boolean signedIn(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return !u.contains("login") && !u.contains("retrievenewpass") && !u.startsWith("about:");
    }

    /** On the first page after signing in: keep it, and find the timetable and results links. */
    private void startRead(String landing) {
        TarcStore.clearPages(this);
        int seq = ++readSeq;
        progress("Finding your timetable and results");
        ui.postDelayed(() -> {
            if (seq != readSeq) return;
            web.evaluateJavascript(COLLECT, links -> {
                List<String[]> found = new ArrayList<>();
                try {
                    JSONArray all = new JSONArray((String) new JSONTokener(links).nextValue());
                    java.util.Set<String> taken = new java.util.HashSet<>();
                    for (String[] want : WANTED) { // the menu's exact names, not the dashboard's reminders
                        for (int i = 0; i < all.length(); i++) {
                            JSONArray a = all.getJSONArray(i);
                            String text = a.optString(0).trim(), href = a.optString(1);
                            if (!text.equalsIgnoreCase(want[0]) || href.isEmpty() || !tarc(Uri.parse(href)) || !taken.add(href)) continue;
                            found.add(new String[]{want[1], href});
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // nothing found; the home page is still kept
                }
                pending = new String[]{"home", landing};
                queue.clear();
                queue.addAll(found);
                try {
                    JSONObject st = TarcStore.state(this);
                    st.put("home", landing);
                    TarcStore.saveState(this, st);
                } catch (Exception ignored) {
                    // the read goes on; Read again starts from the sign-in page instead
                }
                capture(pending);
            });
        }, 1500);
    }

    private String[] capturing; // a page can report "finished" more than once; it is read once

    /** Keeps the page now showing, then opens the next one. */
    private void capture(String[] what) {
        if (what == capturing) return;
        capturing = what;
        int seq = readSeq;
        progress(what[0].equals("timetable") ? "Reading your timetable" : what[0].equals("results") ? "Reading your results"
                : what[0].equals("exams") ? "Reading your exam timetable" : "Reading your portal");
        ui.postDelayed(() -> {
            if (seq != readSeq) return;
            web.evaluateJavascript(CAPTURE, page -> {
                try {
                    JSONObject p = new JSONObject((String) new JSONTokener(page).nextValue());
                    p.put("at", System.currentTimeMillis());
                    TarcStore.savePage(this, what[0], p);
                } catch (Exception ignored) {
                    // this page is skipped
                }
                next();
            });
        }, 1500); // the portal draws some of its pages with scripts after loading
    }

    private void next() {
        pending = queue.poll();
        if (pending == null) {
            finishRead();
            return;
        }
        int seq = readSeq;
        web.loadUrl(pending[1]);
        ui.postDelayed(() -> { // a page that never finishes loading is skipped
            if (seq == readSeq && pending != null && mode == Mode.READING) next();
        }, 25_000);
    }

    private void finishRead() {
        readSeq++;
        try {
            JSONObject st = TarcStore.state(this);
            st.put("read", System.currentTimeMillis());
            TarcStore.saveState(this, st);
        } catch (Exception ignored) {
            // shown as connected without a time
        }
        showDone();
    }

    private void readAgain() {
        String home = TarcStore.state(this).optString("home", "");
        showReading("Opening TAR UMT. If you were signed out, you'll be asked to sign in again.");
        pending = null;
        web.loadUrl(home.isEmpty() ? LOGIN : home);
    }

    @Override
    public void onBackPressed() {
        if (mode == Mode.SIGNIN && web.canGoBack()) {
            web.goBack();
            return;
        }
        if (mode == Mode.SIGNIN || mode == Mode.READING) {
            readSeq++;
            web.stopLoading();
            if (TarcStore.connected(this) && TarcStore.state(this).has("read")) showDone();
            else finish();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        readSeq++;
        web.destroy();
        super.onDestroy();
    }

    /** Every link on the page and in its frames: [text, address]. */
    private static final String COLLECT = "(function(){var out=[];function grab(d){try{var as=d.querySelectorAll('a');"
            + "for(var i=0;i<as.length;i++){var a=as[i];out.push([((a.innerText||a.textContent||'')+'').trim().slice(0,80),a.href||'']);}"
            + "var fs=d.querySelectorAll('iframe,frame');for(var j=0;j<fs.length;j++){try{grab(fs[j].contentDocument)}catch(e){}}}catch(e){}}"
            + "grab(document);return JSON.stringify(out);})()";

    /** The page as it is drawn now, frames included: its title, address, text and markup. */
    private static final String CAPTURE = "(function(){function all(d){var h=d.documentElement?d.documentElement.outerHTML:'';"
            + "var t=d.body?d.body.innerText:'';var fs=d.querySelectorAll('iframe,frame');for(var j=0;j<fs.length;j++){"
            + "try{var r=all(fs[j].contentDocument);h+='\\n<!-- frame -->\\n'+r[0];t+='\\n'+r[1];}catch(e){}}return [h,t];}"
            + "var r=all(document);return JSON.stringify({title:document.title,url:location.href,html:r[0],text:r[1]});})()";

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

    private void row(int icon, String title, String subtitle) {
        ListRow r = new ListRow(this);
        r.setIcon(icon);
        r.setTitle(title);
        r.setSubtitle(subtitle);
        r.setSubtitleLines(2);
        r.setChevron(false);
        r.setClickable(false);
        r.setBackground(null);
        stage.addView(r);
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
