package com.tsv.dutycalendar;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Full-screen alarm screen for 「TSV 교대달력」.
 *
 * Started by AlarmReceiver (directly and/or through its full-screen intent
 * notification) at the alarm time. It shows the alarm label and, matching the
 * web app's own settings, either
 *
 *   - asks gameCount arithmetic questions one after another (easy = single-digit
 *     addition, normal = two-digit +/-, hard = two-digit × one-digit + two-digit);
 *     every answer has to be right before the sound and vibration stop, or
 *   - offers a plain "알람 끄기" button when the game is off.
 *
 * It plays the device's default TYPE_ALARM ringtone and a repeating vibration
 * pattern for as long as it is ringing. The notification posted by the receiver
 * is cancelled as soon as this screen is up, so the ringtone is heard once.
 */
public class AlarmActivity extends Activity {

    private static final String TAG = "TsvDutyAlarm";
    private static final long REPLAY_INTERVAL_MS = 2500L;

    private String alarmId = "";
    private String label = "근무 알람";
    private boolean gameEnabled = false;
    private String difficulty = "easy";
    private int totalProblems = 1;
    private int solved = 0;
    private boolean soundOn = true;
    private boolean vibrateOn = true;

    private int answer = 0;
    private boolean dismissed = false;

    private Ringtone ringtone;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private TextView questionView;
    private TextView progressView;
    private TextView statusView;
    private EditText answerView;
    private Button primaryButton;

