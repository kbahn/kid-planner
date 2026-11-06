package kr.kidplanner.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/** 일정 알림 알람 + 재부팅·시간 변경 시 다시 걸기. */
public class AlarmReceiver extends BroadcastReceiver {

    static final String ACTION_ALARM = "kr.kidplanner.app.SCHEDULE_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        String action = intent.getAction();
        if (!ACTION_ALARM.equals(action)) {
            // 재부팅, 시간/시간대 변경, 앱 업데이트
            NotifyScheduler.reschedule(c);
            return;
        }
        final long t = intent.getLongExtra(NotifyScheduler.EXTRA_T, System.currentTimeMillis());
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                List<NotifyScheduler.Event> events = NotifyScheduler.nextEvents(c, t - 61_000L);
                // 울린 시각(±1분)의 이벤트만
                for (int i = events.size() - 1; i >= 0; i--) {
                    if (Math.abs(events.get(i).time - t) > 60_000L) events.remove(i);
                }
                boolean hasEnd = false;
                for (NotifyScheduler.Event e : events) if (!e.isStart) hasEnd = true;
                if (hasEnd) {
                    // 끝 알림은 최신 체크 상태를 받아와서, 이미 체크했으면 생략
                    try {
                        WidgetData.fetch(c);
                    } catch (Exception ex) {
                        Log.w("KidPlannerAlarm", "fetch failed", ex);
                    }
                    TodayWidget.updateAll(c);
                }
                NotifyScheduler.show(c, events);
                NotifyScheduler.checkReward(c);
            } catch (Exception ex) {
                Log.w("KidPlannerAlarm", "alarm failed", ex);
            } finally {
                NotifyScheduler.reschedule(c);
                pr.finish();
            }
        }).start();
    }
}
