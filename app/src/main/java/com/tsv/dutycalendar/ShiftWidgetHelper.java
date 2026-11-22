package com.tsv.dutycalendar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Shared data and drawing helpers for the five home-screen widgets.
 *
 * The web app is the only place the shift table is edited, so every time it saves
 * it hands the widget-relevant slice of its state to
 * window.AndroidWidget.syncWidgetData(json) (see MainActivity.WidgetBridge). That
 * JSON — myTeamId, anchorDate, teams[], overrides{} and holidays{} — is kept in
 * SharedPreferences("tsv_widget_prefs", "widget_json"), and the widgets are drawn
 * from it. Before the app has ever run the same preferences are empty, so
 * DEFAULT_* below mirrors DEFAULT_DATA in assets/index.html: A/B/C조, the 12-day
 * 주4→휴2→야4→휴2 pattern, anchor 2026-11-22 and B조 as mine.
 *
 * Shift calculation copies the page's getShift() exactly:
 *   base = pattern[(anchorIndex + (ymd - anchorDate)) % pattern.length]
 *   overrides[ymd][teamId] is either a plain string ("야", "휴" ... — or one of
 *   연차/반차/교육, which are kept as the sub shift on top of the base) or an
 *   object {main, sub}. When the override carries a sub shift the badge shows
 *   that instead, because 연차/교육 is the thing worth seeing at a glance.
 */
final class ShiftWidgetHelper {

    static final String TAG = "TsvWidget";

    static final String PREFS = "tsv_widget_prefs";
    static final String KEY_JSON = "widget_json";
    static final String KEY_MONTH_OFFSET = "month_offset";

    /* ---- defaults, same as DEFAULT_DATA in assets/index.html ---- */
    private static final String DEFAULT_MY_TEAM = "B";
    private static final String DEFAULT_ANCHOR = "2026-11-22";
    private static final String[] DEFAULT_PATTERN =
            {"주", "주", "주", "주", "휴", "휴", "야", "야", "야", "야", "휴", "휴"};
    private static final String[] DEFAULT_TEAM_IDS = {"A", "B", "C"};
    private static final String[] DEFAULT_TEAM_NAMES = {"A조", "B조", "C조"};
    private static final int[] DEFAULT_TEAM_ANCHORS = {3, 7, 11};

    /* ---- palette ---- */
    static final int COLOR_TEXT = 0xFF0F172A;
    static final int COLOR_SUB = 0xFF64748B;
    static final int COLOR_SATURDAY = 0xFF2563EB;
    static final int COLOR_SUNDAY = 0xFFEF4444;
    static final int COLOR_HOLIDAY = 0xFFEF4444;
    static final int COLOR_TODAY = 0xFFB45309;
    static final int COLOR_BADGE_TEXT = 0xFFFFFFFF;

    static final int COLOR_SHIFT_JU = 0xFFB45309;
    static final int COLOR_SHIFT_YA = 0xFF1E293B;
    static final int COLOR_SHIFT_HYU = 0xFFFDBA74;
    static final int COLOR_SHIFT_EDU = 0xFF0284C7;
    static final int COLOR_SHIFT_OFF = 0xFF16A34A;

    private static final String[] WEEKDAYS = {"일", "월", "화", "수", "목", "금", "토"};

    private ShiftWidgetHelper() {
    }

    /* ===================================================== data model */

    static final class Team {
        String id;
        String name;
        int anchorIndex;
        String[] pattern;
    }

    /** What one cell shows: the main shift plus a sub shift such as 연차. */
    static final class Shift {
        final String main;
        final String sub;
        final boolean modified;

        Shift(String main, String sub, boolean modified) {
            this.main = main;
            this.sub = sub;
            this.modified = modified;
        }
    }

    static final class Snapshot {
        String myTeamId = DEFAULT_MY_TEAM;
        String anchorDate = DEFAULT_ANCHOR;
        List<Team> teams = new ArrayList<>();
        JSONObject overrides = new JSONObject();
        JSONObject holidays = new JSONObject();

        Team team(String id) {
            if (id == null) {
                return null;
            }
            for (Team team : teams) {
                if (id.equals(team.id)) {
                    return team;
                }
            }
            return null;
        }
    }

