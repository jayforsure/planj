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

    /** One semester on the My Timetable list, and how to open its timetable. */
    static final class Session {
        final String code, fsid, branch;
        final LocalDate start, end;
        final int weeks;

        Session(String code, String fsid, String branch, LocalDate start, LocalDate end, int weeks) {
            this.code = code;
            this.fsid = fsid;
            this.branch = branch;
            this.start = start;
            this.end = end;
            this.weeks = weeks;
        }

        String timetableUrl() {
            return "https://web.tarc.edu.my/portal/courseReg/viewTimetable.jsp?fsid=" + fsid + "&fsession=" + code + "&fbrncd=" + branch;
        }
    }

    private static final Pattern OPEN = Pattern.compile("getTimetable\\('([^']*)','([^']*)','([^']*)'\\)");
    private static final Pattern DMY = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{4})");

    /** The semesters on the My Timetable page, newest first. */
    static List<Session> sessions(String html) {
        List<Session> out = new ArrayList<>();
        Matcher r = ROW.matcher(html);
        while (r.find()) {
            Matcher o = OPEN.matcher(r.group(1));
            if (!o.find()) continue;
            LocalDate start = null, end = null;
            int weeks = 0;
            for (String cell : cells(r.group(1))) {
                Matcher d = DMY.matcher(cell);
                List<LocalDate> dates = new ArrayList<>();
                while (d.find()) {
                    try {
                        dates.add(LocalDate.of(Integer.parseInt(d.group(3)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1))));
                    } catch (RuntimeException ignored) {
                        // not a date after all
                    }
                }
                if (dates.size() == 2) {
                    start = dates.get(0);
                    end = dates.get(1);
                } else if (cell.matches("\\d{1,2}")) {
                    weeks = Integer.parseInt(cell);
                }
            }
            out.add(new Session(o.group(2), o.group(1), o.group(3), start, end, weeks));
        }
        out.sort((a, b) -> b.code.compareTo(a.code));
        return out;
    }

    /** One weekly class of a course: "Mon, 12:00 PM ~ 2:00 PM, Lecture, DK ABA". */
    static final class Lesson {
        final java.time.DayOfWeek day;
        final int startMin, endMin;
        final String type, venue;

        Lesson(java.time.DayOfWeek day, int startMin, int endMin, String type, String venue) {
            this.day = day;
            this.startMin = startMin;
            this.endMin = endMin;
            this.type = type;
            this.venue = venue;
        }
    }

    /** A course this semester, its attendance so far, and its weekly classes. */
    static final class Course {
        final String code, name;
        final double attendance; // percent, or -1 when not shown
        final List<Lesson> lessons = new ArrayList<>();

        Course(String code, String name, double attendance) {
            this.code = code;
            this.name = name;
            this.attendance = attendance;
        }
    }

    private static final Pattern COURSE_CODE = Pattern.compile("<strong>\\s*([A-Z]{2,5}-?\\d{4})\\s*</strong>(?:\\s|&nbsp;)*([^<]+)");
    private static final Pattern BADGE = Pattern.compile("badge[^>]*>\\s*(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern LESSON = Pattern.compile("(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s*,\\s*(\\d{1,2}:\\d{2}\\s*[AP]M)\\s*~\\s*(\\d{1,2}:\\d{2}\\s*[AP]M)\\s*\\(\\s*([A-Za-z]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*([AP]M)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEK = Pattern.compile("Week\\s*(\\d+)\\s*:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*~\\s*(\\d{4}-\\d{2}-\\d{2})");

    /** The courses on the timetable page's "By Course" table, with their weekly classes. */
    static List<Course> courses(String html) {
        List<Course> out = new ArrayList<>();
        int at = html.indexOf("Timetable By Course");
        if (at < 0) return out;
        Course current = null;
        Matcher r = ROW.matcher(html.substring(at));
        while (r.find()) {
            String row = r.group(1);
            List<String> cells = new ArrayList<>();
            Matcher c = CELL.matcher(row);
            while (c.find()) cells.add(c.group(1));
            if (cells.isEmpty()) continue;
            int first = 0;
            Matcher code = COURSE_CODE.matcher(row);
            if (row.contains("viewAttendance") && code.find()) { // the first row of a course
                Matcher badge = BADGE.matcher(row);
                current = new Course(code.group(1), titleCase(text(code.group(2)).trim()),
                        badge.find() ? Double.parseDouble(badge.group(1)) : -1);
                out.add(current);
                first = 2; // its number and the course cell come before the class
            }
            if (current == null || cells.size() <= first) continue;
            Matcher l = LESSON.matcher(text(cells.get(first)).replace('\n', ' '));
            if (!l.find()) continue;
            String venue = cells.size() > first + 1 ? text(cells.get(first + 1)).replace('\n', ' ') : "";
            current.lessons.add(new Lesson(day(l.group(1)), clock(l.group(2)), clock(l.group(3)),
                    l.group(4).substring(0, 1).toUpperCase(Locale.ROOT) + l.group(4).substring(1).toLowerCase(Locale.ROOT), venue));
        }
        return out;
    }

    /** The teaching weeks of the semester: [week number, first day, last day]. */
    static List<Object[]> weeks(String html) {
        List<Object[]> out = new ArrayList<>();
        Matcher w = WEEK.matcher(html);
        while (w.find()) {
            try {
                out.add(new Object[]{Integer.parseInt(w.group(1)), LocalDate.parse(w.group(2)), LocalDate.parse(w.group(3))});
            } catch (RuntimeException ignored) {
                // a malformed week is skipped
            }
        }
        return out;
    }

    static int clock(String s) {
        Matcher m = CLOCK.matcher(s);
        if (!m.find()) return -1;
        int h = Integer.parseInt(m.group(1)) % 12;
        if (m.group(3).equalsIgnoreCase("PM")) h += 12;
        return h * 60 + Integer.parseInt(m.group(2));
    }

    private static java.time.DayOfWeek day(String d) {
        switch (d.toLowerCase(Locale.ROOT)) {
            case "mon": return java.time.DayOfWeek.MONDAY;
            case "tue": return java.time.DayOfWeek.TUESDAY;
            case "wed": return java.time.DayOfWeek.WEDNESDAY;
            case "thu": return java.time.DayOfWeek.THURSDAY;
            case "fri": return java.time.DayOfWeek.FRIDAY;
            case "sat": return java.time.DayOfWeek.SATURDAY;
            default: return java.time.DayOfWeek.SUNDAY;
        }
    }

    /** TAR UMT hides results until the semester's course evaluation is done. */
    static boolean resultsHidden(String html) {
        String t = html.toLowerCase(Locale.ROOT);
        return t.contains("block viewing") || t.contains("not completed your online evaluation");
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
