package com.vcbulletin.widget;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/**
 * Only used on Android 11 and older, which can't take the headline rows directly.
 * It reads the bulletin the widget already saved; it doesn't download anything itself.
 */
public class BulletinService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static class Factory implements RemoteViewsFactory {
        private final Context context;
        private final List<BulletinData.Story> stories = new ArrayList<>();

        Factory(Context context) { this.context = context; }

        @Override public void onCreate() { }

        @Override
        public void onDataSetChanged() {
            stories.clear();
            stories.addAll(BulletinData.load(context).stories);
        }

        @Override public int getCount() { return stories.size(); }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position >= stories.size()) {
                return new RemoteViews(context.getPackageName(), R.layout.widget_item);
            }
            return BulletinWidget.buildRow(context, stories.get(position));
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return false; }
        @Override public void onDestroy() { stories.clear(); }
    }
}
