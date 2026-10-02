package com.planj.phone;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * The twice-daily TAR UMT check, only when automatic refresh is on: signs in with the saved
 * login in a hidden web view, reads, and notifies when something changed.
 */
public class TarcSync extends JobService {
    private static final int JOB_ID = 4;
    private static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(4);

    static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (!TarcCreds.saved(ctx)) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, TarcSync.class))
                .setPeriodic(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(3))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    static void cancel(Context ctx) {
        ctx.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TarcReader reader;
    private WebView web;

    @Override
    public boolean onStartJob(JobParameters params) {
        TarcCreds.Login login = TarcCreds.load(this);
        if (login == null || TarcReader.busy) return false;
        JSONObject before = TarcWatch.snapshot(this);
        web = new WebView(getApplicationContext());
        web.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        web.layout(0, 0, 1080, 2400);
        reader = new TarcReader(this, web, login, new TarcReader.Listener() {
            @Override
            public void progress(String what) {}

            @Override
            public void signInNeeded() {
                end(params, TarcReader.Result.FAILED, before);
            }

            @Override
            public void reading() {}

            @Override
            public void done(TarcReader.Result result) {
                end(params, result, before);
            }
        });
        ui.postDelayed(() -> {
            if (reader != null) {
                reader.cancel();
                end(params, TarcReader.Result.FAILED, before);
            }
        }, TIMEOUT_MS);
        reader.start();
        return true;
    }

    private void end(JobParameters params, TarcReader.Result result, JSONObject before) {
        if (reader == null) return;
        reader = null;
        ui.removeCallbacksAndMessages(null);
        try {
            JSONObject st = TarcStore.state(this);
            st.put("checked", System.currentTimeMillis()).put("check", result.name());
            TarcStore.saveState(this, st);
        } catch (Exception ignored) {
            // the page shows the last read instead
        }
        if (result == TarcReader.Result.OK) {
            TarcWatch.notify(this, TarcWatch.changes(this, before, TarcWatch.snapshot(this)));
        } else if (result == TarcReader.Result.REJECTED) {
            TarcCreds.clear(this); // a password TAR UMT refuses is no use, and shouldn't be kept
            cancel(this);
            TarcWatch.notifyStopped(this);
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        jobFinished(params, result == TarcReader.Result.FAILED); // a failure (offline) is retried
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (reader != null) {
            reader.cancel();
            reader = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
        return true;
    }
}
