package com.tsv.dutycalendar;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Full-screen WebView shell for 「TSV 교대달력」.
 *
 * The web app lives in assets/index.html and is loaded from
 * file:///android_asset/index.html, untouched. Two small bridges add what a bare
 * WebView cannot do by itself:
 *
 *   window.AndroidCalendar — the device calendar (permission, calendar list,
 *   events of a range, event insert), used by the page's own sync code.
 *
 *   window.AndroidBridge   — file save (JSON backup export), clipboard, the
 *   file chooser for the JSON import / photo picker, one-tap image sharing
 *   into KakaoTalk and friends, and a SharedPreferences mirror of the page's
 *   localStorage. assets/bridge.js wires the page's normal web APIs to it.
 *
 *   window.AndroidAlarm    — the shift alarms. scheduleAlarms(json) hands the
 *   page's upcoming alarm occurrences to AlarmReceiver, which keeps AlarmManager
 *   and a high-priority full-screen notification in sync with them. The page
 *   only calls it when the bridge exists, so the same index.html also runs as
 *   a plain web app.
 *
 * Coming back from the Samsung/Google calendar app re-syncs the device calendar:
 * onResume() calls the page's own window.onAppResume().
 */
public class MainActivity extends Activity {

    private static final String TAG = "TsvDutyCalendar";
    private static final String START_URL = "file:///android_asset/index.html";

    private static final String PREFS_NAME = "tsv_duty_web_storage";
    private static final String MAIN_STORAGE_KEY = "tsv_duty_calendar_v1";

    private static final int REQ_CALENDAR_PERMISSION = 2001;
    private static final int REQ_NOTIFICATIONS = 2004;
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;
    private static final int REQ_SHARE_STORAGE = 1003;

    private static final String PICTURE_ALBUM = "TSV교대달력";

    /** Returns '1' when the back press was consumed (sheet/drawer closed, scrolled up). */
    private static final String BACK_JS =
            "(function(){try{"
                    + "var s=document.getElementById('sheet');"
                    + "var d=document.getElementById('drawer');"
                    + "var b=document.getElementById('backdrop');"
                    + "if((s&&s.classList.contains('open'))||(d&&d.classList.contains('open'))){"
                    + "if(b){b.click();}else{"
                    + "if(s)s.classList.remove('open');if(d)d.classList.remove('open');}return '1';}"
                    + "if(window.scrollY>4){window.scrollTo({top:0,behavior:'smooth'});return '1';}"
                    + "return '0';}catch(e){return '0';}})()";

    private WebView webView;
    private String bridgeJs = "";
    private ValueCallback<Uri[]> filePathCallback;
    private boolean storageCheckDone = false;

    /** True once the asset page has finished loading, so the resume hook is safe to fire. */
    private boolean pageReady = false;

    private String pendingName;
    private String pendingMime;
    private byte[] pendingBytes;

