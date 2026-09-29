package com.planj.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Icons for PC apps and web services. Each is fetched once from the service's own website
 * (youtube.com asks nothing it doesn't already know) and kept on the phone; no third-party
 * icon service is ever asked, so nobody else learns which sites are used.
 */
final class PcIcons {
    private static final Map<String, String> DOMAIN = new HashMap<>();

    static {
        String[][] d = {
                {"YouTube", "www.youtube.com"}, {"Netflix", "www.netflix.com"}, {"Twitch", "www.twitch.tv"},
                {"Bilibili", "www.bilibili.com"}, {"Spotify", "open.spotify.com"}, {"Steam", "store.steampowered.com"},
                {"Instagram", "www.instagram.com"}, {"Facebook", "www.facebook.com"}, {"Reddit", "www.reddit.com"},
                {"TikTok", "www.tiktok.com"}, {"X", "x.com"}, {"Threads", "www.threads.net"}, {"RedNote", "www.xiaohongshu.com"},
                {"LinkedIn", "www.linkedin.com"}, {"WhatsApp", "web.whatsapp.com"}, {"Telegram", "telegram.org"},
                {"Discord", "discord.com"}, {"WeChat", "www.wechat.com"}, {"Messenger", "www.messenger.com"},
                {"Slack", "slack.com"}, {"Gmail", "mail.google.com"}, {"Outlook", "outlook.live.com"},
                {"Teams", "teams.microsoft.com"}, {"Google Meet", "meet.google.com"}, {"Zoom", "zoom.us"},
                {"Google Docs", "docs.google.com"}, {"Google Sheets", "docs.google.com"}, {"Google Slides", "docs.google.com"},
                {"Google Drive", "drive.google.com"}, {"Google Calendar", "calendar.google.com"},
                {"Google Classroom", "classroom.google.com"}, {"Google Search", "www.google.com"}, {"Gemini", "gemini.google.com"},
                {"Perplexity", "www.perplexity.ai"}, {"Canva", "www.canva.com"}, {"Figma", "www.figma.com"},
                {"Wikipedia", "www.wikipedia.org"}, {"Shopee", "shopee.com.my"}, {"Lazada", "www.lazada.com.my"},
                {"Medium", "medium.com"}, {"VS Code", "code.visualstudio.com"}, {"PyCharm", "www.jetbrains.com"},
                {"IntelliJ IDEA", "www.jetbrains.com"}, {"Android Studio", "developer.android.com"},
                {"Word", "www.office.com"}, {"Excel", "www.office.com"}, {"PowerPoint", "www.office.com"},
                {"Acrobat", "www.adobe.com"}, {"Obsidian", "obsidian.md"}, {"Notion", "www.notion.so"},
                {"GitHub", "github.com"}, {"GitLab", "gitlab.com"}, {"Stack Overflow", "stackoverflow.com"},
                {"LeetCode", "leetcode.com"}, {"Overleaf", "www.overleaf.com"}, {"Coursera", "www.coursera.org"},
                {"Udemy", "www.udemy.com"}, {"Moodle", "moodle.org"}, {"Claude", "claude.ai"}, {"ChatGPT", "chatgpt.com"},
                {"Railway", "railway.com"}, {"Crunchyroll", "www.crunchyroll.com"}, {"Disney+", "www.disneyplus.com"},
                {"Prime Video", "www.primevideo.com"}, {"Viu", "www.viu.com"}, {"iQIYI", "www.iq.com"},
        };
        for (String[] x : d) DOMAIN.put(x[0], x[1]);
    }

    private PcIcons() {}

    private static File file(Context ctx, String name) {
        return new File(new File(ctx.getFilesDir(), "pcicons"), name.replaceAll("[^A-Za-z0-9]", "_") + ".png");
    }

    /** The icon if it is already here; null otherwise (then call fetch on a worker thread). */
    static Bitmap cached(Context ctx, String name) {
        File f = file(ctx, name);
        return f.exists() && f.length() > 0 ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static boolean known(String name) {
        return domain(name) != null;
    }

    /** A listed service's site, or the name itself when it is already a site ("lms.utar.edu.my"). */
    private static String domain(String name) {
        String d = DOMAIN.get(name);
        if (d != null) return d;
        return name.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+") ? name : null;
    }

    /** Fetches the service's icon from its own site: a large touch icon first, the favicon after. */
    static Bitmap fetch(Context ctx, String name) {
        String domain = domain(name);
        if (domain == null) return null;
        String site = "https://" + domain + "/";
        Bitmap b = usable(get(site + "apple-touch-icon.png"));
        if (b == null) b = usable(get(site + "favicon.ico"));
        if (b == null) { // some sites only name their icon in the page itself
            for (String url : declared(site)) {
                if ((b = usable(get(url))) != null) break;
            }
        }
        if (b != null) save(file(ctx, name), b);
        return b;
    }

    private static Bitmap usable(Bitmap b) {
        return b != null && b.getWidth() >= 16 ? b : null;
    }

    private static void save(File out, Bitmap b) {
        File dir = out.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return;
        try (FileOutputStream fo = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
        } catch (Exception ignored) {
            // shown this time, fetched again next time
        }
    }

    private static final Pattern LINK = Pattern.compile("<link[^>]+>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL = Pattern.compile("rel=\"([^\"]*icon[^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** Icons the homepage names in its own link tags, touch icons first; SVGs skipped (can't decode). */
    private static List<String> declared(String page) {
        List<String> touch = new ArrayList<>(), plain = new ArrayList<>();
        byte[] html = bytes(page, 400 * 1024);
        if (html == null) return touch;
        Matcher m = LINK.matcher(new String(html, java.nio.charset.StandardCharsets.UTF_8));
        while (m.find()) {
            Matcher rel = REL.matcher(m.group()), href = HREF.matcher(m.group());
            if (!rel.find() || !href.find() || href.group(1).toLowerCase(Locale.ROOT).contains(".svg")) continue;
            try {
                String url = URI.create(page).resolve(href.group(1).replace("&amp;", "&")).toString();
                if (!url.startsWith("https://")) continue;
                (rel.group(1).toLowerCase(Locale.ROOT).contains("apple") ? touch : plain).add(url);
            } catch (Exception ignored) {
                // a malformed href: skip it
            }
        }
        touch.addAll(plain);
        return touch;
    }

    private static Bitmap get(String url) {
        byte[] b = bytes(url, 512 * 1024);
        return b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
    }

    private static byte[] bytes(String url, int limit) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(10_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) planj/" + BuildInfo.VERSION);
            if (conn.getResponseCode() / 100 != 2) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int r; (r = in.read(b)) > 0 && buf.size() < limit; ) buf.write(b, 0, r);
                return buf.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
