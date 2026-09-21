package com.planj.phone;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

/** Evening nudge to log mood, shown only if today has not been logged yet. */
public class MoodReminder extends BroadcastReceiver {
    private static final String ACTION = "com.planj.phone.MOOD_REMINDER";
    private static final String CHANNEL = "mood";
    private static final LocalTime AT = LocalTime.of(21, 30);

    static void schedule(Context ctx) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.with(AT);
        if (!next.isAfter(now)) next = next.plusDays(1);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, MoodReminder.class).setAction(ACTION),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // An inexact window needs no exact-alarm permission and is kinder to the battery.
        ctx.getSystemService(AlarmManager.class).setWindow(
                AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), TimeUnit.MINUTES.toMillis(30), pi);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION.equals(intent.getAction()) && MoodStore.moodFor(ctx, MoodStore.today()) == 0) {
            notifyToLog(ctx);
        }
        schedule(ctx);
    }

    /** Some Android skins grey out notification settings, and skip the permission prompt, until a channel exists. */
    static void ensureChannel(Context ctx) {
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Daily mood", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static boolean enabled(Context ctx) {
        return ctx.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private static void notifyToLog(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        ensureChannel(ctx);
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("How was today?")
                .setContentText("One tap to log your mood — it's what the forecasts learn from.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }
}
