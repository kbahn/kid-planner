package kr.kidplanner.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

/**
 * 일정 체크 – 안드로이드 껍데기.
 * 화면은 assets/index.html(웹 UI)을 앱 안의 WebView로 띄우고,
 * 데이터는 Firebase(Firestore)에 저장한다.
 * 홈 화면 위젯(TodayWidget)에 오늘 진행 상황을 보여준다.
 */
public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/index.html";
    /** 새 버전을 확인할 GitHub 저장소 (공개 저장소여야 확인 가능). */
    static final String UPDATE_REPO = "kbahn/kid-planner";
    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;

    private WebView webView;
    private SharedPreferences prefs;
    private WebViewAssetLoader assetLoader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("planner", Context.MODE_PRIVATE);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(APP_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // 기기가 다크 모드여도 앱 화면은 항상 밝게 (입력칸 글씨가 안 보이는 문제 방지)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) return false;
                // 아이 탭에서는 앱 밖(다른 사이트)으로 나가지 않는다.
                if (!isKidRole()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "Android");

        // 알림 채널 준비 + (안드로이드 13 이상) 알림 권한 요청
        NotifyScheduler.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }


        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        NotifyScheduler.reschedule(this);
        if (webView != null) {
            webView.evaluateJavascript("window.onAndroidResume && window.onAndroidResume()", null);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // 뒤로가기는 웹 화면이 처리한다. 첫 화면에서 "exit"을 돌려주면 앱을 뒤로 보낸다.
        webView.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : ''", value -> {
            if (value != null && value.contains("exit")) {
                moveTaskToBack(true);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private boolean isKidRole() {
        return "kid".equals(prefs.getString("role", ""));
    }

    // ---------------------------------------------------------------- update

    private void js(String fn, String arg) {
        final String code = "window." + fn + " && window." + fn + "(" + org.json.JSONObject.quote(arg) + ")";
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(code, null); });
    }

    private long currentVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkUpdateAsync() {
        new Thread(() -> {
            org.json.JSONObject out = new org.json.JSONObject();
            try {
                long cur = currentVersionCode();
                out.put("current", cur);
                java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.github.com/repos/" + UPDATE_REPO + "/releases/latest").openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(10000);
                con.setRequestProperty("Accept", "application/vnd.github+json");
                con.setRequestProperty("User-Agent", "kid-planner-app");
                int code = con.getResponseCode();
                if (code == 404) {
                    out.put("error", "not_public");
                } else if (code != 200) {
                    out.put("error", "http_" + code);
                } else {
                    java.io.InputStream is = con.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    is.close();
                    org.json.JSONObject rel = new org.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
                    String tag = rel.optString("tag_name", "");
                    long latest = 0;
                    try {
                        latest = Long.parseLong(tag.replaceAll("[^0-9]", ""));
                    } catch (NumberFormatException ignored) { }
                    String url = "";
                    org.json.JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            org.json.JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").endsWith(".apk")) {
                                url = a.optString("browser_download_url");
                                break;
                            }
                        }
                    }
                    out.put("latest", latest);
                    out.put("name", rel.optString("name", "").replace("일정 체크", "").trim());
                    out.put("url", url);
                    out.put("page", rel.optString("html_url"));
                    out.put("newer", latest > cur && !url.isEmpty());
                }
                con.disconnect();
            } catch (Exception e) {
                try { out.put("error", "network"); } catch (Exception ignored) { }
            }
            js("onUpdateInfo", out.toString());
        }).start();
    }

    private void downloadAndInstall(String url, String version) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            js("onUpdateProgress", "need_permission");
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            String name = "update-" + version + ".apk";
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null) {
                java.io.File old = new java.io.File(dir, name);
                if (old.exists()) //noinspection ResultOfMethodCallIgnored
                    old.delete();
            }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(getString(R.string.app_name) + " 업데이트");
            r.setMimeType("application/vnd.android.package-archive");
            r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            downloadId = dm.enqueue(r);
            registerDownloadReceiver();
            js("onUpdateProgress", "downloading");
        } catch (Exception e) {
            js("onUpdateProgress", "error");
        }
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                boolean ok = false;
                android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null) {
                    if (c.moveToFirst()) {
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        ok = st == DownloadManager.STATUS_SUCCESSFUL;
                    }
                    c.close();
                }
                Uri apk = ok ? dm.getUriForDownloadedFile(id) : null;
                if (apk == null) {
                    js("onUpdateProgress", "error");
                    return;
                }
                try {
                    Intent install = new Intent(Intent.ACTION_VIEW);
                    install.setDataAndType(apk, "application/vnd.android.package-archive");
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(install);
                    js("onUpdateProgress", "installing");
                } catch (Exception e) {
                    js("onUpdateProgress", "error");
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, f);
    }

    @Override
    protected void onDestroy() {
        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) { }
            downloadReceiver = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- JS bridge

    /** 웹 화면(index.html)에서 window.Android.xxx() 로 호출. */
    private class Bridge {
        @JavascriptInterface
        public String getPref(String key) {
            return prefs.getString(key, "");
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            if (value == null || value.isEmpty()) prefs.edit().remove(key).apply();
            else prefs.edit().putString(key, value).apply();
            if ("role".equals(key)) NotifyScheduler.reschedule(MainActivity.this);
            if ("fam".equals(key) || "cfg".equals(key)) {
                runOnUiThread(() -> TodayWidget.updateAll(MainActivity.this));
            }
        }

        @JavascriptInterface
        public boolean isApp() {
            return true;
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        // ---------------- 위젯

        /** 웹 화면이 최신 시간표·오늘 체크 상태를 넘겨주면 위젯을 바로 갱신한다. */
        @JavascriptInterface
        public void pushWidgetData(String json) {
            WidgetData.saveModel(MainActivity.this, json);
            NotifyScheduler.reschedule(MainActivity.this);
            runOnUiThread(() -> TodayWidget.updateAll(MainActivity.this));
        }

        @JavascriptInterface
        public int widgetCount() {
            return TodayWidget.ids(MainActivity.this).length;
        }

        @JavascriptInterface
        public boolean canPinWidget() {
            AppWidgetManager mgr = AppWidgetManager.getInstance(MainActivity.this);
            return mgr.isRequestPinAppWidgetSupported();
        }

        /** 홈 화면에 위젯 추가 요청 (런처가 지원하면 확인창이 뜬다). */
        @JavascriptInterface
        public boolean requestPinWidget() {
            AppWidgetManager mgr = AppWidgetManager.getInstance(MainActivity.this);
            if (!mgr.isRequestPinAppWidgetSupported()) return false;
            return mgr.requestPinAppWidget(new ComponentName(MainActivity.this, TodayWidget.class), null, null);
        }

        @JavascriptInterface
        public String getWidgetStyle() {
            return WidgetData.defaultStyleJson(MainActivity.this);
        }

        /** 앱 ⚙ 설정의 '위젯 설정': 모든 위젯에 같은 스타일 적용. */
        @JavascriptInterface
        public void setWidgetStyle(String mode, String theme, boolean hideDone) {
            WidgetData.saveDefaultStyleForAll(MainActivity.this, TodayWidget.ids(MainActivity.this),
                    mode == null ? "list" : mode, theme == null ? "light" : theme, hideDone);
            runOnUiThread(() -> TodayWidget.updateAll(MainActivity.this));
        }

        // ---------------- 앱 업데이트

        @JavascriptInterface
        public void checkUpdate() {
            checkUpdateAsync();
        }

        @JavascriptInterface
        public void downloadUpdate(String url, String version) {
            if (url == null || !url.startsWith("https://github.com/")) {
                js("onUpdateProgress", "error");
                return;
            }
            runOnUiThread(() -> downloadAndInstall(url, version == null ? "" : version));
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || !url.startsWith("https://github.com/")) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) { }
            });
        }

        // ---------------- 일정 알림

        @JavascriptInterface
        public String getNotifySettings() {
            return NotifyScheduler.settingsJson(MainActivity.this);
        }

        @JavascriptInterface
        public void setNotifySettings(String lead, String end, String routine) {
            NotifyScheduler.saveSettings(MainActivity.this, lead, end, routine);
        }

        @JavascriptInterface
        public void testNotify() {
            NotifyScheduler.test(MainActivity.this);
        }

        /** 다음 알림 예정 시각 (ms, 없으면 0). */
        @JavascriptInterface
        public double nextNotifyAt() {
            java.util.List<NotifyScheduler.Event> ev =
                    NotifyScheduler.nextEvents(MainActivity.this, System.currentTimeMillis() + 1000);
            return ev.isEmpty() ? 0 : (double) ev.get(0).time;
        }

        @JavascriptInterface
        public boolean notificationsEnabled() {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void refreshWidgets() {
            WidgetJob.schedulePeriodic(MainActivity.this);
            WidgetJob.refreshNow(MainActivity.this);
        }

        /** 안드로이드 설정 앱 열기 (보호자 PIN 확인 후에만 웹에서 호출). */
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ignored) { }
            });
        }
    }
}