    /** One date prepared for drawing. */
    static final class Day {
        String ymd;
        int day;
        int month;
        int year;
        int dow;
        String dowName;
        boolean holiday;
        String holidayName;
        Shift shift;

        boolean isSunday() {
            return dow == Calendar.SUNDAY;
        }

        boolean isSaturday() {
            return dow == Calendar.SATURDAY;
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Snapshot load(Context context) {
        String raw = null;
        try {
            raw = prefs(context).getString(KEY_JSON, null);
        } catch (Exception e) {
            Log.w(TAG, "widget prefs unreadable", e);
        }
        Snapshot snapshot = parse(raw);
        if (snapshot.teams.isEmpty()) {
            snapshot.teams = defaultTeams();
        }
        if (snapshot.myTeamId == null || snapshot.myTeamId.isEmpty()) {
            snapshot.myTeamId = DEFAULT_MY_TEAM;
        }
        if (snapshot.anchorDate == null || snapshot.anchorDate.isEmpty()) {
            snapshot.anchorDate = DEFAULT_ANCHOR;
        }
        if (snapshot.team(snapshot.myTeamId) == null && !snapshot.teams.isEmpty()) {
            snapshot.myTeamId = snapshot.teams.get(0).id;
        }
        return snapshot;
    }

    private static Snapshot parse(String raw) {
        Snapshot snapshot = new Snapshot();
        if (raw == null || raw.trim().isEmpty()) {
            return snapshot;
        }
        try {
            JSONObject json = new JSONObject(raw);
            snapshot.myTeamId = json.optString("myTeamId", DEFAULT_MY_TEAM);
            snapshot.anchorDate = json.optString("anchorDate", DEFAULT_ANCHOR);

            JSONArray teams = json.optJSONArray("teams");
            if (teams != null) {
                for (int i = 0; i < teams.length(); i++) {
                    JSONObject item = teams.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    Team team = new Team();
                    team.id = item.optString("id", "");
                    team.name = item.optString("name", team.id);
                    team.anchorIndex = item.optInt("anchorIndex", 0);
                    JSONArray pattern = item.optJSONArray("pattern");
                    List<String> values = new ArrayList<>();
                    if (pattern != null) {
                        for (int p = 0; p < pattern.length(); p++) {
                            String value = pattern.optString(p, "");
                            if (!value.isEmpty()) {
                                values.add(value);
                            }
                        }
                    }
                    team.pattern = values.isEmpty()
                            ? DEFAULT_PATTERN.clone() : values.toArray(new String[0]);
                    if (!team.id.isEmpty()) {
                        snapshot.teams.add(team);
                    }
                }
            }

            JSONObject overrides = json.optJSONObject("overrides");
            if (overrides != null) {
                snapshot.overrides = overrides;
            }
            JSONObject holidays = json.optJSONObject("holidays");
            if (holidays != null) {
                snapshot.holidays = holidays;
            }
        } catch (Exception e) {
            Log.w(TAG, "widget json broken, using defaults", e);
            snapshot.teams = defaultTeams();
        }
        return snapshot;
    }

    private static List<Team> defaultTeams() {
        List<Team> teams = new ArrayList<>();
        for (int i = 0; i < DEFAULT_TEAM_IDS.length; i++) {
            Team team = new Team();
            team.id = DEFAULT_TEAM_IDS[i];
            team.name = DEFAULT_TEAM_NAMES[i];
            team.anchorIndex = DEFAULT_TEAM_ANCHORS[i];
            team.pattern = DEFAULT_PATTERN.clone();
            teams.add(team);
        }
        return teams;
    }

    /* =============================================== shift calculation */

    static String baseShift(Team team, String anchorDate, String ymd) {
        if (team == null || team.pattern == null || team.pattern.length == 0) {
            return "휴";
        }
        int length = team.pattern.length;
        int offset = diffDays(ymd, anchorDate);
        int index = ((team.anchorIndex + offset) % length + length) % length;
        return team.pattern[index];
    }

    static Shift shift(Snapshot snapshot, String teamId, String ymd) {
        Team team = snapshot.team(teamId);
        String base = baseShift(team, snapshot.anchorDate, ymd);

        JSONObject day = snapshot.overrides.optJSONObject(ymd);
        if (day == null) {
            return new Shift(base, "", false);
        }
        Object override = day.opt(teamId);
        if (override instanceof JSONObject) {
            JSONObject object = (JSONObject) override;
            String main = object.optString("main", "");
            String sub = object.optString("sub", "");
            if (main.isEmpty()) {
                main = base;
            }
            return new Shift(main, sub, true);
        }
        if (override instanceof String) {
            String value = ((String) override).trim();
            if (value.isEmpty()) {
                return new Shift(base, "", false);
            }
            if (isLeave(value)) {
                return new Shift(base, value, true);
            }
            return new Shift(value, "", !value.equals(base));
        }
        return new Shift(base, "", false);
    }

    static boolean isLeave(String value) {
        return "연차".equals(value) || "반차".equals(value) || "교육".equals(value)
                || "출장".equals(value) || "기타".equals(value);
    }

    /** Days from bDate to aYmd (aYmd - bDate), both yyyy-MM-dd in local time. */
    static int diffDays(String aYmd, String bYmd) {
        long a = parseYmd(aYmd).getTimeInMillis();
        long b = parseYmd(bYmd).getTimeInMillis();
        return (int) Math.round((a - b) / 86400000.0);
    }

    static Calendar parseYmd(String ymd) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        try {
            String[] parts = ymd.trim().split("-");
            calendar.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                    Integer.parseInt(parts[2]), 12, 0, 0);
            calendar.set(Calendar.MILLISECOND, 0);
        } catch (Exception e) {
            calendar.clear();
            calendar.set(2026, Calendar.NOVEMBER, 22, 12, 0, 0);
        }
        return calendar;
    }