    private String pendingShareName;
    private byte[] pendingShareBytes;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bridgeJs = readAsset("bridge.js");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_background));
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.app_background));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();

        // Alarm plumbing: the channel has to exist before the first notification, and
        // re-arming here restores alarms that a force-stop or an update dropped.
        AlarmReceiver.ensureChannel(this);
        AlarmReceiver.rescheduleAll(this);
        requestNotificationPermission();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        try {
            settings.setAllowFileAccessFromFileURLs(true);
        } catch (Throwable ignored) {
            // Not every WebView provider exposes this flag.
        }

        webView.addJavascriptInterface(new CalendarBridge(), "AndroidCalendar");
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.addJavascriptInterface(new AlarmBridge(), "AndroidAlarm");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request != null ? request.getUrl() : null);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url != null ? Uri.parse(url) : null);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageReady = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                if (!bridgeJs.isEmpty()) {
                    view.evaluateJavascript(bridgeJs, null);
                }
                if (!storageCheckDone) {
                    storageCheckDone = true;
                    restoreStorageIfNeeded();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("확인", (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("확인", (dialog, which) -> result.confirm())
                        .setNegativeButton("취소", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");

                String[] accept = params != null ? params.getAcceptTypes() : null;
                String title = "파일 선택";
                if (accept != null) {
                    List<String> mimes = new ArrayList<>();
                    for (String entry : accept) {
                        if (entry == null) {
                            continue;
                        }
                        String cleaned = entry.split(";")[0].trim();
                        if (cleaned.contains("/") && !cleaned.startsWith(".")) {
                            mimes.add(cleaned);
                        }
                    }
                    if (mimes.isEmpty()) {
                        // ".json" style accept types still mean JSON files.
                        for (String entry : accept) {
                            if (entry != null && entry.trim().toLowerCase(Locale.US).contains("json")) {
                                mimes.add("application/json");
                                mimes.add("text/plain");
                            }
                        }
                    }
                    if (!mimes.isEmpty()) {
                        // <input accept=".json,application/json">: some storage providers report
                        // .json as text/plain, so keep that as a fallback filter.
                        if (mimes.contains("application/json") && !mimes.contains("text/plain")) {
                            mimes.add("text/plain");
                        }
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));

                        // <input type="file" accept="image/*"> must open the gallery / photo
                        // picker, not an "all files" browser: give the intent a concrete type
                        // instead of the */* placeholder (other types keep v1.0's behaviour).
                        boolean onlyImages = true;
                        for (String mime : mimes) {
                            if (!mime.toLowerCase(Locale.US).startsWith("image/")) {
                                onlyImages = false;
                                break;
                            }
                        }
                        if (onlyImages) {
                            intent.setType(mimes.size() == 1 ? mimes.get(0) : "image/*");
                            title = "사진 선택";
                        }
                    }
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, title), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toast("파일을 선택할 앱을 찾지 못했어요");
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null) {
                    Log.d(TAG, message.message() + " @" + message.lineNumber());
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null || url.startsWith("data:") || url.startsWith("blob:")) {
                return;
            }
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                if (mimeType != null) {
                    request.setMimeType(mimeType);
                }
                if (userAgent != null) {
                    request.addRequestHeader("User-Agent", userAgent);
                }
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        "TSV-교대달력-" + System.currentTimeMillis());
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) {
                    manager.enqueue(request);
                }
            } catch (Exception e) {
                Log.w(TAG, "Download failed", e);
            }
        });
    }

    /** Allows asset pages, sends everything else to the right external app. */
    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (scheme.isEmpty() || scheme.equals("file")) {
            return false;
        }
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
                || scheme.equals("tel") || scheme.equals("sms") || scheme.equals("intent")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                toast("이 링크를 열 수 있는 앱이 없어요");
            }
            return true;
        }
        return false;
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------- device calendar

    /** JS-facing API: window.AndroidCalendar.* */
    public class CalendarBridge {

        @JavascriptInterface
        public boolean hasCalendarPermission() {
            return MainActivity.this.hasCalendarPermission();
        }

        @JavascriptInterface
        public void requestCalendarPermission() {
            runOnUiThread(() -> {
                if (MainActivity.this.hasCalendarPermission()) {
                    deliverPermissionResult(true);
                    return;
                }
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_CALENDAR,
                        android.Manifest.permission.WRITE_CALENDAR
                }, REQ_CALENDAR_PERMISSION);
            });
        }

        @JavascriptInterface
        public String getCalendars() {
            if (!hasCalendarPermission()) {
                return "[]";
            }
            JSONArray out = new JSONArray();
            ContentResolver resolver = getContentResolver();
            String[] projection = {
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.VISIBLE
            };
            Cursor cursor = null;
            try {
                cursor = resolver.query(
                        CalendarContract.Calendars.CONTENT_URI,
                        projection,
                        null,
                        null,
                        CalendarContract.Calendars.VISIBLE + " DESC, "
                                + CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC");
                if (cursor == null) {
                    return "[]";
                }
                while (cursor.moveToNext()) {
                    JSONObject item = new JSONObject();
                    long id = cursor.getLong(0);
                    String name = cursor.isNull(1) ? "" : cursor.getString(1);
                    String account = cursor.isNull(2) ? "" : cursor.getString(2);
                    String color = cursor.isNull(3) ? null : toHexColor(cursor.getInt(3));
                    item.put("id", String.valueOf(id));
                    item.put("name", name);
                    item.put("accountName", account);
                    item.put("color", color == null ? "#2563eb" : color);
                    out.put(item);
                }
            } catch (Exception e) {
                Log.w(TAG, "getCalendars failed", e);
                return "[]";
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public String getEvents(long startMs, long endMs) {
            if (!hasCalendarPermission()) {
                return "[]";
            }
            if (endMs <= startMs) {
                return "[]";
            }
            JSONArray out = new JSONArray();
            ContentResolver resolver = getContentResolver();

            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, startMs);
            ContentUris.appendId(builder, endMs);

            String[] projection = {
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.DISPLAY_COLOR
            };

            // All-day rows are stored at UTC midnight, so they must be read in UTC
            // or the date slides a day in KST and other positive-offset zones.
            SimpleDateFormat ymdUtc = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            ymdUtc.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat ymdLocal = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            ymdLocal.setTimeZone(TimeZone.getDefault());
            SimpleDateFormat hmLocal = new SimpleDateFormat("HH:mm", Locale.US);
            hmLocal.setTimeZone(TimeZone.getDefault());

            Cursor cursor = null;
            try {
                cursor = resolver.query(builder.build(), projection, null, null,
                        CalendarContract.Instances.BEGIN + " ASC");
                if (cursor == null) {
                    return "[]";
                }
                while (cursor.moveToNext()) {
                    boolean allDay = !cursor.isNull(6) && cursor.getInt(6) == 1;
                    long begin = cursor.getLong(4);
                    long end = cursor.isNull(5) ? begin : cursor.getLong(5);

                    JSONObject item = new JSONObject();
                    item.put("id", cursor.isNull(0) ? "" : String.valueOf(cursor.getLong(0)));
                    item.put("calId", cursor.isNull(1) ? "" : String.valueOf(cursor.getLong(1)));
                    item.put("calName", cursor.isNull(2) ? "" : cursor.getString(2));
                    item.put("title", cursor.isNull(3) ? "" : cursor.getString(3));
                    item.put("ymd", allDay
                            ? ymdUtc.format(new Date(begin))
                            : ymdLocal.format(new Date(begin)));
                    item.put("allDay", allDay);
                    item.put("startTime", allDay ? "" : hmLocal.format(new Date(begin)));
                    item.put("endTime", allDay ? "" : hmLocal.format(new Date(end)));
                    String color = cursor.isNull(7) ? null : toHexColor(cursor.getInt(7));
                    item.put("color", color == null ? "#0d9488" : color);
                    out.put(item);
                }
            } catch (Exception e) {
                Log.w(TAG, "getEvents failed", e);
                return "[]";
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return out.toString();
        }

        @JavascriptInterface
        public boolean addEvent(String ymd, String title, boolean allDay,
                                String startTime, String endTime) {
            if (!hasCalendarPermission() || ymd == null || title == null
                    || title.trim().isEmpty()) {
                return false;
            }
            try {
                long calendarId = findWritableCalendarId();
                if (calendarId < 0) {
                    return false;
                }
                Date[] range = buildEventRange(ymd.trim(), allDay, startTime, endTime);
                if (range == null) {
                    return false;
                }

                ContentValues values = new ContentValues();
                values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
                values.put(CalendarContract.Events.TITLE, title.trim());
                values.put(CalendarContract.Events.DTSTART, range[0].getTime());
                values.put(CalendarContract.Events.DTEND, range[1].getTime());
                values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
                values.put(CalendarContract.Events.EVENT_TIMEZONE, allDay
                        ? "UTC" : TimeZone.getDefault().getID());
                values.put(CalendarContract.Events.HAS_ALARM, 0);

                Uri uri = getContentResolver()
                        .insert(CalendarContract.Events.CONTENT_URI, values);
                return uri != null;
            } catch (Exception e) {
                Log.w(TAG, "addEvent failed", e);
                return false;
            }
        }
    }

    /** First visible calendar the app may write into, or -1. */
    private long findWritableCalendarId() {
        String[] projection = {CalendarContract.Calendars._ID};
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    CalendarContract.Calendars.CONTENT_URI,
                    projection,
                    CalendarContract.Calendars.VISIBLE + "=1 AND "
                            + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">="
                            + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    null,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " DESC, "
                            + CalendarContract.Calendars._ID + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "findWritableCalendarId failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
                cursor = null;
            }
        }
        // Fall back to any writable calendar even if it is hidden from the UI.
        try {
            cursor = getContentResolver().query(
                    CalendarContract.Calendars.CONTENT_URI,
                    projection,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">="
                            + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    null,
                    CalendarContract.Calendars._ID + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "findWritableCalendarId fallback failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    /** Turns the page's ymd/times into a [start, end] pair the calendar accepts. */
    private static Date[] buildEventRange(String ymd, boolean allDay,
                                          String startTime, String endTime) {
        try {
            if (allDay) {
                // All-day events live at UTC midnight; DTEND is exclusive.
                SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                parser.setLenient(false);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date start = parser.parse(ymd);
                if (start == null) {
                    return null;
                }
                Calendar end = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
                end.setTime(start);
                end.add(Calendar.DAY_OF_MONTH, 1);
                return new Date[]{start, end.getTime()};
            }

            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            parser.setLenient(false);
            parser.setTimeZone(TimeZone.getDefault());

            String startText = (startTime == null || startTime.trim().isEmpty())
                    ? "09:00" : normalizeHm(startTime);
            String endText = (endTime == null || endTime.trim().isEmpty())
                    ? null : normalizeHm(endTime);

            Date start = parser.parse(ymd + " " + startText);
            if (start == null) {
                return null;
            }
            Date end = endText == null ? null : parser.parse(ymd + " " + endText);
            if (end == null || !end.after(start)) {
                end = new Date(start.getTime() + 60L * 60L * 1000L);
            }
            return new Date[]{start, end};
        } catch (Exception e) {
            Log.w(TAG, "buildEventRange failed", e);
            return null;
        }
    }

    private static String normalizeHm(String value) {
        String[] parts = value.trim().split(":");
        if (parts.length < 2) {
            return "09:00";
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            return String.format(Locale.US, "%02d:%02d",
                    Math.max(0, Math.min(23, hour)), Math.max(0, Math.min(59, minute)));
        } catch (Exception e) {
            return "09:00";
        }
    }

    private static String toHexColor(int argb) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & argb);
    }

    /** True when the page handed over at least one occurrence (i.e. an alarm is on). */
    private static boolean jsonHasAlarms(String jsonStr) {
        try {
            return new JSONArray(jsonStr == null ? "[]" : jsonStr).length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Android 12+ keeps exact alarms, and Android 14+ full-screen intents, behind a
     * special-access switch. Without them the alarm still exists but can ring late or
     * only as a heads-up, so the first time an alarm is scheduled we point the user at
     * the switch — once per install, never again after they decide.
     */
    private void maybeAskAlarmPermissions() {
        SharedPreferences asked = getSharedPreferences(AlarmReceiver.PREFS, MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            boolean exact = false;
            try {
                exact = am != null && am.canScheduleExactAlarms();
            } catch (Exception ignored) {
                exact = false;
            }
            if (!exact && !asked.getBoolean("asked_exact_alarm", false)) {
                asked.edit().putBoolean("asked_exact_alarm", true).apply();
                new AlertDialog.Builder(this)
                        .setTitle("알람을 정확한 시각에 울리려면")
                        .setMessage("휴대폰 설정에서 '알람 및 리마인더'를 허용하면 "
                                + "근무 알람이 예정된 시각에 정확히 울립니다.\n"
                                + "허용하지 않으면 몇 분 늦게 울릴 수 있어요.")
                        .setPositiveButton("설정 열기", (dialog, which) -> openSpecialAccess(
                                "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"))
                        .setNegativeButton("나중에", null)
                        .show();
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            boolean fullScreen = true;
            try {
                fullScreen = nm == null || nm.canUseFullScreenIntent();
            } catch (Exception ignored) {
                fullScreen = false;
            }
            if (!fullScreen && !asked.getBoolean("asked_fullscreen_intent", false)) {
                asked.edit().putBoolean("asked_fullscreen_intent", true).apply();
                new AlertDialog.Builder(this)
                        .setTitle("잠금화면 알람 화면")
                        .setMessage("'전체 화면 알림'을 허용하면 화면이 꺼져 있거나 잠겨 있어도 "
                                + "알람 화면이 바로 뜨게 됩니다.")
                        .setPositiveButton("설정 열기", (dialog, which) -> openSpecialAccess(
                                "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"))
                        .setNegativeButton("나중에", null)
                        .show();
            }
        }
    }

    /** Opens a special-access screen for this package, falling back to app details. */
    private void openSpecialAccess(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                detail.setData(Uri.parse("package:" + getPackageName()));
                startActivity(detail);
            } catch (Exception ignored) {
                toast("설정 화면을 열지 못했어요");
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQ_NOTIFICATIONS);
    }

    private boolean hasCalendarPermission() {
        return checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void deliverPermissionResult(boolean granted) {
        if (webView == null) {
            return;
        }
        String js = "window.onCalendarPermissionResult && window.onCalendarPermissionResult(" + granted + ");";
        webView.evaluateJavascript(js, null);
        toast(granted ? "캘린더 사용 권한이 허용되었습니다" : "캘린더 권한이 거부되었습니다");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALENDAR_PERMISSION) {
            deliverPermissionResult(hasCalendarPermission());
            return;
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                toast("알림 권한이 없으면 알람 화면이 뜨지 않을 수 있어요");
            }
            return;
        }
        if (requestCode == REQ_WRITE_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingBytes != null) {
                writeToLegacyDownloads(pendingName, pendingMime, pendingBytes);
            } else {
                toast("저장 권한이 없어 파일을 저장하지 못했어요");
            }
            pendingName = null;
            pendingMime = null;
            pendingBytes = null;
            return;
        }
        if (requestCode == REQ_SHARE_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            String name = pendingShareName;
            byte[] bytes = pendingShareBytes;
            pendingShareName = null;
            pendingShareBytes = null;
            if (granted && bytes != null) {
                shareImageLegacy(name, "image/png", bytes);
            } else {
                toast("저장 권한이 없어 이미지를 공유하지 못했어요");
            }
        }
    }

    // ---------------------------------------------------------------- storage

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    /**
     * Some devices are strict about localStorage for the file:// origin. The
     * page mirrors every write into SharedPreferences, and here we push the
     * snapshot back if the page starts with no saved shift table.
     */
    private void restoreStorageIfNeeded() {
        final Map<String, ?> snapshot = prefs().getAll();
        if (snapshot.isEmpty()) {
            return;
        }
        String js = "(function(){try{return localStorage.getItem("
                + JSONObject.quote(MAIN_STORAGE_KEY) + ")?'has':'missing';}catch(e){return 'error';}})()";
        webView.evaluateJavascript(js, value -> {
            if (value != null && value.contains("missing")) {
                restoreSnapshot(snapshot);
            }
        });
    }

    private void restoreSnapshot(Map<String, ?> snapshot) {
        StringBuilder js = new StringBuilder("(function(){try{");
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                js.append("localStorage.setItem(")
                        .append(JSONObject.quote(entry.getKey()))
                        .append(",")
                        .append(JSONObject.quote((String) value))
                        .append(");");
            }
        }
        js.append("return 'ok';}catch(e){return 'fail';}})()");
        webView.evaluateJavascript(js.toString(), value -> runOnUiThread(() -> {
            if (value != null && value.contains("ok")) {
                webView.reload();
            }
        }));
    }

    // --------------------------------------------------------------- download

    private void saveDataUrlInternal(String dataUrl, String fileName) {
        if (dataUrl == null) {
            toast("저장할 내용이 없어요");
            return;
        }
        if (!dataUrl.startsWith("data:")) {
            toast("이 형식은 바로 저장할 수 없어요");
            return;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            toast("저장할 수 없는 데이터예요");
            return;
        }
        String meta = dataUrl.substring(5, comma);
        String payload = dataUrl.substring(comma + 1);
        String mime = meta.split(";")[0].trim();
        if (mime.isEmpty()) {
            mime = "application/octet-stream";
        }

        byte[] bytes;
        try {
            if (meta.contains("base64")) {
                bytes = Base64.decode(payload, Base64.DEFAULT);
            } else {
                bytes = Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "Base64 decode failed", e);
            toast("파일을 읽지 못했어요");
            return;
        }

        String name = sanitizeFileName(fileName, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            writeWithMediaStore(name, mime, bytes);
        } else if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingName = name;
            pendingMime = mime;
            pendingBytes = bytes;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE);
        } else {
            writeToLegacyDownloads(name, mime, bytes);
        }
    }

    private void writeWithMediaStore(String name, String mime, byte[] bytes) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = null;
        OutputStream out = null;
        try {
            item = getContentResolver().insert(collection, values);
            if (item == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            out = getContentResolver().openOutputStream(item);
            if (out == null) {
                throw new IllegalStateException("MediaStore stream unavailable");
            }
            out.write(bytes);
            out.flush();
            out.close();
            out = null;

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(item, ready, null, null);
            toast("다운로드 폴더에 저장했어요\n" + name);
        } catch (Exception e) {
            Log.w(TAG, "MediaStore save failed", e);
            if (item != null) {
                try {
                    getContentResolver().delete(item, null, null);
                } catch (Exception ignored) {
                    // nothing else to do
                }
            }
            toast("파일을 저장하지 못했어요");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // nothing else to do
                }
            }
        }
    }

    private void writeToLegacyDownloads(String name, String mime, byte[] bytes) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            toast("저장 위치를 찾지 못했어요");
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create " + dir);
        }
        File target = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(bytes);
        } catch (Exception e) {
            Log.w(TAG, "Legacy save failed", e);
            toast("파일을 저장하지 못했어요");
            return;
        }
        toast("다운로드 폴더에 저장했어요\n" + name);
    }

    // ---------------------------------------------------------- image share

    /**
     * Saves a PNG data URL into the phone's photo gallery (Pictures/TSV교대달력) and
     * opens the system share sheet so it goes straight to KakaoTalk, Messages, etc.
     * Called from the page as window.AndroidBridge.shareImageDataUrl(dataUrl, fileName).
     */
    private void shareImageDataUrlInternal(String dataUrl, String fileName) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            toast("공유할 이미지가 없어요");
            return;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            toast("공유할 이미지가 없어요");
            return;
        }
        String meta = dataUrl.substring(5, comma);
        String payload = dataUrl.substring(comma + 1);
        String mime = meta.split(";")[0].trim();
        if (mime.isEmpty()) {
            mime = "image/png";
        }

        byte[] bytes;
        try {
            bytes = meta.contains("base64")
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Share image decode failed", e);
            toast("이미지를 읽지 못했어요");
            return;
        }
        if (bytes.length == 0) {
            toast("공유할 이미지가 없어요");
            return;
        }

        String name = sanitizeImageFileName(fileName);
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = saveImageWithMediaStore(name, mime, bytes);
            if (uri == null) {
                toast("이미지를 저장하지 못해 공유할 수 없어요");
                return;
            }
            launchImageShare(uri, mime);
            return;
        }

        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingShareName = name;
            pendingShareBytes = bytes;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_SHARE_STORAGE);
            return;
        }
        shareImageLegacy(name, mime, bytes);
    }

    /** Android 10+: insert straight into the gallery with no storage permission. */
    private Uri saveImageWithMediaStore(String name, String mime, byte[] bytes) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + PICTURE_ALBUM);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = null;
        OutputStream out = null;
        try {
            item = getContentResolver().insert(collection, values);
            if (item == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            out = getContentResolver().openOutputStream(item);
            if (out == null) {
                throw new IllegalStateException("MediaStore stream unavailable");
            }
            out.write(bytes);
            out.flush();
            out.close();
            out = null;

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(item, ready, null, null);
            return item;
        } catch (Exception e) {
            Log.w(TAG, "MediaStore image save failed", e);
            if (item != null) {
                try {
                    getContentResolver().delete(item, null, null);
                } catch (Exception ignored) {
                    // nothing else to do
                }
            }
            return null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // nothing else to do
                }
            }
        }
    }

    /** Android 9 and below: write the file into Pictures/TSV교대달력 and let the scanner index it. */
    private void shareImageLegacy(String name, String mime, byte[] bytes) {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                PICTURE_ALBUM);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create " + dir);
            toast("이미지를 저장하지 못했어요");
            return;
        }
        File target = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(bytes);
        } catch (Exception e) {
            Log.w(TAG, "Legacy image save failed", e);
            toast("이미지를 저장하지 못했어요");
            return;
        }
        final String imageMime = (mime == null || mime.isEmpty()) ? "image/png" : mime;
        MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()},
                new String[]{imageMime}, (path, uri) -> runOnUiThread(() -> {
                    if (uri != null) {
                        launchImageShare(uri, imageMime);
                    } else {
                        toast("이미지를 공유하지 못했어요");
                    }
                }));
    }

    /** System share sheet (KakaoTalk, Messages, Drive ...) for the saved gallery image. */
    private void launchImageShare(Uri uri, String mime) {
        if (uri == null) {
            toast("이미지를 공유하지 못했어요");
            return;
        }
        String type = (mime == null || mime.isEmpty()) ? "image/png" : mime;
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(type);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newUri(getContentResolver(), "TSV 근무표", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, "근무표 이미지 공유");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            toast("이미지를 공유할 앱을 찾지 못했어요");
        } catch (Exception e) {
            Log.w(TAG, "Image share failed", e);
            toast("이미지를 공유하지 못했어요");
        }
    }

    /** Gallery-friendly file name that always ends in .png. */
    private static String sanitizeImageFileName(String raw) {
        String name = sanitizeFileName(raw, "image/png");
        if (!name.toLowerCase(Locale.US).endsWith(".png")) {
            name = name + ".png";
        }
        return name;
    }

    private static String sanitizeFileName(String raw, String mime) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            name = "TSV-교대달력-" + System.currentTimeMillis();
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\r\n\t]", "_");
        int dot = name.lastIndexOf('.');
        if (name.length() > 120) {
            if (dot > 0) {
                name = name.substring(0, 100) + name.substring(dot);
            } else {
                name = name.substring(0, 100);
            }
        }
        if (name.lastIndexOf('.') <= 0) {
            if ("application/json".equals(mime)) {
                name += ".json";
            } else if ("image/png".equals(mime)) {
                name += ".png";
            }
        }
        return name;
    }

    private String readAsset(String name) {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getAssets().open(name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read asset " + name, e);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------- JS bridge API

    /** JS-facing API: window.AndroidBridge.* */
    public class Bridge {

        @JavascriptInterface
        public void saveDataUrl(String dataUrl, String fileName) {
            runOnUiThread(() -> saveDataUrlInternal(dataUrl, fileName));
        }

        /** One-tap "이미지 카톡 공유": save PNG to the gallery, then open the share sheet. */
        @JavascriptInterface
        public void shareImageDataUrl(String dataUrl, String fileName) {
            runOnUiThread(() -> shareImageDataUrlInternal(dataUrl, fileName));
        }
        @JavascriptInterface
        public void setItem(String key, String value) {
            if (key == null) {
                return;
            }
            prefs().edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public String getItem(String key) {
            return key == null ? null : prefs().getString(key, null);
        }

        @JavascriptInterface
        public void removeItem(String key) {
            if (key != null) {
                prefs().edit().remove(key).apply();
            }
        }

        @JavascriptInterface
        public boolean copyToClipboard(String text) {
            if (text == null) {
                return false;
            }
            runOnUiThread(() -> {
                try {
                    ClipboardManager manager =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager != null) {
                        manager.setPrimaryClip(ClipData.newPlainText("TSV 교대달력", text));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Clipboard failed", e);
                }
            });
            return true;
        }

        @JavascriptInterface
        public void toast(String message) {
            if (message != null) {
                MainActivity.this.toast(message);
            }
        }
    }

    /** JS-facing API: window.AndroidAlarm.* */
    public class AlarmBridge {

        /**
         * Replaces the whole native alarm schedule. The page sends the occurrences it
         * already filtered to the next 30 days as a JSON array of
         * {id, triggerAtMs, label, gameEnabled, gameDifficulty, gameCount, sound, vibrate}.
         */
        @JavascriptInterface
        public void scheduleAlarms(String jsonStr) {
            try {
                AlarmReceiver.ensureChannel(MainActivity.this);
                AlarmReceiver.apply(MainActivity.this, jsonStr);
                if (jsonHasAlarms(jsonStr)) {
                    runOnUiThread(() -> maybeAskAlarmPermissions());
                }
            } catch (Exception e) {
                Log.w(TAG, "AndroidAlarm.scheduleAlarms failed", e);
            }
        }

        @JavascriptInterface
        public void cancelAlarms() {
            try {
                AlarmReceiver.apply(MainActivity.this, "[]");
            } catch (Exception e) {
                Log.w(TAG, "AndroidAlarm.cancelAlarms failed", e);
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER || filePathCallback == null) {
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(BACK_JS, value -> {
            boolean consumed = value != null && value.contains("1");
            if (!consumed) {
                runOnUiThread(this::exitApp);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void exitApp() {
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            // Coming back from the Samsung/Google calendar app: let the page re-read
            // the device calendar at once instead of waiting for a manual sync tap.
            if (pageReady) {
                webView.evaluateJavascript(
                        "if (window.onAppResume) window.onAppResume();", null);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
