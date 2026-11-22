package com.tsv.dutycalendar;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Full-screen WebView shell for 「TSV 교대달력」.
 *
 * The web app lives in assets/index.html and is loaded from
 * file:///android_asset/index.html. Everything the calendar needs (shift table,
 * memos, settings) stays in the page's localStorage; the device calendar is
 * reached through the small AndroidCalendar bridge below, which exposes the
 * permission state, the calendar list, the events of a range, and event insert.
 */
public class MainActivity extends Activity {

    private static final String TAG = "TsvDutyCalendar";
    private static final String START_URL = "file:///android_asset/index.html";

    private static final int REQ_CALENDAR_PERMISSION = 2001;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_background));
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.app_background));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();

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
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null) {
                    Log.d(TAG, message.message() + " @" + message.lineNumber());
                }
                return true;
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
                    String color = null;
                    if (!cursor.isNull(3)) {
                        color = toHexColor(cursor.getInt(3));
                    }
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
                    // All-day rows are stored at UTC midnight, so read them in UTC
                    // or the date slides a day backwards in negative-offset zones.
                    item.put("ymd", allDay
                            ? ymdUtc.format(new Date(begin))
                            : ymdLocal.format(new Date(begin)));
                    item.put("allDay", allDay);
                    item.put("startTime", allDay ? "" : hmLocal.format(new Date(begin)));
                    item.put("endTime", allDay ? "" : hmLocal.format(new Date(end)));
                    String color = null;
                    if (!cursor.isNull(7)) {
                        color = toHexColor(cursor.getInt(7));
                    }
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

        /** First visible calendar the app is allowed to write into, or -1. */
        private long findWritableCalendarId() {
            String[] projection = {CalendarContract.Calendars._ID};
            String selection = CalendarContract.Calendars.VISIBLE + "=1 AND "
                    + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">="
                    + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(
                        CalendarContract.Calendars.CONTENT_URI,
                        projection,
                        selection,
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
        if (requestCode != REQ_CALENDAR_PERMISSION) {
            return;
        }
        deliverPermissionResult(hasCalendarPermission());
    }

    // ------------------------------------------------------------ lifecycle

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
