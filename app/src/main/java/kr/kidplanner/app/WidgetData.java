package kr.kidplanner.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 위젯용 데이터.
 * - 앱이 열려 있을 때: 웹 화면이 pushWidgetData()로 최신 시간표·체크 상태를 넘겨준다.
 * - 앱이 닫혀 있을 때: Firestore REST API로 직접 받아온다 (익명 로그인 토큰 사용).
 * 저장 형식(w_model): {"sched":[일정...], "checksDate":"yyyy-MM-dd", "checks":{id:{s:..}}, "at":ms}
 */
final class WidgetData {

    private WidgetData() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("planner", Context.MODE_PRIVATE);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------ style

    static String style(SharedPreferences p, int widgetId, String key, String dflt) {
        return p.getString("w" + widgetId + "_" + key, p.getString("wd_" + key, dflt));
    }

    static void saveStyle(Context c, int widgetId, String mode, String theme, boolean hideDone) {
        prefs(c).edit()
                .putString("w" + widgetId + "_mode", mode)
                .putString("w" + widgetId + "_theme", theme)
                .putString("w" + widgetId + "_hide", hideDone ? "1" : "0")
                .putString("wd_mode", mode)
                .putString("wd_theme", theme)
                .putString("wd_hide", hideDone ? "1" : "0")
                .apply();
    }

    /** 앱 설정에서 바꾸면 모든 위젯에 같은 스타일을 적용한다. */
    static void saveDefaultStyleForAll(Context c, int[] ids, String mode, String theme, boolean hideDone) {
        SharedPreferences.Editor e = prefs(c).edit()
                .putString("wd_mode", mode)
                .putString("wd_theme", theme)
                .putString("wd_hide", hideDone ? "1" : "0");
        if (ids != null) {
            for (int id : ids) {
                e.remove("w" + id + "_mode").remove("w" + id + "_theme").remove("w" + id + "_hide");
            }
        }
        e.apply();
    }

    static void forget(Context c, int widgetId) {
        prefs(c).edit()
                .remove("w" + widgetId + "_mode")
                .remove("w" + widgetId + "_theme")
                .remove("w" + widgetId + "_hide")
                .apply();
    }

    static String defaultStyleJson(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        try {
            o.put("mode", p.getString("wd_mode", "list"));
            o.put("theme", p.getString("wd_theme", "light"));
            o.put("hide", p.getString("wd_hide", "0"));
        } catch (JSONException ignored) { }
        return o.toString();
    }

    // ------------------------------------------------------------ model

