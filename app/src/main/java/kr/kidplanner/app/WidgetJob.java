package kr.kidplanner.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/** 앱이 닫혀 있어도 위젯 데이터를 새로 받아오는 백그라운드 작업. */
public class WidgetJob extends JobService {

    private static final int PERIODIC_ID = 7101;
    private static final int NOW_ID = 7102;

    static void schedulePeriodic(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == PERIODIC_ID) return;
        }
        JobInfo info = new JobInfo.Builder(PERIODIC_ID, new ComponentName(c, WidgetJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    static void refreshNow(Context c) {
        boolean parent = "parent".equals(WidgetData.prefs(c).getString("role", ""));
        if (TodayWidget.ids(c).length == 0 && !parent) return;
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo info = new JobInfo.Builder(NOW_ID, new ComponentName(c, WidgetJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(5000)
                .build();
        js.schedule(info);
    }

    static void cancelAll(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(PERIODIC_ID);
        js.cancel(NOW_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try {
                WidgetData.fetch(ctx);
            } catch (Exception e) {
                Log.w("KidPlannerWidget", "fetch failed", e);
            }
            TodayWidget.updateAll(ctx);
            try { NotifyScheduler.checkReward(ctx); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
