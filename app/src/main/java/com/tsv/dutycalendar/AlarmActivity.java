package com.tsv.dutycalendar;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Full-screen alarm screen for 「TSV 교대달력」.
 *
 * v2.4: the ringtone and the vibration live in AlarmService, so this screen does not
 * have to open for the alarm to be heard — it is the face of the alarm, not its
 * voice. It shows the label, the time, the user's own encouragement line, and the
 * release game the page chose:
 *
 *   math    — 사칙연산 (easy +, normal +/-, hard with ×)
 *   typing  — 따라쓰기 (type the phrase exactly; the cheer message is the phrase)
 *   numbers — 숫자 순서 터치 (tap 1..N in order on a shuffled grid)
 *   colors  — 색깔 순서 터치 (tap the palette in the shown order)
 *   random  — one of the four, picked when the alarm fires
 *
 * Getting through the game calls AlarmService.stop(..), which also cancels the
 * notification. With the game off the screen is a plain "알람 끄기" button.
 */
public class AlarmActivity extends Activity {

    private static final String TAG = "TsvDutyAlarm";

    private static final String[] COLOR_NAMES = {"빨강", "주황", "노랑", "초록", "파랑", "보라"};
    private static final int[] COLOR_VALUES = {
            0xFFEF4444, 0xFFF97316, 0xFFEAB308, 0xFF22C55E, 0xFF3B82F6, 0xFFA855F7
    };

    private String alarmId = "";
    private String label = "근무 알람";
    private boolean gameEnabled = false;
    private String gameType = "math";
    private String difficulty = "easy";
    private String cheer = "";
    private String alarmJson = "";
    /** true only when the alarm is due; the status-bar icon opens this screen with false. */
    private boolean firedFromAlarm = false;

    private String activeType = "math";
    private int totalProblems = 1;
    private int solved = 0;
    private boolean dismissed = false;

    // math
    private int answer = 0;
    // typing
    private String phrase = "";
    // numbers
    private int nextNumber = 1;
    private int numberCount = 9;
    // colors
    private final List<Integer> colorTarget = new ArrayList<>();
    private int colorStep = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private TextView badgeView;
    private TextView cheerView;
    private TextView questionView;
    private TextView hintView;
    private TextView statusView;
    private TextView progressView;
    private EditText answerView;
    private GridLayout gridView;
    private Button primaryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applyWindowFlags();
        readExtras(getIntent());
        buildUi();
        AlarmService.setStoppedListener(this::onAlarmStoppedElsewhere);