    static void saveModel(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            o.put("at", System.currentTimeMillis());
            prefs(c).edit().putString("w_model", o.toString()).apply();
        } catch (JSONException ignored) { }
    }

    static JSONObject loadModel(Context c) {
        String s = prefs(c).getString("w_model", "");
        if (s.isEmpty()) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean isConnected(Context c) {
        SharedPreferences p = prefs(c);
        return !p.getString("cfg", "").isEmpty() && !p.getString("fam", "").isEmpty();
    }

    // ------------------------------------------------------------ fetch (Firestore REST)

    /** 네트워크로 오늘 데이터를 받아 저장. 성공하면 true. 메인 스레드에서 부르지 말 것. */
    static boolean fetch(Context c) throws IOException, JSONException {
        SharedPreferences p = prefs(c);
        String cfgS = p.getString("cfg", "");
        String fam = p.getString("fam", "");
        if (cfgS.isEmpty() || fam.isEmpty()) return false;
        JSONObject cfg = new JSONObject(cfgS);
        String apiKey = cfg.optString("apiKey", "");
        String projectId = cfg.optString("projectId", "");
        if (apiKey.isEmpty() || projectId.isEmpty()) return false;

        String token = token(c, apiKey);
        String base = "https://firestore.googleapis.com/v1/projects/" + projectId
                + "/databases/(default)/documents/families/" + fam + "/";
        String t = today();
        JSONObject sch = getDoc(base + "schedule/main", token);
        JSONObject day = getDoc(base + "days/" + t, token);

        JSONObject model = new JSONObject();
        JSONArray items = sch != null ? sch.optJSONArray("items") : null;
        if (items == null) {
            JSONObject old = loadModel(c);
            items = old != null && old.optJSONArray("sched") != null ? old.optJSONArray("sched") : new JSONArray();
        }
        model.put("sched", items);
        model.put("checksDate", t);
        JSONObject checks = day != null ? day.optJSONObject("checks") : null;
        model.put("checks", checks != null ? checks : new JSONObject());
        JSONObject reward = day != null ? day.optJSONObject("reward") : null;
        if (reward != null) model.put("reward", reward);
        JSONObject prev = loadModel(c);   // 밀린 일 목록은 앱이 계산해 넘겨준 값을 유지
        if (prev != null && prev.optJSONArray("carry") != null && t.equals(prev.optString("checksDate"))) model.put("carry", prev.optJSONArray("carry"));
        model.put("at", System.currentTimeMillis());
        p.edit().putString("w_model", model.toString()).apply();
        return true;
    }

    private static String token(Context c, String apiKey) throws IOException, JSONException {
        SharedPreferences p = prefs(c);
        if (!apiKey.equals(p.getString("w_key", ""))) {
            p.edit().remove("w_id").remove("w_refresh").remove("w_exp").putString("w_key", apiKey).apply();
        }
        String id = p.getString("w_id", "");
        long exp = p.getLong("w_exp", 0);
        if (!id.isEmpty() && exp > System.currentTimeMillis() + 60_000) return id;

        String refresh = p.getString("w_refresh", "");
        if (!refresh.isEmpty()) {
            try {
                String body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8");
                String res = http("POST", "https://securetoken.googleapis.com/v1/token?key=" + apiKey,
                        "application/x-www-form-urlencoded", body, null);
                if (res != null) {
                    JSONObject o = new JSONObject(res);
                    storeToken(p, o.getString("id_token"), o.optString("refresh_token", refresh),
                            o.optString("expires_in", "3600"));
                    return o.getString("id_token");
                }
            } catch (IOException ignored) {
                // 갱신 실패 → 새 익명 계정으로 다시 로그인
            }
        }
        String res = http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + apiKey,
                "application/json", "{\"returnSecureToken\":true}", null);
        if (res == null) throw new IOException("sign-up failed");
        JSONObject o = new JSONObject(res);
        storeToken(p, o.getString("idToken"), o.getString("refreshToken"), o.optString("expiresIn", "3600"));
        return o.getString("idToken");
    }

    private static void storeToken(SharedPreferences p, String id, String refresh, String expiresIn) {
        long sec;
        try {
            sec = Long.parseLong(expiresIn);
        } catch (NumberFormatException e) {
            sec = 3600;
        }
        p.edit().putString("w_id", id).putString("w_refresh", refresh)
                .putLong("w_exp", System.currentTimeMillis() + sec * 1000L).apply();
    }

    private static JSONObject getDoc(String url, String token) throws IOException, JSONException {
        String res = http("GET", url, null, null, token);
        if (res == null) return null; // 문서 없음
        JSONObject doc = new JSONObject(res);
        JSONObject fields = doc.optJSONObject("fields");
        return fields == null ? new JSONObject() : decodeFields(fields);
    }

    private static JSONObject decodeFields(JSONObject fields) throws JSONException {
        JSONObject out = new JSONObject();
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, decode(fields.getJSONObject(k)));
        }
        return out;
    }

    private static Object decode(JSONObject v) throws JSONException {
        if (v.has("stringValue")) return v.getString("stringValue");
        if (v.has("integerValue")) {
            try {
                return Long.parseLong(v.getString("integerValue"));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        if (v.has("doubleValue")) return v.getDouble("doubleValue");
        if (v.has("booleanValue")) return v.getBoolean("booleanValue");
        if (v.has("timestampValue")) return v.getString("timestampValue");
        if (v.has("mapValue")) {
            JSONObject f = v.getJSONObject("mapValue").optJSONObject("fields");
            return f == null ? new JSONObject() : decodeFields(f);
        }
        if (v.has("arrayValue")) {
            JSONArray vals = v.getJSONObject("arrayValue").optJSONArray("values");
            JSONArray a = new JSONArray();
            if (vals != null) {
                for (int i = 0; i < vals.length(); i++) a.put(decode(vals.getJSONObject(i)));
            }
            return a;
        }
        return JSONObject.NULL;
    }

    /** 404면 null, 그 밖의 오류는 IOException. */
    private static String http(String method, String url, String contentType, String body, String bearer)
            throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);
            con.setRequestMethod(method);
            if (bearer != null) con.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", contentType);
                OutputStream os = con.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String text = read(is);
            if (code == 404) return null;
            if (code >= 400) throw new IOException("HTTP " + code + ": " + text);
            return text;
        } finally {
            con.disconnect();
        }
    }

    private static String read(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