    private final Runnable replayTick = new Runnable() {
        @Override
        public void run() {
            if (dismissed) {
                return;
            }
            try {
                if (ringtone != null && !ringtone.isPlaying()) {
                    ringtone.play();
                }
            } catch (Exception e) {
                Log.w(TAG, "ringtone replay failed", e);
            }
            handler.postDelayed(this, REPLAY_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applyWindowFlags();
        readExtras(getIntent());
        buildUi();
        startRinging();
        if (gameEnabled) {
            nextProblem();
        }

        // The heads-up notification did its job once this screen owns the alarm.
        cancelNotification();
    }

    @SuppressWarnings("deprecation")
    private void applyWindowFlags() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setVolumeControlStream(AudioManager.STREAM_ALARM);
    }

    private void readExtras(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        String id = intent.getStringExtra(AlarmReceiver.EXTRA_ID);
        if (id != null) {
            alarmId = id;
        }
        String name = intent.getStringExtra(AlarmReceiver.EXTRA_LABEL);
        if (name != null && !name.trim().isEmpty()) {
            label = name.trim();
        }
        gameEnabled = intent.getBooleanExtra(AlarmReceiver.EXTRA_GAME_ENABLED, false);
        String diff = intent.getStringExtra(AlarmReceiver.EXTRA_GAME_DIFFICULTY);
        if (diff != null && !diff.trim().isEmpty()) {
            difficulty = diff.trim();
        }
        totalProblems = Math.max(1, Math.min(3, intent.getIntExtra(
                AlarmReceiver.EXTRA_GAME_COUNT, 1)));
        soundOn = intent.getBooleanExtra(AlarmReceiver.EXTRA_SOUND, true);
        vibrateOn = intent.getBooleanExtra(AlarmReceiver.EXTRA_VIBRATE, true);
    }

    // ------------------------------------------------------------------- UI

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout card(int color, float radiusDp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        box.setBackground(bg);
        return box;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#0B1220"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView badge = new TextView(this);
        badge.setText("TSV 교대달력 · 근무 알람");
        badge.setTextColor(Color.parseColor("#93C5FD"));
        badge.setTextSize(12f);
        badge.setLetterSpacing(0.08f);
        badge.setGravity(Gravity.CENTER);
        root.addView(badge, matchWrap());

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(Color.WHITE);
        labelView.setTextSize(26f);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.topMargin = dp(10);
        root.addView(labelView, labelParams);

        TextView clockView = new TextView(this);
        clockView.setText(new SimpleDateFormat("HH:mm", Locale.KOREAN).format(new Date()));
        clockView.setTextColor(Color.parseColor("#BFDBFE"));
        clockView.setTextSize(46f);
        clockView.setTypeface(Typeface.DEFAULT_BOLD);
        clockView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clockParams = matchWrap();
        clockParams.topMargin = dp(2);
        clockParams.bottomMargin = dp(22);
        root.addView(clockView, clockParams);

        LinearLayout body = card(Color.parseColor("#16233A"), 22f);
        body.setPadding(dp(20), dp(22), dp(20), dp(22));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(body, bodyParams);

        questionView = new TextView(this);
        questionView.setTextColor(Color.WHITE);
        questionView.setTextSize(30f);
        questionView.setTypeface(Typeface.DEFAULT_BOLD);
        questionView.setGravity(Gravity.CENTER);
        body.addView(questionView, matchWrap());

        answerView = new EditText(this);
        answerView.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        answerView.setHint("정답 숫자 입력");
        answerView.setHintTextColor(Color.parseColor("#64748B"));
        answerView.setTextColor(Color.WHITE);
        answerView.setTextSize(22f);
        answerView.setTypeface(Typeface.DEFAULT_BOLD);
        answerView.setGravity(Gravity.CENTER);
        answerView.setSingleLine(true);
        answerView.setImeOptions(EditorInfo.IME_ACTION_DONE);
        answerView.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(Color.parseColor("#0F1B2E"));
        fieldBg.setCornerRadius(dp(14));
        fieldBg.setStroke(dp(1), Color.parseColor("#334155"));
        answerView.setBackground(fieldBg);
        LinearLayout.LayoutParams answerParams = matchWrap();
        answerParams.topMargin = dp(18);
        body.addView(answerView, answerParams);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#FCA5A5"));
        statusView.setTextSize(13f);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        body.addView(statusView, statusParams);

        progressView = new TextView(this);
        progressView.setTextColor(Color.parseColor("#93C5FD"));
        progressView.setTextSize(13f);
        progressView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(6);
        body.addView(progressView, progressParams);

        primaryButton = new Button(this);
        primaryButton.setAllCaps(false);
        primaryButton.setTextColor(Color.WHITE);
        primaryButton.setTextSize(16f);
        primaryButton.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setColor(Color.parseColor("#1D4ED8"));
        buttonBg.setCornerRadius(dp(14));
        primaryButton.setBackground(buttonBg);
        primaryButton.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(18);
        body.addView(primaryButton, buttonParams);

        View.OnClickListener listener = view -> onPrimary();
        primaryButton.setOnClickListener(listener);
        answerView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onPrimary();
                return true;
            }
            return false;
        });

        if (gameEnabled) {
            questionView.setText("문제를 준비하는 중…");
            primaryButton.setText("확인");
            answerView.setVisibility(View.VISIBLE);
        } else {
            questionView.setText("알람을 끄려면 아래 버튼을 눌러 주세요");
            questionView.setTextSize(20f);
            primaryButton.setText("알람 끄기");
            answerView.setVisibility(View.GONE);
            progressView.setVisibility(View.GONE);
        }

        setContentView(scroll);
        if (gameEnabled) {
            answerView.requestFocus();
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // ------------------------------------------------------------ the game

    /** Same problem shapes as the web app's preview (makeMathProblem in index.html). */
    private void nextProblem() {
        String question;
        if ("hard".equals(difficulty)) {
            int a = 11 + random.nextInt(19);
            int b = 3 + random.nextInt(9);
            int c = 2 + random.nextInt(15);
            answer = a * b + c;
            question = a + " × " + b + " + " + c + " = ?";
        } else if ("normal".equals(difficulty)) {
            int a = 12 + random.nextInt(35);
            int b = 5 + random.nextInt(25);
            boolean plus = random.nextBoolean();
            answer = plus ? a + b : a - b;
            question = a + (plus ? " + " : " - ") + b + " = ?";
        } else {
            int a = 2 + random.nextInt(9);
            int b = 1 + random.nextInt(9);
            answer = a + b;
            question = a + " + " + b + " = ?";
        }

        questionView.setText(question);
        questionView.setTextSize(30f);
        statusView.setText("");
        answerView.setText("");
        progressView.setText("알람 해제 게임 " + (solved + 1) + " / " + totalProblems
                + "  ·  정답을 입력해야 알람이 꺼져요");
        answerView.requestFocus();
    }

    private void onPrimary() {
        if (dismissed) {
            return;
        }
        if (!gameEnabled) {
            stopAlarm();
            return;
        }
        String raw = answerView.getText() == null ? "" : answerView.getText().toString().trim();
        if (raw.isEmpty()) {
            statusView.setText("정답을 입력해 주세요");
            return;
        }
        int typed;
        try {
            typed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            statusView.setText("숫자로 입력해 주세요");
            answerView.setText("");
            return;
        }
        if (typed != answer) {
            statusView.setText("틀렸어요! 다시 계산해 보세요");
            answerView.setText("");
            return;
        }
        solved++;
        if (solved >= totalProblems) {
            statusView.setText("");
            progressView.setText("알람 해제 성공!");
            stopAlarm();
        } else {
            nextProblem();
        }
    }

    // ----------------------------------------------------------- sound & buzz

    private Vibrator vibrator() {
        if (vibrator != null) {
            return vibrator;
        }
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
        } catch (Exception e) {
            Log.w(TAG, "vibrator unavailable", e);
        }
        return vibrator;
    }

    private void startRinging() {
        if (soundOn) {
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) {
                    uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                }
                ringtone = RingtoneManager.getRingtone(this, uri);
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= 21) {
                        ringtone.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
                    }
                    if (Build.VERSION.SDK_INT >= 28) {
                        ringtone.setLooping(true);
                    }
                    ringtone.play();
                    handler.postDelayed(replayTick, REPLAY_INTERVAL_MS);
                }
            } catch (Exception e) {
                Log.w(TAG, "ringtone failed", e);
            }
        }
        if (vibrateOn) {
            Vibrator v = vibrator();
            if (v != null) {
                try {
                    long[] pattern = {0, 700, 400, 700, 900};
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createWaveform(pattern, 0));
                    } else {
                        //noinspection deprecation
                        v.vibrate(pattern, 0);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "vibrate failed", e);
                }
            }
        }
    }

    private void stopRinging() {
        handler.removeCallbacks(replayTick);
        try {
            if (ringtone != null) {
                ringtone.stop();
            }
        } catch (Exception e) {
            Log.w(TAG, "ringtone stop failed", e);
        }
        ringtone = null;
        try {
            if (vibrator != null) {
                vibrator.cancel();
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrator cancel failed", e);
        }
    }

    private void cancelNotification() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(AlarmReceiver.notificationId(alarmId));
            }
        } catch (Exception e) {
            Log.w(TAG, "notification cancel failed", e);
        }
    }

    private void stopAlarm() {
        dismissed = true;
        stopRinging();
        cancelNotification();
        Toast.makeText(this, "알람을 껐어요", Toast.LENGTH_SHORT).show();
        finish();
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTop: a second alarm while this screen is up replaces what it shows.
        dismissed = true;
        stopRinging();
        dismissed = false;
        solved = 0;
        readExtras(intent);
        if (gameEnabled) {
            nextProblem();
        } else {
            questionView.setText("알람을 끄려면 아래 버튼을 눌러 주세요");
            questionView.setTextSize(20f);
            primaryButton.setText("알람 끄기");
            answerView.setVisibility(View.GONE);
            progressView.setVisibility(View.GONE);
        }
        startRinging();
    }

    @Override
    public void onBackPressed() {
        if (gameEnabled && solved < totalProblems) {
            statusView.setText("문제를 모두 풀어야 알람이 꺼져요");
            return;
        }
        stopAlarm();
    }

    @Override
    protected void onDestroy() {
        dismissed = true;
        stopRinging();
        super.onDestroy();
    }
}
