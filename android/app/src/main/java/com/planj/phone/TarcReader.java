package com.planj.phone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the TAR UMT Student Intranet in a web view: on screen while you sign in yourself, or
 * hidden for the twice-daily check. Only opens pages about your studies, chosen by the portal's
 * own menu names; never submits a form other than the sign-in, and never opens money or
 * identity pages. Pages go to a staging area and replace the last read only when it finishes.
 */
final class TarcReader {
    static final String LOGIN = "https://web.tarc.edu.my/portal/login.jsp";

    enum Result { OK, REJECTED, FAILED }

    interface Listener {
        void progress(String what);

        /** There is no saved password: the person signs in on the page, which is now showing. */
        void signInNeeded();

        /** Signed in; the reading has started. */
        void reading();

        void done(Result result);
    }

    /** The portal's own menu items for what planj reads, and what each is kept as. */
    private static final String[][] WANTED = {
            {"My Timetable", "sessions"}, // the list of semesters; the newest one's timetable is opened from it
            {"Overall Result", "results"},
            {"Exam Timetable", "exams"},
            {"Provisional Exam Timetable", "exams_provisional"},
            {"Course, Lecturer and Tutor Evaluation", "evaluation"},
            {"Cocu Coursework Marks", "cocu"},
            {"Dean's and President's List", "deans"},
            {"Academic Advisory", "advisory"},
            {"Barred List", "barred"},
            {"Programme Structure", "structure"},
            {"Announcement", "news"}, // "Announcement +44": matched by its start
    };

    /** Only one read at a time, on screen or in the background: they share the sign-in. */
    static volatile boolean busy;

    private final Context ctx;
    private final WebView web;
    private final TarcCreds.Login creds;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Deque<String[]> queue = new ArrayDeque<>(); // {kind, url}
    private String[] pending, capturing;
    private int seq;
    private boolean reading, triedCreds, finished;

    @SuppressLint("SetJavaScriptEnabled") // TAR UMT's own pages need their scripts
    TarcReader(Context ctx, WebView web, TarcCreds.Login creds, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.web = web;
        this.creds = creds;
        this.listener = listener;
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setSaveFormData(false); // nothing typed here is remembered by planj's web view
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
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
    }

    static boolean tarc(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(u.getScheme()) && (host.endsWith("tarc.edu.my") || host.endsWith("tarumt.edu.my"));
    }

    /** Starts from the last home page, so a session that is still open needs no sign-in. */
    void start() {
        busy = true;
        String home = TarcStore.state(ctx).optString("home", "");
        web.loadUrl(home.isEmpty() ? LOGIN : home);
    }

    /** Starts from the sign-in page (after the cookies were cleared, to check a password). */
    void startSignIn() {
        busy = true;
        web.loadUrl(LOGIN);
    }

    void cancel() {
        if (finished) return;
        finished = true;
        seq++;
        busy = false;
        web.stopLoading();
    }

