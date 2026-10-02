package com.planj.phone;

import java.time.LocalDate;
import java.util.List;

/** A plain-Java check of TarcParse against a made-up page shaped like TAR UMT's. */
public class TarcParseCheck {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        String page = "<table id=\"simple-table\"><thead><tr><td>DATE</td><td>TIME (DURATION)</td><td>COURSE</td>"
                + "<td>VENUE (SEAT NUMBER) </td><td>ROOM NUMBER</td></tr></thead><tbody>"
                + "<tr bgcolor=\"\"><td>2030-01-07<br>MONDAY</td><td>9.00AM (2h)</td>"
                + "<td><strong>ABCD1234</strong> : INTRODUCTION TO TESTING<br>M(XYZ1)&ndash;12</td>"
                + "<td><strong>EH1</strong> (1&ndash;50)<br>Index Range: A&ndash;B</td><td>EXAM HALL 1 </td></tr>"
                + "<tr><td>2030-01-09<br>WEDNESDAY</td><td>2.30PM (1h 30m)</td>"
                + "<td><strong>EFGH5678</strong> : Data and the Web<br>R(XYZ1)&ndash;3</td><td><strong>M2</strong></td><td>M003/004 </td></tr>"
                + "</tbody></table><p>Note: M = Main</p>";
        List<TarcParse.Exam> e = TarcParse.exams(page);
        check(e.size() == 2, "two exams, header and notes skipped");
        TarcParse.Exam a = e.get(0), b = e.get(1);
        check(a.date.equals(LocalDate.of(2030, 1, 7)) && a.startMin == 9 * 60 && a.minutes == 120, "date, 9.00AM and 2h");
        check(a.code.equals("ABCD1234") && a.name.equals("Introduction to Testing"), "code and a readable name");
        check(a.venue.equals("EH1 (1–50)") || a.venue.startsWith("EH1"), "venue");
        check(a.room.equals("EXAM HALL 1"), "room");
        check(b.startMin == 14 * 60 + 30 && b.minutes == 90, "2.30PM and 1h 30m");
        check(b.name.equals("Data and the Web"), "a name already in mixed case is kept");
        check(TarcParse.duration("10.00AM (90m)") == 90 && TarcParse.duration("9.00AM (1.5h)") == 90 && TarcParse.duration("9.00AM") == 0, "durations");
        check(TarcParse.exams("<p>No exams</p>").isEmpty(), "a page without the table gives none");
        String list = "<table id=\"simple-table\"><thead><tr><th>No</th><th>Session</th><th>Campus</th><th>Duration Date</th>"
                + "<th>Total Week</th><th>View Access</th></tr></thead><tbody>"
                + "<tr><td>1</td> <td>202905</td> <td>Main Campus</td> <td>Monday, 11-06-2029 ~ Sunday, 16-09-2029</td>"
                + "<td align=\"center\">14</td><td><button onclick=\"getTimetable('AAA-111','202905','KL');\">View Timetable</button></td></tr>"
                + "<tr><td>2</td> <td>203001</td> <td>Main Campus</td> <td>Monday, 07-01-2030 ~ Sunday, 14-04-2030</td>"
                + "<td align=\"center\">14</td><td><button onclick=\"getTimetable('BBB-222','203001','KL');\">View Timetable</button></td></tr>"
                + "</tbody></table>";
        List<TarcParse.Session> ss = TarcParse.sessions(list);
        check(ss.size() == 2 && ss.get(0).code.equals("203001"), "sessions, newest first");
        TarcParse.Session s0 = ss.get(0);
        check(s0.start.equals(LocalDate.of(2030, 1, 7)) && s0.end.equals(LocalDate.of(2030, 4, 14)) && s0.weeks == 14, "semester dates and weeks");
        check(s0.timetableUrl().endsWith("viewTimetable.jsp?fsid=BBB-222&fsession=203001&fbrncd=KL"), "how to open its timetable");
        check(TarcParse.resultsHidden("<b>Block Viewing</b> You have not completed your online evaluation") && !TarcParse.resultsHidden("<table></table>"), "hidden results are recognised");
        String tt = "<p>Week 1 : 2030-01-07 ~ 2030-01-13<br>Week 2 : 2030-01-14 ~ 2030-01-20</p>"
                + "<label>Semester Timetable By Course :</label><table id=\"simple-table\"><thead><tr><td>No</td><td>Course</td>"
                + "<td>Day &amp; Time (Duration)</td><td>Venue</td><td>Lecturer</td></tr></thead><tbody>"
                + "<tr><td rowspan=\"2\">1</td><td rowspan=\"2\"><a href=\"viewAttendance.jsp?crs=ABCD1234\"><span class=\"badge badge-danger\">71.4%</span></a>"
                + " &nbsp;<strong>ABCD1234</strong> &nbsp;INTRODUCTION TO TESTING <span><div><a href=\"x\"><strong>Course Plan</strong></a></div></span></td>"
                + "<td>&nbsp; <span>Mon , 12:00 PM ~ 2:00 PM ( Lecture: <span> 2.0 hours )</span><br><span class=\"red\"> ( having replacement class )</span></span></td>"
                + "<td> HALL A, Block A </td><td> Someone (Weekly)</td></tr>"
                + "<tr><td>&nbsp; <span>Wed , 1:00 PM ~ 3:00 PM ( Practical: <span> 2.0 hours )</span></span></td><td> LAB 2, Block D </td><td> Someone</td></tr>"
                + "<tr><td rowspan=\"1\">2</td><td rowspan=\"1\"><a href=\"viewAttendance.jsp?crs=XY-1234\"><span class=\"badge\">85.7%</span></a>"
                + " &nbsp;<strong>XY-1234</strong> &nbsp;Ethics Today </td>"
                + "<td><span>Tue , 9:30 AM ~ 10:30 AM ( Tutorial: <span> 1.0 hour )</span></span></td><td> ROOM 9 </td><td> Someone</td></tr>"
                + "</tbody></table>";
        List<TarcParse.Course> cs = TarcParse.courses(tt);
        check(cs.size() == 2, "two courses");
        TarcParse.Course c0 = cs.get(0), c1 = cs.get(1);
        check(c0.code.equals("ABCD1234") && c0.name.equals("Introduction to Testing") && c0.attendance == 71.4, "code, name and attendance");
        check(c0.lessons.size() == 2, "a course's second class on its own row");
        TarcParse.Lesson l0 = c0.lessons.get(0), l1 = c0.lessons.get(1);
        check(l0.day == java.time.DayOfWeek.MONDAY && l0.startMin == 12 * 60 && l0.endMin == 14 * 60 && l0.type.equals("Lecture"), "Mon 12:00-14:00 lecture");
        check(l0.venue.equals("HALL A, Block A"), "venue");
        check(l1.day == java.time.DayOfWeek.WEDNESDAY && l1.startMin == 13 * 60 && l1.type.equals("Practical"), "Wed practical");
        check(c1.code.equals("XY-1234") && c1.lessons.size() == 1 && c1.lessons.get(0).startMin == 9 * 60 + 30, "a hyphenated code, 9:30 AM");
        List<Object[]> ws = TarcParse.weeks(tt);
        check(ws.size() == 2 && ws.get(1)[1].equals(LocalDate.of(2030, 1, 14)), "semester weeks");
        check(TarcParse.clock("12:00 PM") == 720 && TarcParse.clock("12:30 AM") == 30, "noon and midnight");
        String dash = "<ul><li><a href=\"setNavPath.jsp?a=1\"><span class=\"pull-left\">Course Survey</span>"
                + "<span class=\"pull-right\">by Nov 02, 2030</span></a></li>"
                + "<li><a href=\"x\">Exam Slipby Oct 08, 2030</a></li>"
                + "<li><a href=\"y\">Exam Slip by Oct 08, 2030</a></li>"
                + "<li><a href=\"z\">Photo by Nov 30, 2121</a></li>"
                + "<li><a href=\"#\">Profile</a></li></ul>";
        List<TarcParse.Deadline> dl = TarcParse.deadlines(dash);
        check(dl.size() == 3, "three reminders, the repeat counted once");
        check(dl.get(0).title.equals("Exam Slip") && dl.get(0).due.equals(LocalDate.of(2030, 10, 8)), "soonest first, title without 'by'");
        check(dl.get(1).title.equals("Course Survey") && dl.get(1).due.equals(LocalDate.of(2030, 11, 2)), "title and date in separate spans");
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