    static String toYmd(Calendar calendar) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    static String plusDays(String ymd, int delta) {
        Calendar calendar = parseYmd(ymd);
        calendar.add(Calendar.DAY_OF_MONTH, delta);
        return toYmd(calendar);
    }

    static String today() {
        return toYmd(Calendar.getInstance());
    }

    static Day day(Snapshot snapshot, String ymd, String teamId) {
        Calendar calendar = parseYmd(ymd);
        Day day = new Day();
        day.ymd = toYmd(calendar);
        day.year = calendar.get(Calendar.YEAR);
        day.month = calendar.get(Calendar.MONTH) + 1;
        day.day = calendar.get(Calendar.DAY_OF_MONTH);
        day.dow = calendar.get(Calendar.DAY_OF_WEEK);
        day.dowName = WEEKDAYS[day.dow - 1];
        try {
            day.holidayName = snapshot.holidays.optString(day.ymd, "");
        } catch (Exception e) {
            day.holidayName = "";
        }
        day.holiday = day.holidayName != null && !day.holidayName.isEmpty();
        day.shift = shift(snapshot, teamId, day.ymd);
        return day;
    }

    /** Today, tomorrow, ... as a list. */
    static List<Day> daysFrom(Snapshot snapshot, String fromYmd, int count) {
        List<Day> days = new ArrayList<>(count);
        String ymd = fromYmd;
        for (int i = 0; i < count; i++) {
            days.add(day(snapshot, ymd, snapshot.myTeamId));
            ymd = plusDays(ymd, 1);
        }
        return days;
    }

    /* ==================================================== drawing */

    static int dateColor(Day day) {
        if (day.holiday || day.isSunday()) {
            return COLOR_HOLIDAY;
        }
        if (day.isSaturday()) {
            return COLOR_SATURDAY;
        }
        return COLOR_TEXT;
    }

    static String shortDate(Day day) {
        return day.month + "." + day.day;
    }

    /** "11.23" for today, and for every other day of the month only "23". */
    static void bindDate(RemoteViews views, int id, Day day, boolean today) {
        views.setTextViewText(id, day.month + "." + day.day);
        views.setTextColor(id, today ? COLOR_TODAY : dateColor(day));
    }

    static void bindDow(RemoteViews views, int id, Day day) {
        views.setTextViewText(id, day.dowName);
        views.setTextColor(id, day.holiday || day.isSunday() ? COLOR_HOLIDAY
                : day.isSaturday() ? COLOR_SATURDAY : COLOR_SUB);
    }

