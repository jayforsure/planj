package com.planj.phone;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A plain-Java check of Routines, run with the JDK outside Android. */
public class RoutinesCheck {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    record D(LocalDate date, List<int[]> free, List<int[]> away) implements Routines.DayBlocks {
        public List<int[]> blocks(Routines.Kind k) {
            return k == Routines.Kind.FREE ? free : away;
        }
    }

    public static void main(String[] args) {
        Random rnd = new Random(7);
        LocalDate monday = LocalDate.of(2026, 9, 7); // a Monday
        List<D> days = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            LocalDate d = monday.plusDays(i);
            int dow = d.getDayOfWeek().getValue();
            List<int[]> used = new ArrayList<>();
            // the phone is picked up every 20 minutes or so, all day
            for (int m = 9 * 60; m < 22 * 60 + 30; m += 15 + rnd.nextInt(10)) used.add(new int[]{m, m + 4});
            List<int[]> away = new ArrayList<>();
            // weekdays 08:30-12:30 on campus Wi-Fi counts as away
            if (dow <= 5) away.add(new int[]{8 * 60 + 30, 12 * 60 + 30});
            // gym Tue and Thu 19:00-20:30, phone left in the locker, missed one Thursday
            boolean gym = (dow == 2 || (dow == 4 && i != 24));
            if (gym) {
                used.removeIf(u -> u[1] > 19 * 60 && u[0] < 20 * 60 + 30);
                away.add(new int[]{18 * 60 + 45, 20 * 60 + 45});
            }
            List<int[]> free = Routines.gaps(used, Routines.Kind.FREE);
            days.add(new D(d, free, Routines.clean(away, Routines.Kind.AWAY)));
        }
        List<Routines.Routine> found = Routines.find(days);
        for (Routines.Routine r : found) {
            System.out.printf("   found %s %s %s freq %.2f id %s%n", r.kind, r.days(), r.window(), r.freq, r.id());
        }
        Routines.Routine campus = null, gymAway = null, gymFree = null;
        for (Routines.Routine r : found) {
            if (r.kind == Routines.Kind.AWAY && r.mask == 0b0011111) campus = r;
            if (r.kind == Routines.Kind.AWAY && r.mask == 0b0001010) gymAway = r;
            if (r.kind == Routines.Kind.FREE && r.mask == 0b0001010) gymFree = r;
        }
        check(campus != null && campus.start <= 8 * 60 + 45 && campus.end >= 12 * 60 + 15, "weekday campus block found as away on weekdays");
        check(gymAway != null && gymAway.start <= 19 * 60 && gymAway.end >= 20 * 60 + 30, "Tue/Thu gym found as away, merged across two weekdays");
        check(gymFree != null, "Tue/Thu gym also shows as phone-down (phone left in the locker)");
        check(gymAway != null && "Tue, Thu".equals(gymAway.days()), "days read as 'Tue, Thu'");
        check(gymAway != null && Routines.Routine.parse(gymAway.id()).id().equals(gymAway.id()), "id round-trips for the ledger");
        D missedThursday = days.get(24);
        check(gymAway != null && Boolean.FALSE.equals(gymAway.happened(missedThursday)), "the missed Thursday resolves as not happened");
        check(gymAway != null && Boolean.TRUE.equals(gymAway.happened(days.get(22))), "a gym Tuesday resolves as happened");
        check(gymAway != null && gymAway.happened(days.get(21)) == null, "a Monday is not asked about");
        D noNet = new D(monday, days.get(0).free(), null);
        check(campus != null && campus.happened(noNet) == null, "a day without network data is unknown, not a miss");
        // noise: a random day-to-day scatter must not become a routine
        List<D> noise = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            int s = 9 * 60 + rnd.nextInt(12) * 60;
            noise.add(new D(monday.plusDays(i), Routines.clean(List.of(new int[]{s, s + 60}), Routines.Kind.FREE), null));
        }
        check(Routines.find(noise).isEmpty(), "scattered hours do not make a routine");
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
