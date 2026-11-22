package com.tsv.dutycalendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * 2×1 「오늘·내일」 widget: two columns, each date + weekday + shift badge.
 */
public class Widget2DayProvider extends AppWidgetProvider {

    private static final int[] DATE_IDS = {R.id.w_2d_date0, R.id.w_2d_date1};
    private static final int[] DOW_IDS = {R.id.w_2d_dow0, R.id.w_2d_dow1};
    private static final int[] BADGE_IDS = {R.id.w_2d_badge0, R.id.w_2d_badge1};

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, build(context));
        } catch (Exception e) {
            Log.w(ShiftWidgetHelper.TAG, "2day widget update failed", e);
        }
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_2day);
        ShiftWidgetHelper.Snapshot snapshot = ShiftWidgetHelper.load(context);
        java.util.List<ShiftWidgetHelper.Day> days =
                ShiftWidgetHelper.daysFrom(snapshot, ShiftWidgetHelper.today(), 2);

        for (int i = 0; i < days.size() && i < DATE_IDS.length; i++) {
            ShiftWidgetHelper.Day day = days.get(i);
            ShiftWidgetHelper.bindDate(views, DATE_IDS[i], day, i == 0);
            ShiftWidgetHelper.bindDow(views, DOW_IDS[i], day);
            ShiftWidgetHelper.bindBadge(views, BADGE_IDS[i], day.shift);
        }

        views.setOnClickPendingIntent(R.id.w_root, ShiftWidgetHelper.openApp(context, 102));
        return views;
    }
}
