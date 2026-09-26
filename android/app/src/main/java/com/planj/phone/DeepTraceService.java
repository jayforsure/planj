package com.planj.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Set;

/**
 * Premium: measures what is on screen inside opted-in apps — one "item" at a time (a video,
 * a post, a page) and how long it stayed. Everything it sees is written only to this phone.
 */
public class DeepTraceService extends AccessibilityService {
    private static final long MIN_DWELL_MS = 1500;
    private static final String[] ACTION_WORDS = {"like", "save", "follow", "subscribe", "share", "comment", "repost"};
    private static final String[] MEDIA_APPS = {"youtube", "spotify", "netflix", "music", "bilibili", "viu", "iqiyi"};

    private static boolean isMediaApp(String pkg) {
        String low = pkg.toLowerCase();
        for (String m : MEDIA_APPS) if (low.contains(m)) return true;
        return false;
    }

    private String curApp, curKind, curText;
    private long curSince;


    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        Set<String> apps = ContentStore.deepApps(this);
        boolean traced = apps.contains(pkg) && !PrivateMode.isOn(this) && !PrivateMode.isPrivateApp(this, pkg);
        if (!traced) {
            if (!pkg.equals(getPackageName()) && !pkg.startsWith("com.android.systemui")) close(); // left a traced app
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // Video and music players draw their screens in ways that expose no text, but their
            // playback notification names what is playing. Only transport-style notifications of
            // opted-in media apps are read; a message notification is never looked at.
            if (!isMediaApp(pkg) || !(event.getParcelableData() instanceof android.app.Notification)) return;
            android.app.Notification n = (android.app.Notification) event.getParcelableData();
            if (!android.app.Notification.CATEGORY_TRANSPORT.equals(n.category) || n.extras == null) return;
            CharSequence title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
            CharSequence channel = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT);
            if (title == null || title.length() < 2) return;
            String text = title + (channel != null && channel.length() > 0 ? " · " + channel : "");
            if (!text.equals(curText) || !pkg.equals(curApp)) {
                close();
                curApp = pkg;
                curKind = "video";
                curText = text;
                curSince = System.currentTimeMillis();
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String label = text(event.getSource());
            if (label != null) {
                String low = label.toLowerCase();
                for (String w : ACTION_WORDS) {
                    if (low.contains(w)) {
                        ContentStore.append(this, new ContentStore.Item(pkg, "action", curText == null ? "" : curText, w,
                                System.currentTimeMillis(), 0));
                        break;
                    }
                }
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String[] item = extract(pkg, root);
        root.recycle();
        if (item == null) return;
        if (!item[1].equals(curText) || !pkg.equals(curApp)) {
            close();
            curApp = pkg;
            curKind = item[0];
            curText = item[1];
            curSince = System.currentTimeMillis();
        }
    }

    private void close() {
        if (curApp != null) {
            long dwell = System.currentTimeMillis() - curSince;
            if (dwell >= MIN_DWELL_MS) {
                ContentStore.append(this, new ContentStore.Item(curApp, curKind, curText, null, curSince, dwell));
            }
        }
        curApp = curKind = curText = null;
    }

    /** {kind, text} for the item on screen, or null when nothing recognisable is showing. */
    private String[] extract(String pkg, AccessibilityNodeInfo root) {
        if (pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox")) {
            String url = firstText(root, pkg + ":id/url_bar");
            if (url == null) url = firstText(root, "com.android.chrome:id/url_bar");
            return url == null ? null : new String[]{"page", domain(url)};
        }
        if (pkg.contains("instagram")) {
            // Feed posts name their author in row_feed_photo_profile_name. Reels and other
            // surfaces describe their media as "Reel by X" / "Video by X", which survives
            // layout changes better than any view id. The stories row is not a post.
            String user = firstText(root, pkg + ":id/row_feed_photo_profile_name");
            String kind = "post";
            if (user == null) {
                String[] media = findMediaBy(root);
                if (media != null) {
                    kind = media[0];
                    user = media[1];
                }
            }
            if (user == null) user = findBySuffix(root, "username", 2, "Your story");
            if (user == null) return null;
            user = user.trim();
            // The caption is the text node that starts with the author's handle; it is what
            // makes topic classification meaningful, and it stays on this phone.
            String caption = findCaption(root, user);
            return new String[]{kind, "@" + user + (caption == null ? "" : " — " + caption)};
        }
        if (isMediaApp(pkg)) {
            return null; // what is playing arrives through the playback notification instead
        }
        // Any other opted-in app: its window title, if it offers one.
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(pkg + ":id/title");
        if (!titles.isEmpty()) {
            String t = text(titles.get(0));
            for (AccessibilityNodeInfo n : titles) n.recycle();
            return t == null ? null : new String[]{"screen", t.trim()};
        }
        return null;
    }

    private static final java.util.regex.Pattern MEDIA_BY = java.util.regex.Pattern.compile(
            "^(Reel|Video|Photo(?: \\d+ of \\d+)?|Carousel|Post) by ([^,]+?)(?:,|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** {kind, author} from a media node's description such as "Reel by someone, 12 likes". */
    private static String[] findMediaBy(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence d = n.getContentDescription();
        if (d != null) {
            java.util.regex.Matcher m = MEDIA_BY.matcher(d);
            if (m.find()) {
                String kind = m.group(1).toLowerCase().startsWith("reel") ? "reel"
                        : m.group(1).toLowerCase().startsWith("video") ? "video" : "post";
                return new String[]{kind, m.group(2).trim()};
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            String[] found = findMediaBy(c);
            if (c != null) c.recycle();
            if (found != null) return found;
        }
        return null;
    }

    /** The caption: a text node beginning with the author's handle, trimmed to a snippet. */
    private static String findCaption(AccessibilityNodeInfo n, String author) {
        if (n == null) return null;
        String t = text(n);
        if (t != null) {
            String low = t.toLowerCase(), a = author.toLowerCase();
            if (low.startsWith(a) && t.length() > a.length() + 3) {
                String rest = t.substring(author.length()).replaceAll("\\s*(…|\\.\\.\\.)?\\s*more$", "").trim();
                if (rest.length() > 3) return rest.length() > 100 ? rest.substring(0, 100) + "…" : rest;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            String found = findCaption(c, author);
            if (c != null) c.recycle();
            if (found != null) return found;
        }
        return null;
    }

    /** Depth-first search for a node whose view id ends with {@code suffix} and has usable text. */
    private static String findBySuffix(AccessibilityNodeInfo n, String suffix, int minLen, String... exclude) {
        if (n == null) return null;
        String id = n.getViewIdResourceName();
        if (id != null && id.endsWith(suffix)) {
            String t = text(n);
            if (t != null && t.trim().length() >= minLen) {
                boolean skip = false;
                for (String ex : exclude) if (t.contains(ex)) skip = true;
                if (!skip) return t;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            String found = findBySuffix(c, suffix, minLen, exclude);
            if (c != null) c.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private static String firstText(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        String out = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (out == null) out = text(n);
            n.recycle();
        }
        return out;
    }

    private static String text(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isPassword()) return null;
        // Editable fields are what people type into — skipped, except a browser's address bar
        // while it is merely displaying the loaded page (not focused for typing).
        if (n.isEditable()) {
            String id = n.getViewIdResourceName();
            if (id == null || !id.endsWith(":id/url_bar") || n.isFocused()) return null;
        }
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null || t.length() == 0 ? null : t.toString();
    }

    private static String domain(String url) {
        String u = url.trim().replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int slash = u.indexOf('/');
        return slash > 0 ? u.substring(0, slash) : u;
    }

    @Override
    public void onInterrupt() {
        close();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    static boolean isEnabled(Context ctx) {
        AccessibilityManager am = ctx.getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getId().startsWith(ctx.getPackageName())) return true;
        }
        return false;
    }
}
