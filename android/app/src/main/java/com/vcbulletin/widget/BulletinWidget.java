package com.vcbulletin.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/**
 * The widget on the home screen.
 *
 * Android calls onUpdate() when the widget is added and then about once an hour.
 * Each time, we lay out the widget and tell the list to reload, which makes
 * BulletinService fetch the latest bulletin.json from GitHub Pages.
 */
public class BulletinWidget extends AppWidgetProvider {

    static final String BULLETIN_PAGE = "https://andrewmuthotho.github.io/vc-bulletin/";
    static final String BULLETIN_JSON = BULLETIN_PAGE + "bulletin.json";
    static final String ACTION_REFRESH = "com.vcbulletin.widget.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int id : widgetIds) {
            manager.updateAppWidget(id, buildLayout(context, id));
        }
        manager.notifyAppWidgetViewDataChanged(widgetIds, R.id.list);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, BulletinWidget.class));
            RemoteViews loading = new RemoteViews(context.getPackageName(), R.layout.widget);
            loading.setTextViewText(R.id.date, "Refreshing…");
            for (int id : ids) {
                manager.partiallyUpdateAppWidget(id, loading);
            }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.list);
        }
    }

    static RemoteViews buildLayout(Context context, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);

        // 1. The headline list is filled in by BulletinService.
        Intent serviceIntent = new Intent(context, BulletinService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.list, serviceIntent);
        views.setEmptyView(R.id.list, R.id.empty);

        // 2. Tapping a headline opens that article in the browser.
        //    This is a "template": each row fills in its own web address.
        Intent openArticle = new Intent(Intent.ACTION_VIEW);
        PendingIntent articleTemplate = PendingIntent.getActivity(
                context, 0, openArticle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.list, articleTemplate);

        // 3. Tapping the header opens the full bulletin page.
        Intent openPage = new Intent(Intent.ACTION_VIEW, Uri.parse(BULLETIN_PAGE));
        views.setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(
                context, 1, openPage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.empty, PendingIntent.getActivity(
                context, 2, openPage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        // 4. The refresh button asks for a fresh download right now.
        Intent refresh = new Intent(context, BulletinWidget.class).setAction(ACTION_REFRESH);
        views.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(
                context, 3, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        return views;
    }
}
