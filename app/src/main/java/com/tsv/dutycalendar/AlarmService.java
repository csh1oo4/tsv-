package com.tsv.dutycalendar;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import org.json.JSONObject;

/**
 * v2.4 — the ringing half of a shift alarm.
 *
 * AlarmReceiver only wakes up at the alarm time and hands the alarm over; this
 * foreground service is what actually makes noise. That split is the fix for the
 * v2.3.1 symptom where an alarm was silent because Android refused the background
 * Activity start (background activity limits / missing full-screen-intent access):
 * a foreground service with a notification is an allowed start from an exact alarm,
 * so the sound, vibration and wake lock no longer depend on AlarmActivity opening.
 *
 * Sound order of preference:
 *   1. the audio file the user picked from the device (persisted content URI),
 *   2. one of the built-in res/raw alarm_*.wav melodies,
 *   3. the device's default alarm ringtone.
 * Every step falls through to the next if the player cannot open the source, so an
 * alarm never ends up silent because of a missing file or a lost permission.
 *
 * AlarmActivity only shows the screen and the release game; it calls stop(..) here
 * when the user gets through it.
 */
public class AlarmService extends Service {

    private static final String TAG = "TsvDutyAlarm";

    public static final String ACTION_START = "com.tsv.dutycalendar.action.ALARM_START";
    public static final String ACTION_STOP = "com.tsv.dutycalendar.action.ALARM_STOP";

    public static final String EXTRA_ALARM_JSON = "alarmJson";

    private static final int NOTIFICATION_ID = 730100;
    private static final long SNOOZE_DELAY_MS = 5L * 60L * 1000L;
    private static final int SNOOZE_MAX = 3;

    /** The single ringing instance; AlarmActivity asks it whether sound is already up. */
    private static volatile AlarmService instance;
    private static volatile boolean ringing = false;
    private static volatile String ringingId = "";
    private static volatile Runnable stoppedListener;

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int savedStreamVolume = -1;
    private int notificationId = NOTIFICATION_ID;
    private AudioManager focusAudioManager;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    private String alarmJson = "";
    private boolean soundOn = true;
    private boolean vibrateOn = true;
    private int autoOffMinutes = 0;