    /**
     * Circular badge: 주/주특 amber, 야/야특 dark slate, 교육 blue, 연차·반차 green,
     * 휴 (and anything unknown) no circle at all, just light orange text.
     */
    static void bindBadge(RemoteViews views, int id, Shift shift) {
        String label = shift == null ? "" : shift.sub;
        String type;
        if (label != null && !label.isEmpty()) {
            type = label;
        } else {
            label = shift == null || shift.main == null || shift.main.isEmpty()
                    ? "휴" : shift.main;
            type = label;
        }

        int background;
        int text;
        switch (type) {
            case "주":
            case "주특":
                background = R.drawable.widget_badge_amber;
                text = COLOR_BADGE_TEXT;
                break;
            case "야":
            case "야특":
                background = R.drawable.widget_badge_dark;
                text = COLOR_BADGE_TEXT;
                break;
            case "교육":
                background = R.drawable.widget_badge_blue;
                text = COLOR_BADGE_TEXT;
                break;
            case "연차":
            case "반차":
                background = R.drawable.widget_badge_green;
                text = COLOR_BADGE_TEXT;
                break;
            default:
                background = R.drawable.widget_badge_none;
                text = COLOR_SHIFT_HYU;
                break;
        }
        views.setInt(id, "setBackgroundResource", background);
        views.setTextColor(id, text);
        views.setTextViewText(id, label);
    }

    /** Compact 월간 근무 mark: no circle, just coloured bold text. */
    static void bindShiftText(RemoteViews views, int id, Shift shift, boolean today) {
        String label = shift == null ? "휴" : shift.sub;
        String type;
        if (label == null || label.isEmpty()) {
            label = shift == null || shift.main == null || shift.main.isEmpty()
                    ? "휴" : shift.main;
            type = label;
        } else {
            type = label;
        }
        int color;
        switch (type) {
            case "주":
            case "주특":
                color = COLOR_SHIFT_JU;
                break;
            case "야":
            case "야특":
                color = COLOR_SHIFT_YA;
                break;
            case "교육":
                color = COLOR_SHIFT_EDU;
                break;
            case "연차":
            case "반차":
                color = COLOR_SHIFT_OFF;
                break;
            default:
                color = COLOR_SHIFT_HYU;
                break;
        }
        views.setTextViewText(id, label);
        views.setTextColor(id, today ? COLOR_TODAY : color);
    }

    /* ================================================== intents */

    static PendingIntent openApp(Context context, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent broadcast(Context context, Class<?> receiver, String action,
                                   int requestCode) {
        Intent intent = new Intent(context, receiver);
        intent.setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /* ============================================== refresh plumbing */

    /** Every provider, so one sync repaints all five widget kinds at once. */
    private static final Class<?>[] PROVIDERS = {
            Widget1DayProvider.class,
            Widget2DayProvider.class,
            Widget4DayProvider.class,
            WidgetTeam4DayProvider.class,
            WidgetMonthProvider.class
    };

    /**
     * Asked for by the plan: after the page hands over new data, each provider gets
     * an explicit ACTION_APPWIDGET_UPDATE broadcast carrying its own widget ids, which
     * is exactly what the launcher's own update broadcast looks like.
     */
    static void refreshAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }
        for (Class<?> provider : PROVIDERS) {
            try {
                ComponentName component = new ComponentName(context, provider);
                int[] ids = manager.getAppWidgetIds(component);
                if (ids == null || ids.length == 0) {
                    continue;
                }
                Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                intent.setComponent(component);
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
                context.sendBroadcast(intent);
            } catch (Exception e) {
                Log.w(TAG, "refresh failed for " + provider.getSimpleName(), e);
            }
        }
    }

    /** Month offset the < > arrows write; 0 = the current month. */
    static int monthOffset(Context context) {
        try {
            return prefs(context).getInt(KEY_MONTH_OFFSET, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static void setMonthOffset(Context context, int offset) {
        prefs(context).edit().putInt(KEY_MONTH_OFFSET, offset).apply();
    }
}
