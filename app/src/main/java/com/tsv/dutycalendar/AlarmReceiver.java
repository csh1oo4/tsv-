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
import android.media.RingtoneManager;
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
 *   - at the alarm time it posts a high-priority alarm notification carrying a
 *     full-screen intent and starts AlarmActivity, which rings, vibrates and asks
 *     the arithmetic questions before it lets go.
 *
 * Exact alarms (API 31+) need SCHEDULE_EXACT_ALARM; when the user has not granted
 * it (or an OEM refuses), the alarm is set with setAndAllowWhileIdle instead so the
 * app never crashes, only fires a little less punctually.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "TsvDutyAlarm";

    public static final String ACTION_FIRE = "com.tsv.dutycalendar.ALARM_FIRE";
    private static final String ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_GAME_ENABLED = "gameEnabled";
    public static final String EXTRA_GAME_DIFFICULTY = "gameDifficulty";
    public static final String EXTRA_GAME_COUNT = "gameCount";
    public static final String EXTRA_SOUND = "sound";
    public static final String EXTRA_VIBRATE = "vibrate";

    private static final String PREFS = "tsv_duty_alarms";
    private static final String KEY_ALARMS = "alarm_list_json";
    private static final String KEY_IDS = "scheduled_ids_json";

    public static final String CHANNEL_ID = "tsv_duty_alarm";

    /** Android 12+ has no SCHEDULE_EXACT_ALARM until the user turns it on; keep a flag. */
    private static boolean exactFallbackLogged = false;

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

    private static PendingIntent pendingIntent(Context context, String id, int flags) {
        return PendingIntent.getBroadcast(context, id.hashCode(), fireIntent(context, id),
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Entry point from the web page: replaces the whole schedule.
     *
     * @param jsonStr JSON array of {id, triggerAtMs, label, gameEnabled,
     *                gameDifficulty, gameCount, sound, vibrate}
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
            out.put("gameDifficulty", raw.optString("gameDifficulty", "easy"));
            out.put("gameCount", Math.max(1, raw.optInt("gameCount", 1)));
            out.put("sound", raw.optBoolean("sound", true));
            out.put("vibrate", raw.optBoolean("vibrate", true));
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
                    PendingIntent pi = pendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
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
        Intent intent = fireIntent(context, id);
        intent.putExtra(EXTRA_ID, id);
        intent.putExtra(EXTRA_LABEL, alarm.optString("label", "근무 알람"));
        intent.putExtra(EXTRA_GAME_ENABLED, alarm.optBoolean("gameEnabled", false));
        intent.putExtra(EXTRA_GAME_DIFFICULTY, alarm.optString("gameDifficulty", "easy"));
        intent.putExtra(EXTRA_GAME_COUNT, Math.max(1, alarm.optInt("gameCount", 1)));
        intent.putExtra(EXTRA_SOUND, alarm.optBoolean("sound", true));
        intent.putExtra(EXTRA_VIBRATE, alarm.optBoolean("vibrate", true));

        PendingIntent pi = PendingIntent.getBroadcast(context, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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
                if (!exactFallbackLogged) {
                    exactFallbackLogged = true;
                    Log.i(TAG, "Exact alarms not permitted; using setAndAllowWhileIdle");
                }
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            // Android 12/13 throws here when SCHEDULE_EXACT_ALARM was revoked between
            // the check and the call; the inexact alarm still rings.
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
        channel.setDescription("교대 근무 출근 알람");
        // AlarmActivity owns the ringtone and the vibration pattern; the channel only
        // has to be loud enough to raise a heads-up + full-screen intent.
        channel.enableVibration(false);
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs);
        } catch (Exception e) {
            Log.w(TAG, "channel sound failed", e);
        }
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

    private static void showNotification(Context context, String id, String label,
                                         boolean gameEnabled, Intent open) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannel(context);

        PendingIntent openPi = PendingIntent.getActivity(context, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(label)
                .setContentText(gameEnabled
                        ? "문제를 모두 풀어야 알람이 꺼져요"
                        : "알람 끄기를 눌러 주세요")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .setFullScreenIntent(openPi, true);
        if (Build.VERSION.SDK_INT < 26) {
            builder.setPriority(Notification.PRIORITY_MAX)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }
        try {
            nm.notify(notificationId(id), builder.build());
        } catch (Exception e) {
            Log.w(TAG, "notify failed (POST_NOTIFICATIONS?)", e);
        }
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

        String id = intent.getStringExtra(EXTRA_ID);
        String label = intent.getStringExtra(EXTRA_LABEL);
        if (label == null || label.trim().isEmpty()) {
            label = "근무 알람";
        }
        boolean gameEnabled = intent.getBooleanExtra(EXTRA_GAME_ENABLED, false);

        Intent activity = new Intent(context, AlarmActivity.class);
        activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.putExtra(EXTRA_ID, id);
        activity.putExtra(EXTRA_LABEL, label);
        activity.putExtra(EXTRA_GAME_ENABLED, gameEnabled);
        activity.putExtra(EXTRA_GAME_DIFFICULTY,
                intent.getStringExtra(EXTRA_GAME_DIFFICULTY));
        activity.putExtra(EXTRA_GAME_COUNT, intent.getIntExtra(EXTRA_GAME_COUNT, 1));
        activity.putExtra(EXTRA_SOUND, intent.getBooleanExtra(EXTRA_SOUND, true));
        activity.putExtra(EXTRA_VIBRATE, intent.getBooleanExtra(EXTRA_VIBRATE, true));

        ensureChannel(context);
        // Both paths carry the same extras, so tapping the notification and the
        // full-screen intent land on the same alarm screen with its game settings.
        showNotification(context, id, label, gameEnabled, activity);

        try {
            context.startActivity(activity);
        } catch (Exception e) {
            // Android 10+ may refuse a background activity start; the full-screen intent
            // notification above is the sanctioned path and takes over there.
            Log.w(TAG, "AlarmActivity start refused", e);
        }
    }
}
