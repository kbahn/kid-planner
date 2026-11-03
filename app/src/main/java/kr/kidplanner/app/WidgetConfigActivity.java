package kr.kidplanner.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** 위젯을 홈 화면에 놓을 때 뜨는 '위젯 설정' 화면. */
public class WidgetConfigActivity extends Activity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    private static final int MODE_LIST = 101, MODE_SUMMARY = 102;
    private static final int THEME_LIGHT = 201, THEME_CLEAR = 202, THEME_DARK = 203;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        SharedPreferences p = WidgetData.prefs(this);
        String mode = p.getString("wd_mode", "list");
        String theme = p.getString("wd_theme", "light");
        boolean hide = "1".equals(p.getString("wd_hide", "0"));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        TextView title = text("🧩 위젯 설정", 24, true, 0xFF1D1C1A);
        col.addView(title);
        col.addView(text("홈 화면에 보일 모양을 골라 주세요. 나중에 앱의 ⚙ 설정에서도 바꿀 수 있어요.", 15, false, 0xFF55534E), lp(8));

        if (!WidgetData.isConnected(this)) {
            TextView warn = text("아직 앱 처음 설정을 마치지 않았어요. 위젯을 추가한 뒤 앱을 열어 연결을 마쳐 주세요.", 14, false, 0xFF8A1F1F);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(0xFFFCE8E8);
            gd.setCornerRadius(dp(10));
            warn.setBackground(gd);
            warn.setPadding(dp(12), dp(10), dp(12), dp(10));
            col.addView(warn, lp(14));
        }

        col.addView(text("표시 방식", 17, true, 0xFF1D1C1A), lp(22));
        RadioGroup gMode = new RadioGroup(this);
        gMode.addView(radio(MODE_LIST, "목록형 – 진행률 + 오늘 할 일 목록"));
        gMode.addView(radio(MODE_SUMMARY, "요약형 – 진행률 + 지금 할 일 + 남은 개수"));
        gMode.check("summary".equals(mode) ? MODE_SUMMARY : MODE_LIST);
        col.addView(gMode);

        col.addView(text("배경", 17, true, 0xFF1D1C1A), lp(18));
        RadioGroup gTheme = new RadioGroup(this);
        gTheme.addView(radio(THEME_LIGHT, "흰색"));
        gTheme.addView(radio(THEME_CLEAR, "반투명 (배경화면이 비쳐요)"));
        gTheme.addView(radio(THEME_DARK, "어둡게"));
        gTheme.check("dark".equals(theme) ? THEME_DARK : "clear".equals(theme) ? THEME_CLEAR : THEME_LIGHT);
        col.addView(gTheme);

        CheckBox cHide = new CheckBox(this);
        cHide.setText("완료한 일은 목록에서 숨기기");
        cHide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cHide.setTextColor(0xFF1D1C1A);
        cHide.setChecked(hide);
        col.addView(cHide, lp(18));

        Button save = new Button(this);
        save.setText("위젯 추가");
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        save.setTextColor(0xFFFFFFFF);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1D1C1A);
        bg.setCornerRadius(dp(14));
        save.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        blp.topMargin = dp(26);
        col.addView(save, blp);

        save.setOnClickListener(v -> {
            String m = gMode.getCheckedRadioButtonId() == MODE_SUMMARY ? "summary" : "list";
            int t = gTheme.getCheckedRadioButtonId();
            String th = t == THEME_DARK ? "dark" : t == THEME_CLEAR ? "clear" : "light";
            WidgetData.saveStyle(this, widgetId, m, th, cHide.isChecked());
            TodayWidget.render(this, AppWidgetManager.getInstance(this), widgetId);
            WidgetJob.schedulePeriodic(this);
            WidgetJob.refreshNow(this);
            Intent result = new Intent();
            result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            setResult(RESULT_OK, result);
            finish();
        });

        scroll.setBackgroundColor(0xFFF6F4EF);
        setContentView(scroll);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(topDp);
        return l;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private RadioButton radio(int id, String label) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(label);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        r.setTextColor(0xFF1D1C1A);
        r.setPadding(dp(6), dp(8), 0, dp(8));
        return r;
    }
}
