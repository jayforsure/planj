package com.planj.phone;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/** Hourly: saves new usage events before Android's short retention drops them, then syncs to the PC. */
public class SnapshotJob extends JobService {
    private static final int JOB_ID = 1;

    static void schedule(Context ctx) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, SnapshotJob.class))
                .setPeriodic(TimeUnit.HOURS.toMillis(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        ctx.getSystemService(JobScheduler.class).schedule(job);
    }

    /** Collect then upload; used by the job and whenever the app is opened. */
    static void collectAndSync(Context ctx) {
        try {
            UsageCollector.collect(ctx);
        } catch (Exception e) {
            Log.w("planj", "collect failed", e);
        }
        try {
            RelaySync.upload(ctx);
        } catch (Exception e) {
            Log.w("planj", "sync failed", e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            collectAndSync(this);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
