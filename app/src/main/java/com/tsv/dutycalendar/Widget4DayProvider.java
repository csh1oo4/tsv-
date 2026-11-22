package com.tsv.dutycalendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * 2×2 「4일」 widget: today .. +3 as a 2×2 grid of date, weekday, badge.
 */
public class Widget4DayProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, build(context));
        } catch (Exception e) {
            Log.w(ShiftWidgetHelper.TAG, "4day widget update failed", e);
        }
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_4day);
        ShiftWidgetHelper.Snapshot snapshot = ShiftWidgetHelper.load(context);
        java.util.List<ShiftWidgetHelper.Day> days =
                ShiftWidgetHelper.daysFrom(snapshot, ShiftWidgetHelper.today(), 4);

        int count = Math.min(days.size(), WidgetIds.DAY4_BADGE.length);
        for (int i = 0; i < count; i++) {
            ShiftWidgetHelper.Day day = days.get(i);
            ShiftWidgetHelper.bindDate(views, WidgetIds.DAY4_DATE[i], day, i == 0);
            ShiftWidgetHelper.bindDow(views, WidgetIds.DAY4_DOW[i], day);
            ShiftWidgetHelper.bindBadge(views, WidgetIds.DAY4_BADGE[i], day.shift);
        }

        views.setOnClickPendingIntent(R.id.w_root, ShiftWidgetHelper.openApp(context, 103));
        return views;
    }
}
