package com.vcbulletin.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * The widget on the home screen.
 *
 * Android calls onUpdate() when the widget is added and then about once an hour.
 * Each time we:
 *   1. draw the widget straight away from the copy saved on the phone, then
 *   2. download the latest bulletin.json in the background and redraw.
 *
 * The headlines are handed to the widget directly, rather than through a
 * separate background service, because some phones block that service.
 */
public class BulletinWidget extends AppWidgetProvider {

    static final String BULLETIN_PAGE = BulletinData.PAGE;
    static final String ACTION_REFRESH = "com.vcbulletin.widget.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        drawAll(context, null);
        refreshInBackground(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            drawAll(context, "Refreshing…");
            refreshInBackground(context);
        }
    }

    /** Downloads without freezing the phone, then redraws every copy of the widget. */
    private void refreshInBackground(Context context) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                BulletinData.refresh(app);
                drawAll(app, null);
            } finally {
                pending.finish();
            }
        }).start();
    }

    static void drawAll(Context context, String headerOverride) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, BulletinWidget.class));
        BulletinData.Bulletin bulletin = BulletinData.load(context);
        for (int id : ids) {
            manager.updateAppWidget(id, buildLayout(context, id, bulletin, headerOverride));
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            manager.notifyAppWidgetViewDataChanged(ids, R.id.list);
        }
    }

    static RemoteViews buildLayout(Context context, int widgetId,
                                   BulletinData.Bulletin bulletin, String headerOverride) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        views.setTextViewText(R.id.date, headerOverride != null ? headerOverride : bulletin.header);

        // 1. The headline list.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 and newer: hand the rows over directly.
            RemoteViews.RemoteCollectionItems.Builder items =
                    new RemoteViews.RemoteCollectionItems.Builder();
            for (int i = 0; i < bulletin.stories.size(); i++) {
                items.addItem(i, buildRow(context, bulletin.stories.get(i)));
            }
            items.setHasStableIds(true).setViewTypeCount(1);
            views.setRemoteAdapter(R.id.list, items.build());
        } else {
            // Older Android: rows come from BulletinService.
            Intent serviceIntent = new Intent(context, BulletinService.class);
            serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.list, serviceIntent);
        }
        views.setEmptyView(R.id.list, R.id.empty);
        views.setTextViewText(R.id.empty, bulletin.stories.isEmpty() && bulletin.header.startsWith("Couldn't")
                ? "No headlines yet. Tap ↻ to retry."
                : "Fetching today's headlines…");

        // 2. Tapping a headline opens that article in the browser.
        //    This is a "template": each row fills in its own web address.
        PendingIntent articleTemplate = PendingIntent.getActivity(
                context, 0, new Intent(Intent.ACTION_VIEW),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.list, articleTemplate);

        // 3. Tapping the header (or the empty message) opens the full bulletin page.
        Intent openPage = new Intent(Intent.ACTION_VIEW, Uri.parse(BULLETIN_PAGE));
        PendingIntent pageIntent = PendingIntent.getActivity(context, 1, openPage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.header, pageIntent);
        views.setOnClickPendingIntent(R.id.empty, pageIntent);

        // 4. The refresh button asks for a fresh download right now.
        Intent refresh = new Intent(context, BulletinWidget.class).setAction(ACTION_REFRESH);
        views.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(
                context, 3, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        return views;
    }

    /** One headline row: title on top, "Africa · Source · time" underneath. */
    static RemoteViews buildRow(Context context, BulletinData.Story s) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_item);
        row.setTextViewText(R.id.title, s.title);
        String region = "africa".equals(s.region) ? "Africa" : "Global";
        row.setTextViewText(R.id.meta, region + " · " + s.source + " · " + s.age);
        Intent fill = new Intent();
        fill.setData(Uri.parse(s.link));
        row.setOnClickFillInIntent(R.id.item, fill);
        return row;
    }
}
