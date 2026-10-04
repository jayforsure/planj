package com.planj.phone;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Tonight's move as a nudge half an hour before, only when you ask for it: "Phone down by 1am,
 * 30 minutes to go". Also remembers which move each evening had, so the morning can say how it went.
 */
public class MoveReminder extends BroadcastReceiver {
    private static final String PREFS = "planj_tonight";
    private static final String CHANNEL = "tonight";
    private static final String EXTRA_MOVE = "move";
    private static final int NOTIFICATION_ID = 7;

    /** The evening a moment belongs to: until 5am it is still last night. */
    static LocalDate evening() {
        return LocalTime.now().isBefore(LocalTime.of(5, 0)) ? LocalDate.now().minusDays(1) : LocalDate.now();
    }

    /** When the nudge comes, in minutes from the evening's midnight (past 24h is after midnight); -1 when the move has no time. */
    static int remindAt(String moveId) {
        switch (moveId) {
            case "bed_23":
            case "no_late_watch": return 22 * 60 + 30;
            case "bed_00": return 23 * 60 + 30;
            case "bed_01": return 24 * 60 + 30;
            case "bed_02": return 25 * 60 + 30;
            default: return -1;
        }
    }

    /** "12:30am" */
    static String clock(int minutes) {
        int h = (minutes / 60) % 24, m = minutes % 60;
        return (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(java.util.Locale.ROOT, "%02d", m) + (h < 12 ? "am" : "pm");
    }

    private static ZonedDateTime when(String moveId) {
        return evening().atStartOfDay(ZoneId.systemDefault()).plusMinutes(remindAt(moveId));
    }

    /** True while there is still time to be nudged tonight. */
    static boolean canSet(String moveId) {
        return remindAt(moveId) >= 0 && when(moveId).isAfter(ZonedDateTime.now());
    }

    static void set(Context ctx, String moveId) {
        if (!canSet(moveId)) return;
        ctx.getSystemService(AlarmManager.class).setWindow(AlarmManager.RTC_WAKEUP,
                when(moveId).toInstant().toEpochMilli(), TimeUnit.MINUTES.toMillis(5), pending(ctx, moveId));
        prefs(ctx).edit().putString("set_" + evening(), moveId).apply();
    }

    static void cancel(Context ctx, String moveId) {
        ctx.getSystemService(AlarmManager.class).cancel(pending(ctx, moveId));
        prefs(ctx).edit().remove("set_" + evening()).apply();
    }

    /** The move a nudge is set for tonight, or null. */
    static String isSet(Context ctx) {
        return prefs(ctx).getString("set_" + evening(), null);
    }

    /** Remembers the move shown for an evening, for the morning after. */
    static void shown(Context ctx, LocalDate evening, String moveId) {
        if (!moveId.equals(prefs(ctx).getString("move_" + evening, null))) {
            prefs(ctx).edit().putString("move_" + evening, moveId).apply();
        }
    }

    static String shownOn(Context ctx, LocalDate evening) {
        return prefs(ctx).getString("move_" + evening, null);
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent pending(Context ctx, String moveId) {
        return PendingIntent.getBroadcast(ctx, 70, new Intent(ctx, MoveReminder.class).putExtra(EXTRA_MOVE, moveId),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String move = intent.getStringExtra(EXTRA_MOVE);
        if (move == null) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Tonight's move", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(ctx, 71, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFICATION_ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(OddsWords.move(move))
                .setContentText("30 minutes to go. Your days have gone better after nights like this.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
