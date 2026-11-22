package com.tsv.dutycalendar;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 2×1 「동료 4일」 widget: a header of the next four dates, then one row per
 * registered team (A/B/C조, up to four) with that team's badge for each day.
 * Rows beyond the registered teams are hidden, so the visible ones share the height.
 */
public class WidgetTeam4DayProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, build(context));
        } catch (Exception e) {
            Log.w(ShiftWidgetHelper.TAG, "team 4day widget update failed", e);
        }
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_team_4day);
        ShiftWidgetHelper.Snapshot snapshot = ShiftWidgetHelper.load(context);
        String todayYmd = ShiftWidgetHelper.today();
        List<ShiftWidgetHelper.Day> dates =
                ShiftWidgetHelper.daysFrom(snapshot, todayYmd, 4);

        ShiftWidgetHelper.Team myTeam = snapshot.team(snapshot.myTeamId);
        views.setTextViewText(R.id.w_t_mine, myTeam == null ? "" : myTeam.name);

        for (int i = 0; i < dates.size() && i < WidgetIds.TEAM_HEADER_DATE.length; i++) {
            ShiftWidgetHelper.Day day = dates.get(i);
            views.setTextViewText(WidgetIds.TEAM_HEADER_DATE[i], day.month + "." + day.day);
            views.setTextColor(WidgetIds.TEAM_HEADER_DATE[i],
                    ShiftWidgetHelper.dateColor(day));
            views.setTextViewText(WidgetIds.TEAM_HEADER_DOW[i], day.dowName);
            views.setTextColor(WidgetIds.TEAM_HEADER_DOW[i],
                    day.holiday || day.isSunday() ? ShiftWidgetHelper.COLOR_HOLIDAY
                            : day.isSaturday() ? ShiftWidgetHelper.COLOR_SATURDAY
                            : ShiftWidgetHelper.COLOR_SUB);
        }

        int rows = WidgetIds.TEAM_ROW.length;
        for (int row = 0; row < rows; row++) {
            ShiftWidgetHelper.Team team = row < snapshot.teams.size()
                    ? snapshot.teams.get(row) : null;
            if (team == null) {
                views.setViewVisibility(WidgetIds.TEAM_ROW[row], android.view.View.GONE);
                continue;
            }
            views.setViewVisibility(WidgetIds.TEAM_ROW[row], android.view.View.VISIBLE);
            views.setTextViewText(WidgetIds.TEAM_NAME[row], team.name);
            views.setTextColor(WidgetIds.TEAM_NAME[row],
                    team.id.equals(snapshot.myTeamId)
                            ? ShiftWidgetHelper.COLOR_TODAY : 0xFF334155);

            for (int i = 0; i < 4; i++) {
                String ymd = ShiftWidgetHelper.plusDays(todayYmd, i);
                ShiftWidgetHelper.Shift shift =
                        ShiftWidgetHelper.shift(snapshot, team.id, ymd);
                ShiftWidgetHelper.bindBadge(views, WidgetIds.TEAM_BADGE[row][i], shift);
            }
        }

        views.setOnClickPendingIntent(R.id.w_root, ShiftWidgetHelper.openApp(context, 104));
        return views;
    }
}
