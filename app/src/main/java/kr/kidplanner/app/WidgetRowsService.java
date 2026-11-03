package kr.kidplanner.app;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** 위젯 목록(ListView)의 각 줄을 만드는 서비스 – 위젯 안에서 스크롤된다. */
public class WidgetRowsService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        return new Factory(getApplicationContext(), id);
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private final int widgetId;
        private List<TodayWidget.Row> rows = new ArrayList<>();
        private boolean dark;

        Factory(Context ctx, int widgetId) {
            this.ctx = ctx;
            this.widgetId = widgetId;
        }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            dark = "dark".equals(WidgetData.style(WidgetData.prefs(ctx), widgetId, "theme", "light"));
            rows = TodayWidget.buildRows(ctx, widgetId);
        }

        @Override public void onDestroy() { rows.clear(); }

        @Override public int getCount() { return rows.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_row);
            if (position < 0 || position >= rows.size()) return v;
            TodayWidget.Row r = rows.get(position);
            int[] c = TodayWidget.palette(dark);
            int fg = c[0], sub = c[1], timeC = c[2], dim = c[3];
            v.setInt(R.id.w_row, "setBackgroundResource",
                    r.done ? 0 : (dark ? R.drawable.w_row_dark : R.drawable.w_row_light));
            TodayWidget.applyChip(v, R.id.w_row_icon, r.status, r.anytime, dark);
            v.setTextViewText(R.id.w_row_time, r.time);
            v.setTextColor(R.id.w_row_time, r.done ? dim : timeC);
            v.setTextViewText(R.id.w_row_title, r.title);
            v.setTextColor(R.id.w_row_title, r.done ? sub : fg);
            v.setTextViewText(R.id.w_row_at, r.at.isEmpty() ? "–" : r.at);
            v.setTextColor(R.id.w_row_at, r.at.isEmpty() ? dim : sub);
            // 줄을 누르면 앱 열기 (위젯의 PendingIntent 템플릿 사용)
            v.setOnClickFillInIntent(R.id.w_row, new Intent());
            return v;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return false; }
    }
}
