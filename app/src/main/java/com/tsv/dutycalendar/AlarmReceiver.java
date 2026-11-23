package com.tsv.dutycalendar;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native side of the web app's shift alarms.
 *
 * The page builds the list of upcoming alarm occurrences and hands it over as
 * JSON through window.AndroidAlarm.scheduleAlarms(...) (see AlarmBridge in
 * MainActivity). This receiver stores that list, keeps AlarmManager in sync with
 * it, and fires when one of them is due:
 *
 *   - boots / app updates wake it up with BOOT_COMPLETED or MY_PACKAGE_REPLACED and
 *     it re-schedules whatever is still in the future, because AlarmManager drops
 *     every alarm across a reboot,
 *   - at the alarm time it starts AlarmService (foreground, so sound and vibration do
 *     not depend on a background Activity start being allowed) and tries to show
 *     AlarmActivity straight away; the service notification carries the same
 *     full-screen intent for when the system prefers that path.
 *
 * v2.4 schedules with AlarmManager.setAlarmClock(): it fires at the exact minute
 * without SCHEDULE_EXACT_ALARM, rings through Doze, survives the OEM battery
 * managers better than setExactAndAllowWhileIdle, and shows the status-bar alarm
 * icon. Older fallbacks are kept in case a device refuses it.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "TsvDutyAlarm";

    public static final String ACTION_FIRE = "com.tsv.dutycalendar.ALARM_FIRE";
    private static final String ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_GAME_ENABLED = "gameEnabled";
    public static final String EXTRA_GAME_DIFFICULTY = "gameDifficulty";
    public static final String EXTRA_GAME_TYPE = "gameType";
    public static final String EXTRA_GAME_COUNT = "gameCount";
    public static final String EXTRA_SOUND = "sound";
    public static final String EXTRA_VIBRATE = "vibrate";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_SOUND_TYPE = "soundType";
    public static final String EXTRA_SOUND_ID = "soundId";
    public static final String EXTRA_SOUND_URI = "soundUri";
    public static final String EXTRA_AUTO_OFF = "autoOff";
    public static final String EXTRA_REPEAT = "repeat";
    public static final String EXTRA_CHEER = "cheer";
    /** The whole occurrence as JSON; AlarmService and AlarmActivity both read this. */
    public static final String EXTRA_ALARM_JSON = "alarmJson";

    /** Shared alarm store; MainActivity also reads it for its permission prompts. */
    public static final String PREFS = "tsv_duty_alarms";
    private static final String KEY_ALARMS = "alarm_list_json";
    private static final String KEY_IDS = "scheduled_ids_json";

    /**
     * v2.4 channel: silent on purpose. The sound now comes from AlarmService with
     * USAGE_ALARM, exactly once; the channel itself only has to be loud (heads-up +
     * full-screen intent). A new id is used because an existing channel keeps its
     * old sound setting forever.
     */
    public static final String CHANNEL_ID = "tsv_duty_alarm_v2";

    // ------------------------------------------------------------------ store

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static AlarmManager alarmManager(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    /** Stable identity for one occurrence; also used as the PendingIntent request code. */
    private static Intent fireIntent(Context context, String id) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_FIRE);
        // filterEquals() matches on action+data+type+class+categories, so a per-id data
        // URI is what keeps two alarms with the same id hash apart from each other.
        intent.setData(Uri.parse("tsvdualarm://alarm/" + Uri.encode(id)));
        return intent;
    }

    /**
     * Entry point from the web page: replaces the whole schedule.
     *
     * @param jsonStr JSON array of {id, triggerAtMs, label, gameEnabled, gameType,
     *                gameDifficulty, gameCount, sound, vibrate, volume, soundType,
     *                soundId, soundUri, autoOff, repeat, cheer}
     */
    public static void apply(Context context, String jsonStr) {
        if (context == null) {
            return;
        }
        JSONArray incoming;
        try {
            incoming = new JSONArray(jsonStr == null ? "[]" : jsonStr);
        } catch (Exception e) {
            Log.w(TAG, "scheduleAlarms: bad JSON", e);
            return;
        }

        AlarmManager am = alarmManager(context);
        if (am == null) {
            return;
        }

        // Whatever was scheduled before is stale: cancel it first.
        cancelScheduled(context);

        SharedPreferences prefs = prefs(context);
        JSONArray store = new JSONArray();
        JSONArray ids = new JSONArray();
        int scheduled = 0;

        for (int i = 0; i < incoming.length(); i++) {
            JSONObject raw = incoming.optJSONObject(i);
            if (raw == null) {
                continue;
            }
            JSONObject normal = normalize(raw, i);
            if (normal == null) {
                continue;
            }
            long at = normal.optLong("triggerAtMs", 0L);
            try {
                store.put(normal);
                ids.put(normal.optString("id"));
                schedule(context, am, normal, at);
                scheduled++;
            } catch (Exception e) {
                Log.w(TAG, "scheduleAlarms: could not schedule " + normal.optString("id"), e);
            }
        }

        prefs.edit()
                .putString(KEY_ALARMS, store.toString())
                .putString(KEY_IDS, ids.toString())
                .apply();

        Log.d(TAG, "scheduleAlarms: " + scheduled + " alarm(s) scheduled, "
                + incoming.length() + " received");
    }

    /** One-off alarm (the 5-second test and the ''다시 울림'' snooze go through here). */
    public static void scheduleOnce(Context context, String alarmJson, long atMs) {
        if (context == null || atMs <= 0L) {
            return;
        }
        AlarmManager am = alarmManager(context);
        if (am == null) {
            return;
        }
        try {
            JSONObject raw = new JSONObject(alarmJson == null ? "{}" : alarmJson);
            raw.put("triggerAtMs", atMs);
            JSONObject normal = normalize(raw, 0);
            if (normal == null) {
                return;
            }
            schedule(context, am, normal, atMs);
            Log.i(TAG, "one-shot alarm armed for " + atMs + " (" + normal.optString("id") + ")");
        } catch (Exception e) {
            Log.w(TAG, "scheduleOnce failed", e);
        }
    }

    /** Copies the page's occurrence into a defensive, fully-populated object. */
    private static JSONObject normalize(JSONObject raw, int index) {
        long at = raw.optLong("triggerAtMs", 0L);
        if (at <= 0L) {
            return null;
        }
        String id = raw.optString("id", "").trim();
        if (id.isEmpty()) {
            id = "alarm_" + index + "_" + at;
        }
        try {
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("triggerAtMs", at);
            out.put("label", raw.optString("label", "근무 알람"));
            out.put("gameEnabled", raw.optBoolean("gameEnabled", false));
            out.put("gameType", raw.optString("gameType", "math"));
            out.put("gameDifficulty", raw.optString("gameDifficulty", "easy"));
            out.put("gameCount", Math.max(1, raw.optInt("gameCount", 1)));
            out.put("sound", raw.optBoolean("sound", true));
            out.put("vibrate", raw.optBoolean("vibrate", true));
            out.put("volume", Math.max(0, Math.min(100, raw.optInt("volume", 80))));
            out.put("soundType", raw.optString("soundType", "builtin"));
            out.put("soundId", raw.optString("soundId", "arcade"));
            out.put("soundUri", raw.optString("soundUri", ""));
            out.put("autoOff", Math.max(0, Math.min(60, raw.optInt("autoOff", 0))));
            out.put("repeat", raw.optBoolean("repeat", false));
            out.put("cheer", raw.optString("cheer", ""));
            out.put("snoozeCount", Math.max(0, raw.optInt("snoozeCount", 0)));
            return out;
        } catch (Exception e) {
            Log.w(TAG, "normalize failed", e);
            return null;
        }
    }

    /** Cancels every PendingIntent of the previously stored list. */
    private static void cancelScheduled(Context context) {
        SharedPreferences prefs = prefs(context);
        AlarmManager am = alarmManager(context);
        String raw = prefs.getString(KEY_IDS, null);
        if (raw != null && am != null) {
            try {
                JSONArray ids = new JSONArray(raw);
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i, "");
                    if (id.isEmpty()) {
                        continue;
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(context, id.hashCode(),
                            fireIntent(context, id), PendingIntent.FLAG_NO_CREATE
                                    | PendingIntent.FLAG_IMMUTABLE);
                    if (pi != null) {
                        am.cancel(pi);
                        pi.cancel();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "cancelScheduled failed", e);
            }
        }
        prefs.edit().remove(KEY_IDS).apply();
    }

    private static void schedule(Context context, AlarmManager am, JSONObject alarm, long at) {
        String id = alarm.optString("id");
        String json = alarm.toString();

        Intent intent = fireIntent(context, id);
        intent.putExtra(EXTRA_ALARM_JSON, json);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_LABEL, alarm.optString("label", "근무 알람"));

        PendingIntent pi = PendingIntent.getBroadcast(context, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // setAlarmClock needs no special permission on any Android version and shows
        // the status-bar alarm icon; its "show" intent opens the alarm screen.
        Intent show = new Intent(context, AlarmActivity.class);
        show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        show.putExtra(EXTRA_ALARM_JSON, json);
        PendingIntent showPi = PendingIntent.getActivity(context, id.hashCode(), show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
            return;
        } catch (Exception e) {
            Log.w(TAG, "setAlarmClock refused, falling back", e);
        }

        boolean exactAllowed = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                exactAllowed = am.canScheduleExactAlarms();
            } catch (Exception e) {
                exactAllowed = false;
            }
        }
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "exact alarm refused, falling back", e);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /**
     * Re-arms everything still in the future from the stored list. Called on boot,
     * on app update, and when the app itself starts.
     */
    public static void rescheduleAll(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String raw = prefs.getString(KEY_ALARMS, null);
        if (raw == null) {
            return;
        }
        AlarmManager am = alarmManager(context);
        if (am == null) {
            return;
        }
        JSONArray ids = new JSONArray();
        int count = 0;
        try {
            JSONArray arr = new JSONArray(raw);
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject alarm = arr.optJSONObject(i);
                if (alarm == null) {
                    continue;
                }
                long at = alarm.optLong("triggerAtMs", 0L);
                if (at <= now) {
                    continue;
                }
                schedule(context, am, alarm, at);
                ids.put(alarm.optString("id"));
                count++;
            }
        } catch (Exception e) {
            Log.w(TAG, "rescheduleAll failed", e);
        }
        prefs.edit().putString(KEY_IDS, ids.toString()).apply();
        Log.d(TAG, "rescheduleAll: " + count + " alarm(s) re-armed");
    }

    // ------------------------------------------------------------ notification

    /** Creates the high-importance alarm channel (API 26+); safe to call repeatedly. */
    public static void ensureChannel(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "근무 알람", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("교대 근무 출근 알람 — 소리와 진동은 알람 서비스가 담당합니다");
        // Sound and vibration belong to AlarmService (USAGE_ALARM), so the channel is
        // silent: that keeps one ringtone, at the alarm volume, exactly once.
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        try {
            channel.setBypassDnd(true);
        } catch (Exception ignored) {
            // Needs notification-policy access; ignore when not granted.
        }
        nm.createNotificationChannel(channel);
    }

    static int notificationId(String id) {
        return id == null ? 730001 : 730000 + (id.hashCode() & 0xFFFF);
    }

    // -------------------------------------------------------------- reception

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            rescheduleAll(context);
            return;
        }
        if (!ACTION_FIRE.equals(action)) {
            return;
        }

        String json = intent.getStringExtra(EXTRA_ALARM_JSON);
        String label = intent.getStringExtra(EXTRA_LABEL);
        if (json == null || json.trim().isEmpty()) {
            json = "{\"id\":\"" + (intent.getStringExtra(EXTRA_ID) == null
                    ? "alarm" : intent.getStringExtra(EXTRA_ID)) + "\",\"label\":"
                    + JSONObject.quote(label == null ? "근무 알람" : label) + "}";
        }

        ensureChannel(context);

        // 1) The sound lives in the foreground service: this start is allowed from an
        //    alarm delivery and does not need a background Activity launch.
        AlarmService.start(context, json);

        // 2) Wake the screen with the alarm screen when the system lets us; the
        //    notification's full-screen intent is the fallback.
        Intent activity = new Intent(context, AlarmActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.putExtra(EXTRA_ALARM_JSON, json);
        try {
            context.startActivity(activity);
        } catch (Exception e) {
            Log.w(TAG, "AlarmActivity start refused; full-screen intent takes over", e);
        }
    }
}
