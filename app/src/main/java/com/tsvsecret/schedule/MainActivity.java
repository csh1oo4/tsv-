package com.tsvsecret.schedule;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full-screen WebView shell for the local 「TSV secret 근무표」 app.
 *
 * The web app lives in assets/index.html and is loaded from
 * file:///android_asset/index.html. All app features (localStorage, JSON import,
 * share codes, PNG export) keep working through a small JavaScript bridge that
 * saves files into the device Download folder, mirrors localStorage into
 * SharedPreferences as a safety net, and syncs the system bars with the app theme.
 */
public class MainActivity extends Activity {

    private static final String TAG = "TsvSchedule";
    private static final String START_URL = "file:///android_asset/index.html";
    private static final String PREFS_NAME = "tsv_web_storage";
    private static final String MAIN_STORAGE_KEY = "tsv_secret_schedules_v1";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    /** Returns '1' when the back press was consumed (modal closed / scrolled up). */
    private static final String BACK_JS =
            "(function(){try{"
                    + "var m=document.querySelectorAll('.modal-backdrop.open');"
                    + "if(m.length){for(var i=0;i<m.length;i++){m[i].classList.remove('open');}return '1';}"
                    + "if(window.scrollY>4){window.scrollTo({top:0,behavior:'smooth'});return '1';}"
                    + "return '0';}catch(e){return '0';}})()";

    private WebView webView;
    private String bridgeJs = "";
    private ValueCallback<Uri[]> filePathCallback;
    private boolean storageCheckDone = false;

    private String pendingName;
    private String pendingMime;
    private byte[] pendingBytes;

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
        settings.setMediaPlaybackRequiresUserGesture(true);
        try {
            settings.setAllowFileAccessFromFileURLs(true);
        } catch (Throwable ignored) {
            // Not all WebView providers expose this flag.
        }

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
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
                    if (!mimes.isEmpty()) {
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                    }
                }

                try {
                    startActivityForResult(Intent.createChooser(intent, "근무표 파일 선택"), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toastMessage("파일을 선택할 앱을 찾지 못했어요");
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
            if (url == null) {
                return;
            }
            if (url.startsWith("data:") || url.startsWith("blob:")) {
                saveDataUrlInternal(url, guessFileName(contentDisposition, mimeType));
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
                        guessFileName(contentDisposition, mimeType));
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) {
                    manager.enqueue(request);
                    toastMessage("다운로드를 시작했어요");
                }
            } catch (Exception e) {
                Log.w(TAG, "Download failed", e);
                toastMessage("다운로드를 시작하지 못했어요");
            }
        });
    }

    /** Allows asset pages, sends everything else to the right external app. */
    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
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
                toastMessage("이 링크를 열 수 있는 앱이 없어요");
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- storage

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    /**
     * If the WebView lost its localStorage (some devices are strict about
     * storage for file:// origins) but the native mirror still holds the
     * schedules, push them back and reload once.
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
            toastMessage("저장할 내용이 없어요");
            return;
        }
        if (!dataUrl.startsWith("data:")) {
            toastMessage("이 형식은 바로 저장할 수 없어요");
            return;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            toastMessage("저장할 수 없는 데이터예요");
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
            toastMessage("파일을 읽지 못했어요");
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
            toastMessage("다운로드 폴더에 저장했어요\n" + name);
        } catch (Exception e) {
            Log.w(TAG, "MediaStore save failed", e);
            if (item != null) {
                try {
                    getContentResolver().delete(item, null, null);
                } catch (Exception ignored) {
                    // nothing else to do
                }
            }
            toastMessage("파일을 저장하지 못했어요");
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
            toastMessage("저장 위치를 찾지 못했어요");
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
            toastMessage("파일을 저장하지 못했어요");
            return;
        }
        try {
            MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()},
                    new String[]{mime}, null);
        } catch (Exception ignored) {
            // scanner is best effort
        }
        toastMessage("다운로드 폴더에 저장했어요\n" + name);
    }

    private static String sanitizeFileName(String raw, String mime) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            name = "TSV-secret-근무표-" + System.currentTimeMillis();
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
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

    private static String guessFileName(String contentDisposition, String mime) {
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase().startsWith("filename=")) {
                    String value = trimmed.substring("filename=".length()).trim();
                    value = value.replace("\"", "");
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        String ext = "bin";
        if (mime != null) {
            if (mime.contains("json")) {
                ext = "json";
            } else if (mime.contains("png")) {
                ext = "png";
            } else if (mime.contains("jpeg") || mime.contains("jpg")) {
                ext = "jpg";
            } else if (mime.contains("pdf")) {
                ext = "pdf";
            }
        }
        return "TSV-secret-근무표-" + System.currentTimeMillis() + "." + ext;
    }

    // -------------------------------------------------------------- insets/UI

    private void applyChrome(String background, String header, boolean isNight) {
        int bg = parseHexColor(background, isNight ? 0xFF090E1A : 0xFFF3F6FB);
        int top = parseHexColor(header, isNight ? 0xFF111827 : 0xFF1D4ED8);
        Window window = getWindow();
        window.setStatusBarColor(top);
        window.setNavigationBarColor(bg);

        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (isNight) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private static int parseHexColor(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Color.parseColor(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void toastMessage(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
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

    // --------------------------------------------------------- JS bridge API

    public class Bridge {

        @JavascriptInterface
        public void saveDataUrl(String dataUrl, String fileName) {
            runOnUiThread(() -> saveDataUrlInternal(dataUrl, fileName));
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
                        manager.setPrimaryClip(ClipData.newPlainText("TSV secret 근무표", text));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Clipboard failed", e);
                }
            });
            return true;
        }

        @JavascriptInterface
        public void updateChrome(String background, String header, boolean isNight) {
            runOnUiThread(() -> applyChrome(background, header, isNight));
        }

        @JavascriptInterface
        public void toast(String message) {
            if (message != null) {
                toastMessage(message);
            }
        }
    }

    // ------------------------------------------------------- lifecycle/back

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER) {
            return;
        }
        if (filePathCallback == null) {
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingBytes != null) {
            writeToLegacyDownloads(pendingName, pendingMime, pendingBytes);
        } else {
            toastMessage("저장 권한이 없어 파일을 저장하지 못했어요");
        }
        pendingName = null;
        pendingMime = null;
        pendingBytes = null;
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
