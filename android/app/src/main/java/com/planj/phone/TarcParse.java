package com.planj.phone;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads TAR UMT's portal pages into plain facts. Pure Java so it can be checked off the phone.
 * Only what the page shows in its tables is used; nothing is guessed.
 */
final class TarcParse {
    private TarcParse() {}

    /** One exam from the Exam Timetable. */
    static final class Exam {
        final LocalDate date;
        final int startMin, minutes; // start in minutes after midnight, and how long
        final String code, name, venue, room;

        Exam(LocalDate date, int startMin, int minutes, String code, String name, String venue, String room) {
            this.date = date;
            this.startMin = startMin;
            this.minutes = minutes;
            this.code = code;
            this.name = name;
            this.venue = venue;
            this.room = room;
        }
    }

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[.:](\\d{2})\\s*([AP]M)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINS = Pattern.compile("(\\d+)\\s*m(?:in)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COURSE = Pattern.compile("^([A-Z]{3,5}\\d{4})\\s*:\\s*(.+)$");

    /** The exams on an Exam Timetable page, in the order shown. */
    static List<Exam> exams(String html) {
        List<Exam> out = new ArrayList<>();
        Matcher r = ROW.matcher(html);
        while (r.find()) {
            List<String> cells = cells(r.group(1));
            if (cells.size() < 3) continue;
            Matcher d = DATE.matcher(cells.get(0));
            Matcher t = TIME.matcher(cells.get(1));
            String course = firstLine(cells.get(2));
            Matcher c = COURSE.matcher(course);
            if (!d.find() || !t.find() || !c.find()) continue; // the header row, notes and anything else
            LocalDate date;
            try {
                date = LocalDate.of(Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3)));
            } catch (RuntimeException e) {
                continue;
            }
            int h = Integer.parseInt(t.group(1)) % 12, m = Integer.parseInt(t.group(2));
            if (t.group(3).equalsIgnoreCase("PM")) h += 12;
            out.add(new Exam(date, h * 60 + m, duration(cells.get(1)), c.group(1), titleCase(c.group(2).trim()),
                    cells.size() > 3 ? firstLine(cells.get(3)) : "", cells.size() > 4 ? firstLine(cells.get(4)) : ""));
        }
        return out;
    }

    /** "(2h)", "(1.5h)", "(2h 30m)" or "(90m)" in minutes; 0 when not given. */
    static int duration(String s) {
        int i = s.indexOf('(');
        if (i < 0) return 0;
        String in = s.substring(i);
        int total = 0;
        Matcher h = HOURS.matcher(in);
        if (h.find()) total += Math.round(Float.parseFloat(h.group(1)) * 60);
        Matcher m = MINS.matcher(h.find(0) ? in.substring(h.end()) : in);
        if (m.find()) total += Integer.parseInt(m.group(1));
        return total;
    }

    /** The cells of a table row as text, keeping the line breaks the page drew. */
    static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        Matcher c = CELL.matcher(row);
        while (c.find()) out.add(text(c.group(1)));
        return out;
    }

    static String text(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#39;", "'").replace("&quot;", "\"");
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append(t);
        }
        return b.toString();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    /** "DATA STRUCTURES AND ALGORITHMS" -> "Data Structures and Algorithms". */
    static String titleCase(String s) {
        if (!s.equals(s.toUpperCase(Locale.ROOT))) return s; // already written with care
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            boolean small = b.length() > 0 && (w.equals("and") || w.equals("of") || w.equals("in") || w.equals("for")
                    || w.equals("the") || w.equals("to") || w.equals("with"));
            if (b.length() > 0) b.append(' ');
            b.append(small ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }
}
