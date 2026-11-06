package kr.kidplanner.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 일정 시작·종료 알림.
 * 시간표(w_model.sched)에서 '다음 알림 시각' 하나만 AlarmManager에 걸고,
 * 울리면 그 시각의 알림을 띄운 뒤 다음 것을 다시 건다.
 *
 * 설정(기기별, SharedPreferences "planner"):
 *  n_lead    : 시작 알림. "-1"=끄기, "0"=정각, "5"/"10"=N분 전  (기본: 아이 탭 "0", 보호자 폰 "-1")
 *  n_end     : 끝날 때 '아직 체크 안 했어요' 알림. "1"/"0" (기본 "1")
 *  n_routine : 생활 일정(학교·저녁 등)도 알림. "1"/"0" (기본 "0")
 */
final class NotifyScheduler {

    static final String CHANNEL = "schedule";
    static final String EXTRA_T = "t";
    private static final int ALARM_REQ = 9001;

    private NotifyScheduler() { }

    static final class Event {
        long time;
        boolean isStart;
        JSONObject item;
        String date;
    }

    // ------------------------------------------------------------ settings

    private static boolean isParent(SharedPreferences p) {
        return "parent".equals(p.getString("role", ""));
    }

    static String lead(SharedPreferences p) {
        return p.getString("n_lead", isParent(p) ? "-1" : "0");
    }

    static boolean endOn(SharedPreferences p) {
        return "1".equals(p.getString("n_end", "1"));
    }

    static boolean routineOn(SharedPreferences p) {
        return "1".equals(p.getString("n_routine", "0"));
    }

