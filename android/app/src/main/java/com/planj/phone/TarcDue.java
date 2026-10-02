package com.planj.phone;

import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** What TAR UMT's dashboard says is due, from the last read. */
final class TarcDue {
    private TarcDue() {}

    static List<TarcParse.Deadline> read(Context ctx) {
        for (JSONObject p : TarcStore.pages(ctx)) {
            if ("home".equals(p.optString("kind"))) return TarcParse.deadlines(p.optString("html"));
        }
        return new ArrayList<>();
    }

    /** Due from today to within the next days given, soonest first (open-ended ones far off are left out). */
    static List<TarcParse.Deadline> soon(Context ctx, int days) {
        List<TarcParse.Deadline> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (TarcParse.Deadline d : read(ctx)) {
            if (!d.due.isBefore(today) && !d.due.isAfter(today.plusDays(days))) out.add(d);
        }
        return out;
    }

    /** Anything due on a day, as plans for Odds. */
    static List<Agenda.Event> asPlans(Context ctx, LocalDate day) {
        List<Agenda.Event> out = new ArrayList<>();
        long midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (TarcParse.Deadline d : read(ctx)) if (d.due.equals(day)) out.add(new Agenda.Event("Due: " + d.title, midnight, true));
        return out;
    }
}
