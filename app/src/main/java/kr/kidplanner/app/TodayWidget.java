package kr.kidplanner.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 홈 화면 위젯: 오늘 진행률, 지금/다음 일정, 할 일 목록. */
public class TodayWidget extends AppWidgetProvider {

    static final String ACTION_REFRESH = "kr.kidplanner.app.WIDGET_REFRESH";
    private static final String[] DAYS = {"월", "화", "수", "목", "금", "토", "일"};
    private static final int[] ROWS = {R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4, R.id.w_row5, R.id.w_row6};
    private static final int[] R_TIME = {R.id.w_row1_time, R.id.w_row2_time, R.id.w_row3_time, R.id.w_row4_time, R.id.w_row5_time, R.id.w_row6_time};
    private static final int[] R_TITLE = {R.id.w_row1_title, R.id.w_row2_title, R.id.w_row3_title, R.id.w_row4_title, R.id.w_row5_title, R.id.w_row6_title};
    private static final int[] R_ICON = {R.id.w_row1_icon, R.id.w_row2_icon, R.id.w_row3_icon, R.id.w_row4_icon, R.id.w_row5_icon, R.id.w_row6_icon};
    private static final int[] R_AT = {R.id.w_row1_at, R.id.w_row2_at, R.id.w_row3_at, R.id.w_row4_at, R.id.w_row5_at, R.id.w_row6_at};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(ctx, mgr, id);
        WidgetJob.schedulePeriodic(ctx);
        WidgetJob.refreshNow(ctx);
    }

    @Override
    public void onEnabled(Context ctx) {
        WidgetJob.schedulePeriodic(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        WidgetJob.cancelAll(ctx);
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        for (int id : ids) WidgetData.forget(ctx, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle newOptions) {
        render(ctx, mgr, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            updateAll(ctx);
            WidgetJob.refreshNow(ctx);
        }
    }

    static int[] ids(Context ctx) {
        return AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, TodayWidget.class));
    }

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        for (int id : ids(ctx)) render(ctx, mgr, id);
    }

    // ------------------------------------------------------------ rendering

    private static int toMin(String t) {
        if (t == null || t.isEmpty()) return -1;
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + (p.length > 1 ? Integer.parseInt(p[1]) : 0);
        } catch (Exception e) {
            return -1;
        }
    }

    static void render(Context ctx, AppWidgetManager mgr, int id) {
        SharedPreferences p = WidgetData.prefs(ctx);
        String mode = WidgetData.style(p, id, "mode", "list");
        String theme = WidgetData.style(p, id, "theme", "light");
        boolean hideDone = "1".equals(WidgetData.style(p, id, "hide", "0"));
        boolean dark = "dark".equals(theme);

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_today);
        int bg = dark ? R.drawable.widget_bg_dark
                : "clear".equals(theme) ? R.drawable.widget_bg_clear : R.drawable.widget_bg_light;
        v.setInt(R.id.root, "setBackgroundResource", bg);
        int fg = dark ? 0xFFFFFFFF : 0xFF1D1C1A;
        int sub = dark ? 0xFFC3C2B7 : 0xFF55534E;
        int muted = dark ? 0xFFA19E97 : 0xFF8D8A83;
        v.setTextColor(R.id.w_title, fg);
        v.setTextColor(R.id.w_count, fg);
        v.setTextColor(R.id.w_now, sub);
        v.setTextColor(R.id.w_updated, muted);
        v.setTextColor(R.id.w_refresh, dark ? 0xFF8DB8EE : 0xFF2A78D6);
        for (int i = 0; i < ROWS.length; i++) {
            v.setViewVisibility(ROWS[i], View.GONE);
            v.setTextColor(R_TIME[i], muted);
            v.setTextColor(R_TITLE[i], fg);
            v.setTextColor(R_AT[i], muted);
        }

        Calendar cal = Calendar.getInstance();
        int wd = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7; // 월=0 … 일=6
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        String today = WidgetData.today();
        String kid = p.getString("childName", "");
        v.setTextViewText(R.id.w_title, (kid.isEmpty() ? "오늘" : kid + " 오늘") + "  " + (cal.get(Calendar.MONTH) + 1) + "/" + cal.get(Calendar.DAY_OF_MONTH) + " (" + DAYS[wd] + ")");

        JSONObject m = WidgetData.loadModel(ctx);
        JSONArray sched = m != null ? m.optJSONArray("sched") : null;
        if (sched == null) {
            v.setTextViewText(R.id.w_count, "");
            v.setProgressBar(R.id.w_progress, 100, 0, false);
            v.setTextViewText(R.id.w_now, WidgetData.isConnected(ctx)
                    ? "불러오는 중… ⟳ 를 눌러 주세요" : "앱을 열어 처음 설정을 마쳐 주세요");
            v.setTextViewText(R.id.w_updated, "");
        } else {
            JSONObject checks = today.equals(m.optString("checksDate")) ? m.optJSONObject("checks") : null;
            if (checks == null) checks = new JSONObject();

            List<JSONObject> all = new ArrayList<>();
            for (int i = 0; i < sched.length(); i++) {
                JSONObject it = sched.optJSONObject(i);
                if (it != null && it.optInt("day", -1) == wd) all.add(it);
            }
            Collections.sort(all, (a, b) -> {
                int c = Integer.compare(toMin(a.optString("start")), toMin(b.optString("start")));
                return c != 0 ? c : a.optString("title").compareTo(b.optString("title"));
            });

            List<JSONObject> tasks = new ArrayList<>();
            List<String> status = new ArrayList<>();
            List<String> atList = new ArrayList<>();
            int done = 0, partial = 0, miss = 0, open = 0;
            for (JSONObject it : all) {
                if ("routine".equals(it.optString("cat"))) continue;
                JSONObject c = checks.optJSONObject(it.optString("id"));
                String s = c != null ? c.optString("s", "") : "";
                atList.add(c != null ? hhmm(c.optString("at", "")) : "");
                if (s.isEmpty()) {
                    int end = toMin(it.optString("end"));
                    s = (end < 0 || nowMin < end) ? "future" : "none";
                }
                if ("done".equals(s)) done++;
                else if ("partial".equals(s)) partial++;
                else if ("miss".equals(s)) miss++;
                else open++;
                tasks.add(it);
                status.add(s);
            }
            int total = tasks.size();
            int pct = total == 0 ? 0 : Math.round((done + partial * 0.5f) * 100f / total);
            v.setTextViewText(R.id.w_count, total == 0 ? "할 일 없음" : "완료 " + done + " / " + total);
            v.setProgressBar(R.id.w_progress, 100, pct, false);

            // 지금 / 다음
            String nowLine = null;
            for (JSONObject it : all) {
                int st = toMin(it.optString("start")), en = toMin(it.optString("end"));
                if (st >= 0 && en > st && nowMin >= st && nowMin < en) {
                    nowLine = "지금   " + it.optString("title") + "  " + it.optString("start") + "~" + it.optString("end");
                    break;
                }
            }
            if (nowLine == null) {
                for (JSONObject it : all) {
                    int st = toMin(it.optString("start"));
                    if (st > nowMin) {
                        nowLine = "다음   " + it.optString("start") + "  " + it.optString("title");
                        break;
                    }
                }
            }
            if (nowLine == null) nowLine = open == 0 ? "오늘 할 일을 다 체크했어요" : "오늘 일정 끝 · 체크 안 한 일 " + open + "개";
            if ("summary".equals(mode)) {
                nowLine += "\n남은 할 일 " + open + "개" + (miss > 0 ? " · 못 함 " + miss + "개" : "") + (partial > 0 ? " · 일부 " + partial + "개" : "");
            }
            v.setTextViewText(R.id.w_now, nowLine);

            if ("list".equals(mode)) {
                List<Integer> idx = new ArrayList<>();
                for (int i = 0; i < tasks.size(); i++) {
                    if (hideDone && "done".equals(status.get(i))) continue;
                    idx.add(i);
                }
                int max = ROWS.length;
                int shown = Math.min(idx.size(), max);
                boolean overflow = idx.size() > max;
                for (int r = 0; r < shown; r++) {
                    v.setViewVisibility(ROWS[r], View.VISIBLE);
                    if (overflow && r == max - 1) {
                        v.setTextViewText(R_TIME[r], "");
                        v.setTextViewText(R_TITLE[r], "외 " + (idx.size() - max + 1) + "개 더 있어요");
                        v.setTextColor(R_TITLE[r], muted);
                        v.setTextViewText(R_ICON[r], "");
                        v.setTextViewText(R_AT[r], "");
                        continue;
                    }
                    int k = idx.get(r);
                    JSONObject it = tasks.get(k);
                    String s = status.get(k);
                    String start = it.optString("start");
                    v.setTextViewText(R_TIME[r], start.isEmpty() ? "언제든" : start);
                    v.setTextViewText(R_TITLE[r], it.optString("title"));
                    v.setTextColor(R_TITLE[r], "done".equals(s) ? muted : fg);
                    v.setTextViewText(R_ICON[r], icon(s));
                    v.setTextViewText(R_AT[r], atList.get(k));
                }
                if (idx.isEmpty() && total > 0) {
                    v.setViewVisibility(ROWS[0], View.VISIBLE);
                    v.setTextViewText(R_TIME[0], "");
                    v.setTextViewText(R_TITLE[0], "남은 할 일이 없어요");
                    v.setTextViewText(R_ICON[0], "");
                    v.setTextViewText(R_AT[0], "");
                }
            }
            long at = m.optLong("at", 0);
            v.setTextViewText(R.id.w_updated, at > 0
                    ? new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(at)) + " 업데이트" : "");
        }

        // 위젯 누르면 앱 열기, ⟳ 누르면 새로고침
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 0, open, flags));
        Intent refresh = new Intent(ctx, TodayWidget.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(ctx, id, refresh, flags));

        mgr.updateAppWidget(id, v);
    }

    /** 상태 이모지 – 모두 같은 크기의 칸(24dp)에 가운데 정렬로 들어간다. */
    private static String icon(String s) {
        switch (s) {
            case "done": return "✅";
            case "partial": return "🔶";
            case "miss": return "❌";
            case "none": return "⬜";
            default: return "⏳";
        }
    }

    /** 체크 시각(ISO, UTC) → 기기 시간 HH:mm */
    private static String hhmm(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            java.time.LocalTime t = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalTime();
            return String.format(Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
        } catch (Exception e) {
            return "";
        }
    }
}
