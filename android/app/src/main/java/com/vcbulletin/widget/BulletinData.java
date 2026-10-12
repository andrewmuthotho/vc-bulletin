package com.vcbulletin.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Everything to do with getting the bulletin: downloading bulletin.json,
 * keeping a saved copy on the phone, and turning it into a list of stories.
 */
final class BulletinData {

    static final String PAGE = "https://andrewmuthotho.github.io/vc-bulletin/";
    static final String JSON_URL = PAGE + "bulletin.json";

    private static final String PREFS = "bulletin";
    private static final String KEY_JSON = "last_json";
    private static final String KEY_STATUS = "last_status";

    static class Story {
        String title, source, link, region, age;
    }

    static class Bulletin {
        String header = "LOADING…";   // the date line, e.g. "SUN 11-10-26 · 9 STORIES"
        String edition = "";           // e.g. "№ 284" (day of the year)
        List<Story> stories = new ArrayList<>();
    }

    private static final TimeZone NAIROBI = TimeZone.getTimeZone("Africa/Nairobi");

    private BulletinData() { }

    /** Downloads the latest bulletin and saves it. Returns true if it worked. */
    static boolean refresh(Context context) {
        String json = download();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (json != null && json.trim().startsWith("{")) {
            prefs.edit().putString(KEY_JSON, json).putString(KEY_STATUS, "ok").apply();
            return true;
        }
        prefs.edit().putString(KEY_STATUS, "offline").apply();
        return false;
    }

    /** Reads whatever bulletin is saved on the phone (may be empty on first run). */
    static Bulletin load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_JSON, null);
        boolean offline = "offline".equals(prefs.getString(KEY_STATUS, ""));
        Bulletin b = new Bulletin();
        if (json == null) {
            b.header = offline ? "OFFLINE · TAP ↻ TO RETRY" : "LOADING…";
            return b;
        }
        try {
            JSONObject data = new JSONObject(json);
            JSONArray items = data.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                Story s = new Story();
                s.title = o.optString("title");
                if (s.title.isEmpty()) continue;   // skip unused slots
                s.source = o.optString("source");
                s.link = o.optString("link");
                s.region = o.optString("region");
                s.age = o.optString("age");
                b.stories.add(s);
            }
            // The bulletin's date comes from its "updated" stamp,
            // e.g. "13:13 EAT, Sun 11 Oct 2026". If that can't be read, use today.
            Date date = parseUpdated(data.optString("updated"));
            Calendar cal = Calendar.getInstance(NAIROBI, Locale.ENGLISH);
            cal.setTime(date);
            b.edition = "№ " + cal.get(Calendar.DAY_OF_YEAR);

            SimpleDateFormat day = new SimpleDateFormat("EEE dd-MM-yy", Locale.ENGLISH);
            day.setTimeZone(NAIROBI);
            int n = b.stories.size();
            b.header = (day.format(date) + " · " + n + (n == 1 ? " STORY" : " STORIES")
                    + (offline ? " · OFFLINE" : "")).toUpperCase(Locale.ENGLISH);
        } catch (Exception e) {
            b.header = "BULLETIN UNREADABLE · TAP ↻";
        }
        return b;
    }

    private static Date parseUpdated(String updated) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm 'EAT', EEE dd MMM yyyy", Locale.ENGLISH);
            f.setTimeZone(NAIROBI);
            Date d = f.parse(updated);
            if (d != null) return d;
        } catch (Exception ignored) { }
        return new Date();
    }

    private static String download() {
        HttpURLConnection conn = null;
        try {
            // The "?t=" part stops the phone from reusing an old copy.
            URL url = new URL(JSON_URL + "?t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
