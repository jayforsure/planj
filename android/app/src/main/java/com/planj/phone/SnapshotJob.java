package com.planj.phone;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/** Periodically saves new usage events so nothing ages out of Android's short retention window. */
public class SnapshotJob extends JobService {
    private static final int JOB_ID = 1;

    static void schedule(Context ctx) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, SnapshotJob.class))
                .setPeriodic(TimeUnit.HOURS.toMillis(6))
                .setPersisted(true)
                .build();
        ctx.getSystemService(JobScheduler.class).schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                UsageCollector.collect(this);
            } catch (Exception e) {
                Log.w("planj", "snapshot failed", e);
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
