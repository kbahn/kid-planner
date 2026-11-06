package kr.kidplanner.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.content.res.Configuration;
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


    static int toMin(String t) {
        if (t == null || t.isEmpty()) return -1;
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + (p.length > 1 ? Integer.parseInt(p[1]) : 0);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String dur(int min) {
        if (min < 60) return min + "분";
        int h = min / 60, m = min % 60;
        return m == 0 ? h + "시간" : h + "시간 " + m + "분";
    }

    /** 색 묶음: [글씨, 보조, 시간칸, 흐림, 퍼센트, 다음라벨, 다음제목, 다음시간, 새로고침] */
    static int[] palette(boolean dark) {
        return dark
                ? new int[]{0xFFF1EFE8, 0xFFB4B2A9, 0xFFB4B2A9, 0xFF7A7974, 0xFF5DCAA5, 0xFFB5D4F4, 0xFFE6F1FB, 0xFFE6F1FB, 0xFFB5D4F4}
                : new int[]{0xFF2C2C2A, 0xFF888780, 0xFF5F5E5A, 0xFFB4B2A9, 0xFF0F6E56, 0xFF185FA5, 0xFF042C53, 0xFF0C447C, 0xFF185FA5};
    }

    static void render(Context ctx, AppWidgetManager mgr, int id) {
        SharedPreferences p = WidgetData.prefs(ctx);
        String mode = WidgetData.style(p, id, "mode", "list");
        String theme = WidgetData.style(p, id, "theme", "light");
        boolean hideDone = "1".equals(WidgetData.style(p, id, "hide", "0"));
        boolean dark = "dark".equals(theme);
        int[] c = palette(dark);
        final int FG = c[0], SUB = c[1], TIMEC = c[2], DIM = c[3];

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_today);
        v.setInt(R.id.root, "setBackgroundResource", dark ? R.drawable.widget_bg_dark
                : "clear".equals(theme) ? R.drawable.widget_bg_clear : R.drawable.widget_bg_light);
        v.setInt(R.id.w_next, "setBackgroundResource", dark ? R.drawable.w_next_dark : R.drawable.w_next_light);
        v.setInt(R.id.w_next_icon, "setBackgroundResource", dark ? R.drawable.w_nexticon_dark : R.drawable.w_nexticon_light);
        v.setImageViewResource(R.id.w_next_icon, dark ? R.drawable.ic_clock_dark : R.drawable.ic_clock_light);
        v.setInt(R.id.w_refresh, "setBackgroundResource", dark ? R.drawable.w_pill_dark : R.drawable.w_pill_light);
        v.setTextColor(R.id.w_date, SUB);
        v.setTextColor(R.id.w_title, FG);
        v.setTextColor(R.id.w_pct, c[4]);
        v.setTextColor(R.id.w_count, SUB);
        v.setTextColor(R.id.w_next_label, c[5]);
        v.setTextColor(R.id.w_next_title, c[6]);
        v.setTextColor(R.id.w_next_time, c[7]);
        v.setTextColor(R.id.w_refresh, c[8]);
        v.setTextColor(R.id.w_updated, DIM);
        for (int hid : new int[]{R.id.w_h_status, R.id.w_h_time, R.id.w_h_title, R.id.w_h_at}) v.setTextColor(hid, SUB);

        Calendar cal = Calendar.getInstance();
        int wd = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7; // 월=0 … 일=6
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        String today = WidgetData.today();
        String kid = p.getString("childName", "");
        v.setTextViewText(R.id.w_date, (cal.get(Calendar.MONTH) + 1) + "월 " + cal.get(Calendar.DAY_OF_MONTH) + "일 " + DAYS[wd] + "요일");
        v.setTextViewText(R.id.w_title, kid.isEmpty() ? "오늘 할 일" : kid + " 오늘");

        boolean list = "list".equals(mode);
        v.setViewVisibility(R.id.w_colhdr, list ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.w_list, list ? View.VISIBLE : View.GONE);

        JSONObject m = WidgetData.loadModel(ctx);
        JSONArray sched = m != null ? m.optJSONArray("sched") : null;
        if (sched == null) {
            v.setTextViewText(R.id.w_pct, "");
            v.setTextViewText(R.id.w_count, "");
            v.setProgressBar(R.id.w_progress, 100, 0, false);
            v.setTextViewText(R.id.w_next_label, "시작하기");
            v.setTextViewText(R.id.w_next_title, WidgetData.isConnected(ctx) ? "불러오는 중… 새로고침을 눌러 주세요" : "앱을 열어 처음 설정을 마쳐 주세요");
            v.setTextViewText(R.id.w_next_time, "");
            v.setViewVisibility(R.id.w_colhdr, View.GONE);
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
                int k = Integer.compare(toMin(a.optString("start")), toMin(b.optString("start")));
                return k != 0 ? k : a.optString("title").compareTo(b.optString("title"));
            });

            List<JSONObject> tasks = new ArrayList<>();
            List<String> status = new ArrayList<>();
            List<String> atList = new ArrayList<>();
            int done = 0, partial = 0, miss = 0, open = 0;
            for (JSONObject it : all) {
                if ("routine".equals(it.optString("cat"))) continue;
                JSONObject ck = checks.optJSONObject(it.optString("id"));
                String st = ck != null ? ck.optString("s", "") : "";
                atList.add(ck != null ? hhmm(ck.optString("at", "")) : "");
                if (st.isEmpty()) {
                    int end = toMin(it.optString("end"));
                    st = (end < 0 || nowMin < end) ? "future" : "none";
                }
                if ("done".equals(st)) done++;
                else if ("partial".equals(st)) partial++;
                else if ("miss".equals(st)) miss++;
                else open++;
                tasks.add(it);
                status.add(st);
            }
            int total = tasks.size();
            int pct = total == 0 ? 0 : Math.round((done + partial * 0.5f) * 100f / total);
            v.setTextViewText(R.id.w_pct, total == 0 ? "–" : pct + "%");
            v.setTextViewText(R.id.w_count, total == 0 ? "오늘 할 일 없음" : total + "개 중 " + done + "개 완료");
            v.setProgressBar(R.id.w_progress, 100, pct, false);

            // 지금 / 다음 할 일 (체크할 일을 먼저, 없으면 생활 일정)
            String label = null, title = null, time = "";
            for (int pass = 0; pass < 2 && label == null; pass++) {
                for (JSONObject it : all) {
                    boolean routine = "routine".equals(it.optString("cat"));
                    if (pass == 0 && routine) continue;
                    int st = toMin(it.optString("start")), en = toMin(it.optString("end"));
                    if (st >= 0 && en > st && nowMin >= st && nowMin < en) {
                        label = "지금 할 일 · " + dur(en - nowMin) + " 남음";
                        title = it.optString("title");
                        time = it.optString("start");
                        break;
                    }
                }
            }
            if (label == null) {
                for (JSONObject it : all) {
                    int st = toMin(it.optString("start"));
                    if (st > nowMin) {
                        label = "다음 할 일 · " + dur(st - nowMin) + " 뒤";
                        title = it.optString("title");
                        time = it.optString("start");
                        break;
                    }
                }
            }
            if (label == null) {
                label = "오늘 일정 끝";
                title = open == 0 ? "오늘 할 일을 다 했어요" : "체크 안 한 일 " + open + "개";
                if (!list && miss > 0) title += " · 못 함 " + miss + "개";
            } else if (!list) {
                label += "  ·  남은 할 일 " + open + "개";
            }
            JSONObject rw = m.optJSONObject("reward");
            if (rw != null) {
                int rmin = rw.optInt("minutes", 0);
                label = "오늘 할 일 끝! 🎉";
                title = rmin > 0 ? "보상 " + rmin + "분 (22:00까지)" + (rw.optBoolean("given", false) ? " · 받음" : "") : "22시가 지나 오늘 보상은 없어요";
                time = "";
            }
            v.setTextViewText(R.id.w_next_label, label);
            v.setTextViewText(R.id.w_next_title, title);
            v.setTextViewText(R.id.w_next_time, time);

            if (list) {
                v.setTextViewText(R.id.w_empty, total == 0 ? "오늘은 체크할 일이 없어요" : "남은 할 일이 없어요");
            }
            long at = m.optLong("at", 0);
            v.setTextViewText(R.id.w_updated, at > 0
                    ? new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(at)) + " 업데이트" : "");
        }

        // 위젯 누르면 앱 열기, 새로고침 누르면 새로 받기
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 0, open, flags));
        Intent refresh = new Intent(ctx, TodayWidget.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(ctx, id, refresh, flags));

        // 목록: 위젯 안에서 스크롤되는 ListView (WidgetRowsService가 줄을 만든다)
        v.setTextColor(R.id.w_empty, SUB);
        Intent svc = new Intent(ctx, WidgetRowsService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(android.net.Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.w_listview, svc);
        v.setEmptyView(R.id.w_listview, R.id.w_empty);
        int tflags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        v.setPendingIntentTemplate(R.id.w_listview, PendingIntent.getActivity(ctx, 1, open, tflags));

        mgr.updateAppWidget(id, v);
        mgr.notifyAppWidgetViewDataChanged(id, R.id.w_listview);
    }

    /** 상태 배지: 둥근 색 상자 + 기호 */
    static void applyChip(RemoteViews v, int viewId, String st, boolean anytime, boolean dark) {
        int bg, color;
        String sym;
        switch (st) {
            case "done":
                bg = dark ? R.drawable.w_chip_green_dark : R.drawable.w_chip_green_light;
                color = dark ? 0xFF9FE1CB : 0xFF0F6E56; sym = "✓"; break;
            case "partial":
                bg = dark ? R.drawable.w_chip_amber_dark : R.drawable.w_chip_amber_light;
                color = dark ? 0xFFFAC775 : 0xFF854F0B; sym = "◆"; break;
            case "miss":
                bg = dark ? R.drawable.w_chip_red_dark : R.drawable.w_chip_red_light;
                color = dark ? 0xFFF7C1C1 : 0xFFA32D2D; sym = "✕"; break;
            case "none":
                bg = dark ? R.drawable.w_chip_red_dark : R.drawable.w_chip_red_light;
                color = dark ? 0xFFF7C1C1 : 0xFFA32D2D; sym = "!"; break;
            case "carry":
                bg = dark ? R.drawable.w_chip_amber_dark : R.drawable.w_chip_amber_light;
                color = dark ? 0xFFFAC775 : 0xFF854F0B; sym = "↻"; break;
            default:
                if (anytime) {
                    bg = dark ? R.drawable.w_chip_amber_dark : R.drawable.w_chip_amber_light;
                    color = dark ? 0xFFFAC775 : 0xFF854F0B; sym = "⏳";
                } else {
                    bg = dark ? R.drawable.w_chip_gray_dark : R.drawable.w_chip_gray_light;
                    color = dark ? 0xFFB4B2A9 : 0xFF888780; sym = "•";
                }
        }
        v.setInt(viewId, "setBackgroundResource", bg);
        v.setTextColor(viewId, color);
        v.setTextViewText(viewId, sym);
    }

    /** 체크 시각(ISO, UTC) → 기기 시간 HH:mm */
    static String hhmm(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            java.time.LocalTime t = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalTime();
            return String.format(Locale.US, "%02d:%02d", t.getHour(), t.getMinute());
        } catch (Exception e) {
            return "";
        }
    }

    /** 목록 한 줄 */
    static final class Row {
        String status, time, title, at;
        boolean anytime, done;
    }

    /** 오늘 체크할 일 목록: 끝나지 않은 일 먼저, 완료한 일은 아래 (설정에 따라 숨김). */
    static List<Row> buildRows(Context ctx, int widgetId) {
        List<Row> out = new ArrayList<>();
        SharedPreferences p = WidgetData.prefs(ctx);
        boolean hideDone = "1".equals(WidgetData.style(p, widgetId, "hide", "0"));
        JSONObject m = WidgetData.loadModel(ctx);
        JSONArray sched = m != null ? m.optJSONArray("sched") : null;
        if (sched == null) return out;
        Calendar cal = Calendar.getInstance();
        int wd = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        JSONObject checks = WidgetData.today().equals(m.optString("checksDate")) ? m.optJSONObject("checks") : null;
        if (checks == null) checks = new JSONObject();
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < sched.length(); i++) {
            JSONObject it = sched.optJSONObject(i);
            if (it != null && it.optInt("day", -1) == wd && !"routine".equals(it.optString("cat"))) all.add(it);
        }
        Collections.sort(all, (a, b) -> {
            int k = Integer.compare(toMin(a.optString("start")), toMin(b.optString("start")));
            return k != 0 ? k : a.optString("title").compareTo(b.optString("title"));
        });
        List<Row> doneRows = new ArrayList<>();
        for (JSONObject it : all) {
            JSONObject ck = checks.optJSONObject(it.optString("id"));
            String st = ck != null ? ck.optString("s", "") : "";
            if (st.isEmpty()) {
                int end = toMin(it.optString("end"));
                st = (end < 0 || nowMin < end) ? "future" : "none";
            }
            Row r = new Row();
            r.status = st;
            r.done = "done".equals(st);
            String start = it.optString("start");
            r.anytime = start.isEmpty();
            r.time = r.anytime ? "언제든" : start;
            r.title = it.optString("title");
            r.at = ck != null ? hhmm(ck.optString("at", "")) : "";
            if (r.done) { if (!hideDone) doneRows.add(r); } else out.add(r);
        }
        out.addAll(doneRows);
        // 밀린 일(이월)은 맨 위에
        JSONArray carry = m.optJSONArray("carry");
        if (carry != null && WidgetData.today().equals(m.optString("checksDate"))) {
            List<Row> cr = new ArrayList<>();
            for (int i = 0; i < carry.length(); i++) {
                JSONObject x = carry.optJSONObject(i);
                if (x == null) continue;
                Row r = new Row();
                r.status = "carry";
                r.time = x.optString("from");
                r.title = x.optString("title") + " (밀린 일)";
                r.at = "";
                cr.add(r);
            }
            out.addAll(0, cr);
        }
        return out;
    }
}