    private final Runnable autoOffRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "auto-off timer reached");
            stopRinging(false);
        }
    };

    // ------------------------------------------------------------- entry points

    /**
     * v2.5.4 — returns false when Android refused the foreground start, so the caller
     * (AlarmReceiver) can still surface the alarm through a notification instead of
     * failing silently and leaving the user with no ringing at all.
     */
    public static boolean start(Context context, String json) {
        if (context == null) {
            return false;
        }
        try {
            Intent intent = new Intent(context, AlarmService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_ALARM_JSON, json == null ? "{}" : json);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "AlarmService.start failed", e);
            return false;
        }
    }

    public static void stop(Context context, boolean dismissedByUser) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(context, AlarmService.class);
            intent.setAction(ACTION_STOP);
            intent.putExtra("byUser", dismissedByUser);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            // A background foreground-service start can be refused; a plain start is
            // still allowed while the app is in front (the usual case here).
            Log.w(TAG, "AlarmService.stop foreground start failed", e);
            try {
                context.startService(new Intent(context, AlarmService.class)
                        .setAction(ACTION_STOP).putExtra("byUser", dismissedByUser));
            } catch (Exception second) {
                Log.w(TAG, "AlarmService.stop failed", second);
            }
        }
    }

    public static boolean isRinging() {
        return ringing;
    }

    public static String ringingId() {
        return ringingId;
    }

    /** AlarmActivity uses this to close itself when the auto-off timer ends the alarm. */
    public static void setStoppedListener(Runnable listener) {
        stoppedListener = listener;
    }

    // -------------------------------------------------------------- sound preview

    private static MediaPlayer previewPlayer;
    private static final Handler previewHandler = new Handler(Looper.getMainLooper());

    /** Plays one sound once (up to 12 s) for the settings screen's ▶ button. */
    public static void preview(Context context, String json) {
        stopPreview();
        if (context == null) {
            return;
        }
        try {
            JSONObject spec = new JSONObject(json == null ? "{}" : json);
            MediaPlayer mp = openPlayer(context, spec);
            if (mp == null) {
                return;
            }
            int volume = spec.optInt("volume", 80);
            float v = Math.max(0.06f, Math.min(1f, volume / 100f));
            mp.setLooping(false);
            mp.setVolume(v, v);
            mp.start();
            previewPlayer = mp;
            previewHandler.postDelayed(AlarmService::stopPreview, 12000);
        } catch (Exception e) {
            Log.w(TAG, "preview failed", e);
        }
    }

    public static void stopPreview() {
        previewHandler.removeCallbacksAndMessages(null);
        MediaPlayer mp = previewPlayer;
        previewPlayer = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
                // already finished
            }
            try {
                mp.release();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    /**
     * Builds a looping-capable player for one sound spec:
     * {soundType: builtin|device|default, soundId: arcade|trumpet|cuckoo|aurora|reveille, soundUri: content://..}
     */
    private static MediaPlayer openPlayer(Context context, JSONObject spec) {
        String type = spec.optString("soundType", "builtin");
        String soundId = spec.optString("soundId", "arcade");
        String uri = spec.optString("soundUri", "");

        if ("device".equals(type) && !uri.isEmpty()) {
            MediaPlayer mp = tryPlayer(context, Uri.parse(uri));
            if (mp != null) {
                return mp;
            }
            Log.w(TAG, "device sound unreadable, using built-in melody");
        }
        MediaPlayer builtin = tryPlayer(context, builtinUri(context, soundId));
        if (builtin != null) {
            return builtin;
        }
        MediaPlayer fallbackBuiltin = tryPlayer(context, builtinUri(context, "arcade"));
        if (fallbackBuiltin != null) {
            return fallbackBuiltin;
        }
        return tryPlayer(context, defaultAlarmUri());
    }

    private static Uri builtinUri(Context context, String soundId) {
        String name = "aurora";
        if ("arcade".equals(soundId) || "trumpet".equals(soundId)
                || "cuckoo".equals(soundId) || "aurora".equals(soundId)
                || "reveille".equals(soundId)) {
            name = soundId;
        }
        int resId = context.getResources().getIdentifier("alarm_" + name, "raw",
                context.getPackageName());
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + resId);
    }

    private static Uri defaultAlarmUri() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        return uri;
    }

    private static MediaPlayer tryPlayer(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            // v2.5.4 — keep the CPU awake for the whole ringtone, not only while the
            // service holds its own lock; some One UI builds doze the player first.
            try {
                mp.setWakeMode(context.getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            } catch (Exception wakeError) {
                Log.w(TAG, "setWakeMode failed", wakeError);
            }
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(context, uri);
            mp.setOnErrorListener((player, what, extra) -> {
                Log.w(TAG, "MediaPlayer error " + what + "/" + extra);
                return false;
            });
            mp.prepare();
            return mp;
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + uri, e);
            if (mp != null) {
                try {
                    mp.release();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            boolean byUser = intent.getBooleanExtra("byUser", false);
            if (!ringing) {
                // Called while nothing is ringing: Android still expects this started
                // service to call startForeground once before it stops itself.
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(alarmJson));
                } catch (Exception e) {
                    Log.w(TAG, "startForeground (stop path) failed", e);
                }
            }
            stopRinging(byUser);
            return START_NOT_STICKY;
        }

        String json = intent == null ? "" : intent.getStringExtra(EXTRA_ALARM_JSON);
        alarmJson = json == null ? "" : json;

        // v2.5.4 — the notification id must be settled before startForeground(): the
        // stop path cancels nm.cancel(notificationId) and a mismatched id left the
        // 'ringing' notification behind after the alarm was dismissed.
        String incomingId = "";
        try {
            incomingId = new JSONObject(alarmJson).optString("id", "");
        } catch (Exception ignored) {
            // treated as an unknown alarm below
        }
        notificationId = AlarmReceiver.notificationId(incomingId);

        // A foreground service must post its notification right away.
        Notification notification = buildNotification(alarmJson);
        if (!postForeground(notificationId, notification)) {
            Log.w(TAG, "could not enter foreground; ringing anyway");
        }

        // The receiver starts the service and AlarmActivity may start it again as a
        // safety net; only the first one should begin playing.
        if (ringing) {
            String incoming = incomingId;
            if (incoming.isEmpty()) {
                Log.i(TAG, "already ringing, ignoring duplicate start");
                return START_NOT_STICKY;
            }
            if (incoming.equals(ringingId)) {
                Log.i(TAG, "already ringing, ignoring duplicate start");
                return START_NOT_STICKY;
            }
            stopRinging(false);
        }

        startRinging(alarmJson);
        return START_NOT_STICKY;
    }

    /**
     * v2.5.4 — API 29+ (and Android 14's targetSdk 34 rules) need the foreground
     * service type spelled out here; without it the alarm service can be killed or
     * refused right after it starts, which is the '알람이 안 울린다' symptom.
     */
    private boolean postForeground(int id, Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(id, notification);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startForeground(type) failed, retrying plain", e);
            try {
                startForeground(id, notification);
                return true;
            } catch (Exception second) {
                Log.w(TAG, "startForeground failed", second);
                return false;
            }
        }
    }

    private void startRinging(String json) {
        JSONObject spec;
        try {
            spec = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
        } catch (Exception e) {
            spec = new JSONObject();
        }
        soundOn = spec.optBoolean("sound", true);
        vibrateOn = spec.optBoolean("vibrate", true);
        autoOffMinutes = Math.max(0, Math.min(60, spec.optInt("autoOff", 0)));
        ringingId = spec.optString("id", "");
        notificationId = AlarmReceiver.notificationId(ringingId);

        acquireWakeLock();
        requestAlarmAudioFocus();
        boostAlarmStreamIfNeeded();

        if (soundOn) {
            try {
                player = openPlayer(this, spec);
                if (player != null) {
                    int volume = spec.optInt("volume", 80);
                    float v = Math.max(0.06f, Math.min(1f, volume / 100f));
                    player.setLooping(true);
                    player.setVolume(v, v);
                    player.start();
                }
            } catch (Exception e) {
                Log.w(TAG, "alarm sound failed", e);
            }
        }

        if (vibrateOn) {
            startVibration();
        }

        handler.removeCallbacks(autoOffRunnable);
        if (autoOffMinutes > 0) {
            handler.postDelayed(autoOffRunnable, autoOffMinutes * 60L * 1000L);
        }

        ringing = true;
        instance = this;
        Log.i(TAG, "ringing " + ringingId + " sound=" + soundOn + " vibrate=" + vibrateOn
                + " autoOff=" + autoOffMinutes + "min");
    }

    private void startVibration() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager =
                        (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) {
                    vibrator = manager.getDefaultVibrator();
                }
            }
            if (vibrator == null) {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null) {
                return;
            }
            long[] pattern = {0, 700, 400, 700, 900};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                //noinspection deprecation
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    /**
     * v2.5.4 — alarm audio is a request for transient exclusive focus: another app's
     * music ducks instead of the alarm being mixed into silence.
     */
    private void requestAlarmAudioFocus() {
        try {
            focusAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (focusAudioManager == null) {
                return;
            }
            focusListener = change -> Log.d(TAG, "alarm audio focus change " + change);
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
                focusAudioManager.requestAudioFocus(focusRequest);
            } else {
                //noinspection deprecation
                focusAudioManager.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception e) {
            Log.w(TAG, "alarm audio focus request failed", e);
        }
    }

    private void abandonAlarmAudioFocus() {
        try {
            if (focusAudioManager != null) {
                if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                    focusAudioManager.abandonAudioFocusRequest(focusRequest);
                } else if (focusListener != null) {
                    //noinspection deprecation
                    focusAudioManager.abandonAudioFocus(focusListener);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "alarm audio focus abandon failed", e);
        }
        focusRequest = null;
        focusListener = null;
        focusAudioManager = null;
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsv:duty-alarm");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(15L * 60L * 1000L);
        } catch (Exception e) {
            Log.w(TAG, "wake lock failed", e);
        }
    }

    /**
     * If the phone's alarm stream is low the ringtone would stay inaudible, so raise it
     * to 90% while ringing and put the user's level back afterwards.
     */
    private void boostAlarmStreamIfNeeded() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int current = am.getStreamVolume(AudioManager.STREAM_ALARM);
            if (max > 0 && current * 100 < max * 70) {
                savedStreamVolume = current;
                am.setStreamVolume(AudioManager.STREAM_ALARM, Math.round(max * 0.9f), 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "alarm stream boost failed", e);
        }
    }

    private void restoreAlarmStream() {
        if (savedStreamVolume < 0) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedStreamVolume, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "alarm stream restore failed", e);
        }
        savedStreamVolume = -1;
    }

    private void stopRinging(boolean dismissedByUser) {
        handler.removeCallbacks(autoOffRunnable);
        boolean wasRinging = ringing;

        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // already stopped
            }
            try {
                player.release();
            } catch (Exception ignored) {
                // nothing to do
            }
            player = null;
        }
        try {
            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrator cancel failed", e);
        }
        vibrator = null;

        abandonAlarmAudioFocus();
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Exception ignored) {
                // nothing to do
            }
            wakeLock = null;
        }
        restoreAlarmStream();

        ringing = false;
        instance = null;
        ringingId = "";

        if (wasRinging && dismissedByUser) {
            scheduleSnoozeIfNeeded(alarmJson);
        }

        Runnable listener = stoppedListener;
        if (listener != null && !dismissedByUser) {
            handler.post(listener);
        }

        try {
            stopForeground(true);
        } catch (Exception ignored) {
            // nothing to do
        }
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(notificationId);
            }
        } catch (Exception ignored) {
            // nothing to do
        }
        stopSelf();
    }

    /**
     * ''다시 울림'' — after the user turns the alarm off, ring once more five minutes
     * later, up to three times, so a half-asleep dismissal cannot make the shift late.
     */
    private void scheduleSnoozeIfNeeded(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject spec = new JSONObject(json);
            if (!spec.optBoolean("repeat", false)) {
                return;
            }
            int count = spec.optInt("snoozeCount", 0);
            if (count >= SNOOZE_MAX) {
                return;
            }
            spec.put("snoozeCount", count + 1);
            spec.put("id", spec.optString("id", "alarm") + "_r" + (count + 1));
            spec.put("label", spec.optString("label", "근무 알람") + " (다시 울림)");
            AlarmReceiver.scheduleOnce(this, spec.toString(),
                    System.currentTimeMillis() + SNOOZE_DELAY_MS);
            Log.i(TAG, "snooze armed #" + (count + 1));
        } catch (Exception e) {
            Log.w(TAG, "snooze failed", e);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopRinging(false);
        super.onDestroy();
    }

    // ------------------------------------------------------------ notification

    private Notification buildNotification(String json) {
        String label = "근무 알람";
        String id = "";
        boolean gameEnabled = false;
        try {
            JSONObject spec = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            label = spec.optString("label", "근무 알람");
            id = spec.optString("id", "");
            gameEnabled = spec.optBoolean("gameEnabled", false);
        } catch (Exception ignored) {
            // keep defaults
        }

        Intent open = new Intent(this, AlarmActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra(AlarmReceiver.EXTRA_ALARM_JSON, json);
        open.putExtra(AlarmReceiver.EXTRA_FIRED, true);
        PendingIntent openPi = PendingIntent.getActivity(this, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, AlarmReceiver.CHANNEL_ID)
                : new Notification.Builder(this);
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
            builder.setPriority(Notification.PRIORITY_MAX);
        }
        return builder.build();
    }
}
