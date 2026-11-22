package com.tsv.dutycalendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * 1×1 「오늘」 widget: today's date, weekday and the big shift badge.
 */
public class Widget1DayProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, build(context));
        } catch (Exception e) {
            Log.w(ShiftWidgetHelper.TAG, "1day widget update failed", e);
        }
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_1day);
        ShiftWidgetHelper.Snapshot snapshot = ShiftWidgetHelper.load(context);
        ShiftWidgetHelper.Day today = ShiftWidgetHelper.day(
                snapshot, ShiftWidgetHelper.today(), snapshot.myTeamId);

        ShiftWidgetHelper.bindDate(views, R.id.w_day_date, today, true);
        ShiftWidgetHelper.bindDow(views, R.id.w_day_dow, today);
        ShiftWidgetHelper.bindBadge(views, R.id.w_day_badge, today.shift);

        views.setOnClickPendingIntent(R.id.w_root, ShiftWidgetHelper.openApp(context, 101));
        return views;
    }
}