    static String settingsJson(Context c) {
        SharedPreferences p = WidgetData.prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("lead", lead(p));
            o.put("end", endOn(p) ? "1" : "0");
            o.put("routine", routineOn(p) ? "1" : "0");
        } catch (JSONException ignored) { }
        return o.toString();
    }

    static void saveSettings(Context c, String lead, String end, String routine) {
        WidgetData.prefs(c).edit()
                .putString("n_lead", lead == null ? "0" : lead)
                .putString("n_end", "1".equals(end) ? "1" : "0")
                .putString("n_routine", "1".equals(routine) ? "1" : "0")
                .apply();
        reschedule(c);
    }

    // ------------------------------------------------------------ computing events

    private static int toMin(String t) {
        if (t == null || t.isEmpty()) return -1;
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + (p.length > 1 ? Integer.parseInt(p[1]) : 0);
        } catch (Exception e) {
            return -1;
        }
    }

    /** after 이후(초과) 가장 이른 알림 시각의 이벤트들. 없으면 빈 목록. */
    static List<Event> nextEvents(Context c, long after) {
        SharedPreferences p = WidgetData.prefs(c);
        List<Event> out = new ArrayList<>();
        String leadS = lead(p);
        int lead;
        try {
            lead = Integer.parseInt(leadS);
        } catch (NumberFormatException e) {
            lead = -1;
        }
        boolean end = endOn(p), routine = routineOn(p);
        if (lead < 0 && !end) return out;

        JSONObject m = WidgetData.loadModel(c);
        JSONArray sched = m != null ? m.optJSONArray("sched") : null;
        if (sched == null) return out;

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long best = Long.MAX_VALUE;
        for (int off = 0; off < 8; off++) {
            Calendar day = Calendar.getInstance();
            day.setTimeInMillis(System.currentTimeMillis());
            day.add(Calendar.DAY_OF_MONTH, off);
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            int wd = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7;
            String date = fmt.format(day.getTime());
            long base = day.getTimeInMillis();
            for (int i = 0; i < sched.length(); i++) {
                JSONObject it = sched.optJSONObject(i);
                if (it == null || it.optInt("day", -1) != wd) continue;
                boolean isRoutine = "routine".equals(it.optString("cat"));
                if (isRoutine && !routine) continue;
                int st = toMin(it.optString("start")), en = toMin(it.optString("end"));
                if (st < 0) continue; // 시간 없는 일은 알림 없음
                if (lead >= 0) {
                    long t = base + (st - lead) * 60_000L;
                    if (t > after && t <= best) {
                        if (t < best) { out.clear(); best = t; }
                        out.add(ev(t, true, it, date));
                    }
                }
                // 생활 일정은 '체크'가 없으므로 끝 알림 없음
                if (end && !isRoutine && en > st) {
                    long t = base + en * 60_000L;
                    if (t > after && t <= best) {
                        if (t < best) { out.clear(); best = t; }
                        out.add(ev(t, false, it, date));
                    }
                }
            }
            if (!out.isEmpty()) break; // 이 날에서 찾았으면 다음 날은 볼 필요 없음
        }
        return out;
    }

    private static Event ev(long t, boolean start, JSONObject it, String date) {
        Event e = new Event();
        e.time = t;
        e.isStart = start;
        e.item = it;
        e.date = date;
        return e;
    }

    // ------------------------------------------------------------ alarm

    private static PendingIntent alarmIntent(Context c, long t) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_ALARM).putExtra(EXTRA_T, t);
        return PendingIntent.getBroadcast(c, ALARM_REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 다음 알림 하나를 건다 (이전 것은 덮어씀). */
    static void reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        List<Event> next = nextEvents(c, System.currentTimeMillis() + 1000);
        if (next.isEmpty()) {
            am.cancel(alarmIntent(c, 0));
            return;
        }
        long t = next.get(0).time;
        PendingIntent pi = alarmIntent(c, t);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
    }

    // ------------------------------------------------------------ notification

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "일정 알림", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("일정 시작·끝 시간 알림");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    /** 알람이 울린 시각의 이벤트들을 알림으로 보여준다. checks는 최신 체크 상태(없으면 null). */
    static void show(Context c, List<Event> events) {
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        SharedPreferences p = WidgetData.prefs(c);
        boolean parent = isParent(p);
        String leadS = lead(p);
        String kid = p.getString("childName", "");
        JSONObject m = WidgetData.loadModel(c);
        JSONObject checks = null;
        if (m != null) checks = m.optJSONObject("checks");
        String checksDate = m != null ? m.optString("checksDate") : "";

        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        for (Event e : events) {
            String title = e.item.optString("title");
            String range = e.item.optString("start") + "~" + e.item.optString("end");
            String head, body;
            if (e.isStart) {
                if (parent) {
                    head = kid.isEmpty() ? "일정 시작" : kid + " 일정 시작";
                    body = title + " · " + range;
                } else {
                    head = "0".equals(leadS) ? "지금 할 시간이에요" : leadS + "분 뒤 시작해요";
                    body = title + " · " + range;
                }
            } else {
                boolean checked = false;
                if (checks != null && e.date.equals(checksDate)) {
                    JSONObject ck = checks.optJSONObject(e.item.optString("id"));
                    checked = ck != null && !ck.optString("s", "").isEmpty();
                }
                if (checked) continue; // 이미 체크했으면 끝 알림 생략
                if (parent) {
                    head = kid.isEmpty() ? "아직 체크 안 한 일정이 있어요" : kid + "가 아직 체크 안 했어요";
                    body = title + " (" + range + ") 시간이 끝났어요.";
                } else {
                    head = "끝날 시간이에요 – 체크했나요?";
                    body = title + " · 다 했어요 / 조금 했어요 / 못 했어요를 눌러 주세요.";
                }
            }
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setColor(0xFF2A78D6)
                    .setContentTitle(head)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openPi)
                    .build();
            int id = (e.date + e.item.optString("id") + (e.isStart ? "s" : "e")).hashCode();
            nm.notify(id, n);
        }
    }

    /** 보호자 폰: 아이가 오늘 할 일을 다 하면 한 번 알림 (Family Link 보너스 안내). */
    static void checkReward(Context c) {
        SharedPreferences p = WidgetData.prefs(c);
        if (!isParent(p)) return;
        JSONObject m = WidgetData.loadModel(c);
        if (m == null) return;
        String date = m.optString("checksDate");
        JSONObject r = m.optJSONObject("reward");
        if (r == null || r.optBoolean("given", false) || !WidgetData.today().equals(date)) return;
        String key = "reward_notified_" + date;
        if (p.getBoolean(key, false)) return;
        p.edit().putBoolean(key, true).apply();
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int min = r.optInt("minutes", 0);
        String kid = p.getString("childName", "");
        String head = (kid.isEmpty() ? "아이가" : kid + "가") + " 오늘 할 일을 다 했어요 🎉";
        String body = min > 0 ? "Family Link에서 보너스 " + min + "분을 주세요 (22:00까지). 누르면 앱이 열리고 'Family Link 열기' 버튼이 있어요."
                : "22시가 지나서 오늘 보너스는 없어요.";
        Intent fl = new Intent(c, MainActivity.class);
        fl.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 2, fl, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF1BAF7A)
                .setContentTitle(head)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(("reward" + date).hashCode(), n);
    }

    static final String FAMILY_LINK_PKG = "com.google.android.apps.kids.familylink";

    /** Family Link 실행 인텐트 (설치 여부 조회 없이 바로 실행 – 옛 안드로이드 호환을 위해 <queries> 미사용) */
    static Intent familyLinkIntent(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setPackage(FAMILY_LINK_PKG);
        return i;
    }

    static void test(Context c) {
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF2A78D6)
                .setContentTitle("알림 테스트")
                .setContentText("일정 알림이 이렇게 와요.")
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build();
        nm.notify(1, n);
    }
}
