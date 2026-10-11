package com.vcbulletin.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches bulletin.json and turns each story into one row of the widget's list.
 *
 * If the download fails (no signal, say), it shows the last bulletin it saved,
 * so the widget never goes blank.
 */
public class BulletinService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        return new Factory(getApplicationContext(), widgetId);
    }

    static class Story {
        String title, source, link, region, age;
    }

    static class Factory implements RemoteViewsFactory {
        private static final String PREFS = "bulletin";
        private static final String KEY_JSON = "last_json";

        private final Context context;
        private final int widgetId;
        private final List<Story> stories = new ArrayList<>();

        Factory(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override public void onCreate() { }

        // Android runs this in the background whenever the list should reload.
        @Override
        public void onDataSetChanged() {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String json = download();
            boolean fresh = json != null;
            if (fresh) {
                prefs.edit().putString(KEY_JSON, json).apply();
            } else {
                json = prefs.getString(KEY_JSON, null);
            }

            stories.clear();
            String header = "Couldn't load the bulletin. Tap ↻ to retry.";
            if (json != null) {
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
                        stories.add(s);
                    }
                    header = data.optString("date_label") + " · " + stories.size() + " stories"
                            + (fresh ? "" : " · offline");
                } catch (Exception e) {
                    header = "Bulletin file looks broken. Tap ↻ to retry.";
                }
            }

            // Update the date line in the widget's header.
            RemoteViews headerViews = new RemoteViews(context.getPackageName(), R.layout.widget);
            headerViews.setTextViewText(R.id.date, header);
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(widgetId, headerViews);
        }

        private String download() {
            HttpURLConnection conn = null;
            try {
                // The "?t=" part stops the phone from reusing an old copy.
                URL url = new URL(BulletinWidget.BULLETIN_JSON + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setUseCaches(false);
                if (conn.getResponseCode() != 200) return null;
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            } catch (Exception e) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        @Override public int getCount() { return stories.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_item);
            if (position >= stories.size()) return row;
            Story s = stories.get(position);
            row.setTextViewText(R.id.title, s.title);
            String region = "africa".equals(s.region) ? "Africa" : "Global";
            row.setTextViewText(R.id.meta, region + " · " + s.source + " · " + s.age);

            // Fills in the article address for the "open in browser" template.
            Intent fill = new Intent();
            fill.setData(Uri.parse(s.link));
            row.setOnClickFillInIntent(R.id.item, fill);
            return row;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return false; }
        @Override public void onDestroy() { stories.clear(); }
    }
}