    private void pageFinished(String url) {
        if (finished) return;
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("about:")) return;
        if (!signedIn(u)) {
            if (reading) { // the session ended part way: sign in again, then start over
                reading = false;
                queue.clear();
                pending = capturing = null;
                seq++;
            }
            if (!u.contains("/login.jsp")) return; // on the way there
            if (creds != null && !triedCreds) {
                triedCreds = true;
                listener.progress("Signing in to TAR UMT");
                web.evaluateJavascript("(function(){var f=document.loginForm;if(!f)return 'no form';"
                        + "f.username.value=" + JSONObject.quote(creds.id) + ";f.password.value=" + JSONObject.quote(creds.password)
                        + ";f.submit();return 'ok';})()", r -> {
                    if (!"\"ok\"".equals(r)) finish(Result.FAILED);
                });
            } else if (creds != null) {
                finish(Result.REJECTED); // back on the sign-in page after trying: TAR UMT said no
            } else {
                listener.signInNeeded();
            }
            return;
        }
        if (!reading) {
            reading = true;
            listener.reading();
            startRead(url);
        } else if (pending != null) {
            capture(pending);
        }
    }

    private static boolean signedIn(String u) {
        return !u.contains("login") && !u.contains("retrievenewpass");
    }

    /** On the first page after signing in: keep it, and find the pages planj reads. */
    private void startRead(String landing) {
        TarcStore.beginRead(ctx);
        int s = ++seq;
        listener.progress("Finding your pages");
        ui.postDelayed(() -> {
            if (s != seq) return;
            web.evaluateJavascript(COLLECT, links -> {
                List<String[]> found = new ArrayList<>();
                try {
                    JSONArray all = new JSONArray((String) new JSONTokener(links).nextValue());
                    Set<String> taken = new HashSet<>();
                    for (String[] want : WANTED) { // the menu's own names, not the dashboard's reminders
                        for (int i = 0; i < all.length(); i++) {
                            JSONArray a = all.getJSONArray(i);
                            String text = a.optString(0).trim(), href = a.optString(1);
                            boolean match = want[1].equals("news") ? text.startsWith(want[0]) : text.equalsIgnoreCase(want[0]);
                            if (!match || href.isEmpty() || !tarc(Uri.parse(href)) || !taken.add(href)) continue;
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
                    JSONObject st = TarcStore.state(ctx);
                    st.put("home", landing);
                    TarcStore.saveState(ctx, st);
                } catch (Exception ignored) {
                    // the next read starts from the sign-in page instead
                }
                capture(pending);
            });
        }, 1500);
    }

    /** Keeps the page now showing, then opens the next one. */
    private void capture(String[] what) {
        if (what == capturing) return; // a page can report "finished" more than once; it is read once
        capturing = what;
        int s = seq;
        listener.progress(label(what[0]));
        ui.postDelayed(() -> {
            if (s != seq) return;
            web.evaluateJavascript(CAPTURE, page -> {
                if (s != seq) return;
                try {
                    JSONObject p = new JSONObject((String) new JSONTokener(page).nextValue());
                    p.put("at", System.currentTimeMillis());
                    TarcStore.savePage(ctx, what[0], p);
                    if (what[0].equals("sessions")) openNewestSemester(p.optString("html"));
                    if (what[0].equals("timetable")) openAttendance(p.optString("url"), p.optString("html"));
                } catch (Exception ignored) {
                    // this page is skipped
                }
                next();
            });
        }, 1500); // the portal draws some of its pages with scripts after loading
    }

    private static String label(String kind) {
        switch (kind) {
            case "timetable": return "Reading your timetable";
            case "attendance": return "Reading your attendance";
            case "results": return "Reading your results";
            case "exams":
            case "exams_provisional": return "Reading your exam timetable";
            case "news": return "Reading announcements";
            case "evaluation": return "Reading your course evaluation";
            default: return "Reading your portal";
        }
    }

    /** From the list of semesters: keep the newest one's dates, and open its timetable next. */
    private void openNewestSemester(String html) {
        List<TarcParse.Session> sessions = TarcParse.sessions(html);
        if (sessions.isEmpty()) return;
        TarcParse.Session s = sessions.get(0);
        queue.addFirst(new String[]{"timetable", s.timetableUrl()});
        try {
            JSONObject st = TarcStore.state(ctx);
            st.put("session", s.code).put("weeks", s.weeks);
            if (s.start != null) st.put("start", s.start.toString());
            if (s.end != null) st.put("end", s.end.toString());
            TarcStore.saveState(ctx, st);
        } catch (Exception ignored) {
            // the timetable is still read
        }
    }

    /** From the timetable: each course's attendance page, which lists every class you were marked at. */
    private void openAttendance(String pageUrl, String html) {
        List<TarcParse.Course> courses = TarcParse.courses(html);
        for (int i = Math.min(courses.size(), 12) - 1; i >= 0; i--) {
            try {
                String url = URI.create(pageUrl).resolve("viewAttendance.jsp?crs=" + Uri.encode(courses.get(i).code)).toString();
                queue.addFirst(new String[]{"attendance", url});
            } catch (RuntimeException ignored) {
                // that course's attendance is skipped
            }
        }
    }

    private void next() {
        pending = queue.poll();
        if (pending == null) {
            TarcStore.commitRead(ctx);
            TarcDeadlines.record(ctx); // what left the dashboard since the last read
            try {
                JSONObject st = TarcStore.state(ctx);
                st.put("read", System.currentTimeMillis());
                TarcStore.saveState(ctx, st);
            } catch (Exception ignored) {
                // shown as read without a time
            }
            finish(Result.OK);
            return;
        }
        int s = seq;
        String[] now = pending;
        web.loadUrl(now[1]);
        ui.postDelayed(() -> { // a page that never finishes loading is skipped
            if (s == seq && pending == now && !finished) next();
        }, 25_000);
    }

    private void finish(Result r) {
        if (finished) return;
        finished = true;
        seq++;
        busy = false;
        listener.done(r);
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
}