        // The receiver starts the service before it opens this screen. Starting it here
        // as well only covers a screen opened from the notification after the service had
        // been cleaned up — never for the status-bar alarm icon (fired == false), which
        // would otherwise ring an alarm hours early.
        if (!AlarmService.isRinging() && firedFromAlarm) {
            AlarmService.start(this, alarmJson);
        }
        if (gameEnabled && !AlarmService.isRinging() && !firedFromAlarm) {
            showScheduledInfo();
        } else if (gameEnabled) {
            startChallenge();
        }
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
    }

    private void readExtras(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        String raw = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_JSON);
        JSONObject spec = null;
        if (raw != null && !raw.trim().isEmpty()) {
            alarmJson = raw;
            try {
                spec = new JSONObject(raw);
            } catch (Exception e) {
                Log.w(TAG, "bad alarm json", e);
            }
        }
        firedFromAlarm = intent.getBooleanExtra(AlarmReceiver.EXTRA_FIRED, false);
        if (spec != null) {
            alarmId = spec.optString("id", alarmId);
            String name = spec.optString("label", "");
            if (!name.trim().isEmpty()) {
                label = name.trim();
            }
            gameEnabled = spec.optBoolean("gameEnabled", false);
            gameType = spec.optString("gameType", "math");
            difficulty = spec.optString("gameDifficulty", "easy");
            cheer = spec.optString("cheer", "");
            totalProblems = Math.max(1, Math.min(3, spec.optInt("gameCount", 1)));
        }
        if (gameType == null || gameType.trim().isEmpty()) {
            gameType = "math";
        }
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
        root.setPadding(dp(22), dp(44), dp(22), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        badgeView = new TextView(this);
        badgeView.setText("TSV 교대달력 · 근무 알람");
        badgeView.setTextColor(Color.parseColor("#93C5FD"));
        badgeView.setTextSize(12f);
        badgeView.setLetterSpacing(0.08f);
        badgeView.setGravity(Gravity.CENTER);
        root.addView(badgeView, matchWrap());

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
        clockView.setTextSize(44f);
        clockView.setTypeface(Typeface.DEFAULT_BOLD);
        clockView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clockParams = matchWrap();
        clockParams.topMargin = dp(2);
        root.addView(clockView, clockParams);

        cheerView = new TextView(this);
        cheerView.setText(cheer.trim().isEmpty() ? "" : cheer.trim());
        cheerView.setTextColor(Color.parseColor("#FDE68A"));
        cheerView.setTextSize(15f);
        cheerView.setTypeface(Typeface.DEFAULT_BOLD);
        cheerView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cheerParams = matchWrap();
        cheerParams.bottomMargin = dp(16);
        cheerParams.topMargin = dp(4);
        cheerView.setVisibility(cheer.trim().isEmpty() ? View.GONE : View.VISIBLE);
        root.addView(cheerView, cheerParams);

        LinearLayout body = card(Color.parseColor("#16233A"), 22f);
        body.setPadding(dp(18), dp(20), dp(18), dp(20));
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        questionView = new TextView(this);
        questionView.setTextColor(Color.WHITE);
        questionView.setTextSize(28f);
        questionView.setTypeface(Typeface.DEFAULT_BOLD);
        questionView.setGravity(Gravity.CENTER);
        body.addView(questionView, matchWrap());

        hintView = new TextView(this);
        hintView.setTextColor(Color.parseColor("#94A3B8"));
        hintView.setTextSize(12f);
        hintView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(6);
        body.addView(hintView, hintParams);

        answerView = new EditText(this);
        answerView.setHint("정답 입력");
        answerView.setHintTextColor(Color.parseColor("#64748B"));
        answerView.setTextColor(Color.WHITE);
        answerView.setTextSize(21f);
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
        answerParams.topMargin = dp(14);
        body.addView(answerView, answerParams);

        gridView = new GridLayout(this);
        gridView.setColumnCount(3);
        gridView.setAlignmentMode(GridLayout.ALIGN_MARGINS);
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.topMargin = dp(16);
        body.addView(gridView, gridParams);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#FCA5A5"));
        statusView.setTextSize(13f);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        body.addView(statusView, statusParams);

        progressView = new TextView(this);
        progressView.setTextColor(Color.parseColor("#93C5FD"));
        progressView.setTextSize(12f);
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
        buttonParams.topMargin = dp(16);
        body.addView(primaryButton, buttonParams);
        primaryButton.setOnClickListener(view -> onPrimary());

        if (gameEnabled) {
            questionView.setText("미션을 준비하는 중…");
            primaryButton.setText("확인");
        } else {
            questionView.setText("알람을 끄려면 아래 버튼을 눌러 주세요");
            questionView.setTextSize(20f);
            primaryButton.setText("알람 끄기");
            answerView.setVisibility(View.GONE);
            gridView.setVisibility(View.GONE);
            hintView.setVisibility(View.GONE);
            progressView.setVisibility(View.GONE);
        }

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // ------------------------------------------------------------ the games

    /** 예약된 알람을 상태바 아이콘에서 열었을 때: 소리 없이 정보만 보여준다. */
    private void showScheduledInfo() {
        questionView.setText("예약된 알람입니다");
        questionView.setTextSize(22f);
        hintView.setText("예정된 시각에 알람이 울립니다.");
        answerView.setVisibility(View.GONE);
        gridView.setVisibility(View.GONE);
        progressView.setVisibility(View.GONE);
        primaryButton.setText("닫기");
        primaryButton.setOnClickListener(v -> finish());
    }

    private void startChallenge() {
        solved = 0;
        nextChallenge();
    }

    /** Resolves ''random'' and deals the first (or next) challenge. */
    private void nextChallenge() {
        activeType = gameType;
        if ("random".equals(activeType)) {
            String[] pool = {"math", "typing", "numbers", "colors"};
            activeType = pool[random.nextInt(pool.length)];
        }
        statusView.setText("");
        gridView.removeAllViews();
        gridView.setVisibility(View.GONE);
        answerView.setVisibility(View.GONE);
        hintView.setVisibility(View.VISIBLE);
        primaryButton.setText("확인");

        progressView.setText("알람 해제 미션 " + (solved + 1) + " / " + totalProblems);

        if ("typing".equals(activeType)) {
            startTyping();
        } else if ("numbers".equals(activeType)) {
            startNumbers();
        } else if ("colors".equals(activeType)) {
            startColors();
        } else {
            startMath();
        }
    }

    private void startMath() {
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
        hintView.setText("정답 숫자를 입력하면 알람이 꺼져요");
        answerView.setHint("정답 숫자 입력");
        answerView.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        answerView.setText("");
        answerView.setVisibility(View.VISIBLE);
        answerView.requestFocus();
    }

    private void startTyping() {
        if (!cheer.trim().isEmpty()) {
            phrase = cheer.trim();
        } else if ("hard".equals(difficulty)) {
            phrase = "오늘도 안전근무 화이팅 힘내세요";
        } else if ("normal".equals(difficulty)) {
            phrase = "오늘도 안전근무 화이팅";
        } else {
            phrase = "안전근무";
        }
        questionView.setText("따라 쓰기");
        questionView.setTextSize(22f);
        hintView.setText("아래 문장을 똑같이 입력하세요:\n\"" + phrase + "\"");
        answerView.setHint("여기에 따라 입력");
        answerView.setInputType(InputType.TYPE_CLASS_TEXT);
        answerView.setText("");
        answerView.setVisibility(View.VISIBLE);
        answerView.requestFocus();
    }

    private void startMathHintReset() {
        questionView.setTextSize(28f);
    }

    private void startNumbers() {
        numberCount = "hard".equals(difficulty) ? 16 : "normal".equals(difficulty) ? 12 : 9;
        int columns = numberCount > 12 ? 4 : 3;
        nextNumber = 1;
        questionView.setTextSize(22f);
        questionView.setText("1부터 " + numberCount + "까지 순서대로 터치!");
        hintView.setText("숫자를 차례대로 누르면 알람이 꺼져요");

        List<Integer> nums = new ArrayList<>();
        for (int i = 1; i <= numberCount; i++) {
            nums.add(i);
        }
        Collections.shuffle(nums, random);

        gridView.setColumnCount(columns);
        gridView.setVisibility(View.VISIBLE);
        int size = dp(numberCount > 12 ? 52 : 62);
        for (int i = 0; i < nums.size(); i++) {
            final int value = nums.get(i);
            Button cell = numberCell(value, size, columns);
            cell.setOnClickListener(v -> onNumberTap(value, (Button) v));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = size;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            gridView.addView(cell, params);
        }
    }

    private Button numberCell(int value, int size, int columns) {
        Button cell = new Button(this);
        cell.setText(String.valueOf(value));
        cell.setTextSize(numberCount > 12 ? 16f : 19f);
        cell.setTypeface(Typeface.DEFAULT_BOLD);
        cell.setTextColor(Color.WHITE);
        cell.setAllCaps(false);
        cell.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E293B"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        cell.setBackground(bg);
        return cell;
    }

    private void onNumberTap(int value, Button cell) {
        if (dismissed) {
            return;
        }
        if (value == nextNumber) {
            GradientDrawable done = new GradientDrawable();
            done.setColor(Color.parseColor("#059669"));
            done.setCornerRadius(dp(14));
            cell.setBackground(done);
            nextNumber++;
            statusView.setText("");
            if (nextNumber > numberCount) {
                completeChallenge();
            }
        } else {
            statusView.setText("순서가 틀렸어요! 1부터 다시 눌러 주세요");
            restartNumbers();
        }
    }

    private void restartNumbers() {
        nextNumber = 1;
        for (int i = 0; i < gridView.getChildCount(); i++) {
            View child = gridView.getChildAt(i);
            if (child instanceof Button) {
                Button cell = (Button) child;
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#1E293B"));
                bg.setCornerRadius(dp(14));
                bg.setStroke(dp(1), Color.parseColor("#334155"));
                cell.setBackground(bg);
            }
        }
    }

    private void startColors() {
        int length = "hard".equals(difficulty) ? 5 : "normal".equals(difficulty) ? 4 : 3;
        colorTarget.clear();
        colorStep = 0;
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < COLOR_VALUES.length; i++) {
            pool.add(i);
        }
        Collections.shuffle(pool, random);
        for (int i = 0; i < length; i++) {
            colorTarget.add(pool.get(i));
        }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < colorTarget.size(); i++) {
            if (i > 0) {
                names.append(" → ");
            }
            names.append(COLOR_NAMES[colorTarget.get(i)]);
        }
        questionView.setTextSize(22f);
        questionView.setText("이 순서대로 터치하세요");
        hintView.setText(names + "\n(아래 색 버튼을 같은 순서로 누르면 알람이 꺼져요)");

        gridView.setColumnCount(3);
        gridView.setVisibility(View.VISIBLE);
        int size = dp(62);
        for (int i = 0; i < COLOR_VALUES.length; i++) {
            final int index = i;
            Button cell = new Button(this);
            cell.setText("");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COLOR_VALUES[i]);
            bg.setCornerRadius(dp(14));
            cell.setBackground(bg);
            cell.setOnClickListener(v -> onColorTap(index));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = size;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            gridView.addView(cell, params);
        }
    }

    private void onColorTap(int index) {
        if (dismissed || colorStep >= colorTarget.size()) {
            return;
        }
        if (colorTarget.get(colorStep) == index) {
            colorStep++;
            statusView.setText("");
            progressView.setText(colorStep + " / " + colorTarget.size() + " 색 맞춤");
            if (colorStep >= colorTarget.size()) {
                completeChallenge();
            }
        } else {
            colorStep = 0;
            statusView.setText("순서가 달라요. 처음 색부터 다시 눌러 주세요");
            progressView.setText("0 / " + colorTarget.size() + " 색 맞춤");
        }
    }

    private void completeChallenge() {
        solved++;
        if (solved >= totalProblems) {
            statusView.setText("");
            progressView.setText("알람 해제 성공!");
            stopAlarm();
        } else {
            nextChallenge();
        }
    }

    // ------------------------------------------------------------ the buttons

    private void onPrimary() {
        if (dismissed) {
            return;
        }
        if (!gameEnabled) {
            stopAlarm();
            return;
        }
        if ("typing".equals(activeType)) {
            checkTyping();
            return;
        }
        if (!"math".equals(activeType)) {
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
        completeChallenge();
    }

    private void checkTyping() {
        String raw = answerView.getText() == null ? "" : answerView.getText().toString();
        String typed = normalizeText(raw);
        String target = normalizeText(phrase);
        if (typed.isEmpty()) {
            statusView.setText("문장을 입력해 주세요");
            return;
        }
        if (!typed.equals(target)) {
            statusView.setText("아직 달라요. 문장을 똑같이 입력해 주세요");
            return;
        }
        completeChallenge();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    // -------------------------------------------------------------- lifecycle

    private void onAlarmStoppedElsewhere() {
        // The service's auto-off timer ended the alarm while this screen was up.
        if (dismissed) {
            return;
        }
        dismissed = true;
        Toast.makeText(this, "알람이 자동으로 꺼졌어요", Toast.LENGTH_SHORT).show();
        finish();
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
        AlarmService.setStoppedListener(null);
        AlarmService.stop(this, true);
        cancelNotification();
        Toast.makeText(this, "알람을 껐어요", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTop: a second alarm while this screen is up replaces what it shows.
        dismissed = false;
        solved = 0;
        readExtras(intent);
        if (!AlarmService.isRinging() && firedFromAlarm) {
            AlarmService.start(this, alarmJson);
        }
        if (gameEnabled && !firedFromAlarm && !AlarmService.isRinging()) {
            showScheduledInfo();
        } else {
            startChallenge();
        }
    }

    @Override
    public void onBackPressed() {
        if (gameEnabled && solved < totalProblems) {
            statusView.setText("미션을 완료해야 알람이 꺼져요");
            return;
        }
        stopAlarm();
    }

    @Override
    protected void onDestroy() {
        dismissed = true;
        AlarmService.setStoppedListener(null);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep the screen awake only while it is visible; the sound keeps running in
        // the service even if the activity is dismissed by the system.
    }
}
