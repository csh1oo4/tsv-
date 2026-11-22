package com.tsv.dutycalendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.Calendar;

/**
 * 4×4 「한달」 widget: < 2026.11 > header, weekday row, and a static 6×7 grid of
 * date + shift. The arrows broadcast to this receiver, which moves month_offset in
 * tsv_widget_prefs and repaints; offset 0 always means "the month it is now", so the
 * widget follows the calendar even when nobody tapped anything.
 */
public class WidgetMonthProvider extends AppWidgetProvider {

    static final String ACTION_PREV = "com.tsv.dutycalendar.action.WIDGET_MONTH_PREV";
    static final String ACTION_NEXT = "com.tsv.dutycalendar.action.WIDGET_MONTH_NEXT";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, build(context));
        } catch (Exception e) {
            Log.w(ShiftWidgetHelper.TAG, "month widget update failed", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PREV.equals(action) || ACTION_NEXT.equals(action)) {
            int offset = ShiftWidgetHelper.monthOffset(context)
                    + (ACTION_NEXT.equals(action) ? 1 : -1);
            ShiftWidgetHelper.setMonthOffset(context, offset);
            refresh(context);
        }
        super.onReceive(context, intent);
    }

    /** Repaint every placed month widget (used by the arrow broadcasts). */
    private static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }
        try {
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, WidgetMonthProvider.class));
            if (ids != null && ids.length > 0) {
                manager.updateAppWidget(ids, build(context));
            }
        } catch (Exception e) {
            Log.w(ShiftWidgetHelper.TAG, "month widget refresh failed", e);
        }
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_month);
        ShiftWidgetHelper.Snapshot snapshot = ShiftWidgetHelper.load(context);

        int offset = ShiftWidgetHelper.monthOffset(context);
        Calendar first = Calendar.getInstance();
        first.set(Calendar.DAY_OF_MONTH, 1);
        first.add(Calendar.MONTH, offset);
        int year = first.get(Calendar.YEAR);
        int month = first.get(Calendar.MONTH) + 1;

        views.setTextViewText(R.id.w_m_title, year + "." + month);
        views.setOnClickPendingIntent(R.id.w_m_prev,
                ShiftWidgetHelper.broadcast(context, WidgetMonthProvider.class, ACTION_PREV, 201));
        views.setOnClickPendingIntent(R.id.w_m_next,
                ShiftWidgetHelper.broadcast(context, WidgetMonthProvider.class, ACTION_NEXT, 202));

        int lead = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        String today = ShiftWidgetHelper.today();
        String monthYmd = ShiftWidgetHelper.toYmd(first);

        // A 5-week month leaves the static sixth row empty; hide it so the grid fills.
        int weeks = (int) Math.ceil((lead + daysInMonth) / 7.0);
        for (int row = 0; row < WidgetIds.MONTH_ROW.length; row++) {
            views.setViewVisibility(WidgetIds.MONTH_ROW[row],
                    row < weeks ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        for (int cell = 0; cell < WidgetIds.MONTH_DATE.length; cell++) {
            int dayNumber = cell - lead + 1;
            int dateId = WidgetIds.MONTH_DATE[cell];
            int shiftId = WidgetIds.MONTH_SHIFT[cell];

            if (dayNumber < 1 || dayNumber > daysInMonth) {
                views.setTextViewText(dateId, "");
                views.setTextViewText(shiftId, "");
                continue;
            }

            String ymd = ShiftWidgetHelper.plusDays(monthYmd, dayNumber - 1);
            ShiftWidgetHelper.Day day = ShiftWidgetHelper.day(snapshot, ymd, snapshot.myTeamId);
            boolean isToday = ymd.equals(today);

            views.setTextViewText(dateId, String.valueOf(dayNumber));
            views.setTextColor(dateId, isToday
                    ? ShiftWidgetHelper.COLOR_TODAY : ShiftWidgetHelper.dateColor(day));
            if (isToday) {
                views.setInt(dateId, "setBackgroundResource", R.drawable.widget_today_bg);
            }
            ShiftWidgetHelper.bindShiftText(views, shiftId, day.shift, isToday);
        }

        views.setOnClickPendingIntent(R.id.w_root, ShiftWidgetHelper.openApp(context, 105));
        return views;
    }
}
