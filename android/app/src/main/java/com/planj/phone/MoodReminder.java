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
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Asks how the day was — one tap, answered from the lock screen, and only when it has been
 * a while: a label every few days keeps the passive signals calibrated without a daily chore.
 */
public class MoodReminder extends BroadcastReceiver {
    private static final String ACTION_REMIND = "com.planj.phone.MOOD_REMINDER";
    private static final String ACTION_LOG = "com.planj.phone.MOOD_LOG";
    private static final String EXTRA_MOOD = "mood";
    private static final String CHANNEL = "mood";
    private static final int NOTIFICATION_ID = 1;
    private static final LocalTime AT = LocalTime.of(21, 30);
    private static final int ASK_AFTER_DAYS = 2; // silence unless the last entry is this old

    static void schedule(Context ctx) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.with(AT);
        if (!next.isAfter(now)) next = next.plusDays(1);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, MoodReminder.class).setAction(ACTION_REMIND),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // An inexact window needs no exact-alarm permission and is kinder to the battery.
        ctx.getSystemService(AlarmManager.class).setWindow(
                AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), TimeUnit.MINUTES.toMillis(30), pi);
    }

    /** Some Android skins grey out notification settings, and skip the permission prompt, until a channel exists. */
    static void ensureChannel(Context ctx) {
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "How was today", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static boolean enabled(Context ctx) {
        return ctx.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static boolean due(Context ctx) {
        Map<LocalDate, MoodStore.Entry> all = MoodStore.all(ctx);
        if (all.isEmpty()) return true;
        LocalDate last = ((java.util.TreeMap<LocalDate, MoodStore.Entry>) all).lastKey();
        return !last.plusDays(ASK_AFTER_DAYS).isAfter(MoodStore.today());
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (ACTION_LOG.equals(action)) {
            int mood = intent.getIntExtra(EXTRA_MOOD, 0);
            if (mood > 0) {
                try {
                    MoodStore.save(ctx, MoodStore.today(), mood, "", List.of());
                } catch (Exception e) {
                    // the notification stays, so the person can try again from the app
                }
            }
            ctx.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
            return;
        }
        if (ACTION_REMIND.equals(action) && due(ctx)) notifyToLog(ctx);
        schedule(ctx);
    }

    private static void notifyToLog(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        ensureChannel(ctx);
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("How was today?")
                .setContentText("One tap. Open the app for a note or the full scale.")
                .setContentIntent(open)
                .setAutoCancel(true);
        String[] labels = {"Bad", "Okay", "Good"};
        int[] moods = {2, 3, 4};
        for (int i = 0; i < 3; i++) {
            PendingIntent log = PendingIntent.getBroadcast(ctx, 10 + i,
                    new Intent(ctx, MoodReminder.class).setAction(ACTION_LOG).putExtra(EXTRA_MOOD, moods[i]),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, labels[i], log).build());
        }
        nm.notify(NOTIFICATION_ID, b.build());
    }
}
