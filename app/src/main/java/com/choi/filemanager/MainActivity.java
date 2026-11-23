package com.choi.filemanager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「SynoFile Pro」 shell.
 *
 * The whole screen is one WebView loading assets/index.html from file:///android_asset/.
 * Everything a WebView cannot do itself is handed to two Java bridges the page calls:
 *
 *   window.NasBridge        — DSM File Station (login, list, thumb, download, upload,
 *                             copy/move, rename, delete, quota). See NasBridge.
 *   window.LocalFileBridge  — the phone's storage (list, mkdir, rename, delete, copy/move,
 *                             open/share, storage stats, permissions). See LocalFileBridge.
 *
 * Displaying files goes through LocalServer: a loopback HTTP server that proxies NAS
 * originals and phone files to &lt;img&gt;/&lt;video&gt; with Range support, so photos can be
 * zoomed and videos seeked without loading them into the WebView.
 *
 * Results travel back as window.fmCallback(reqId, ok, json) (and fmProgress for transfers);
 * assets/bridge.js turns those into promises for the page.
 */
public class MainActivity extends Activity {

    private static final String TAG = "NasFileManager";
    private static final String START_URL = "file:///android_asset/index.html";
    private static final int REQ_MEDIA_PERMISSION = 3001;

    private static final String BACK_JS =
            "(function(){try{return (window.fmHandleBack&&window.fmHandleBack())?'1':'0';}"
                    + "catch(e){return '0';}})()";

    private WebView webView;
    private LocalServer server;
    private NasBridge nasBridge;
    private LocalFileBridge localBridge;
    private ExecutorService ioPool;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ioPool = Executors.newFixedThreadPool(6);
        server = new LocalServer(this);
        server.start();

        nasBridge = new NasBridge(this);
        localBridge = new LocalFileBridge(this);
        server.setNasSource(nasBridge);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F19);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0B0F19);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }

        requestMediaPermissions();
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
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        try {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {
            // not every WebView provider exposes these flags
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new NasJs(), "NasBridge");
        webView.addJavascriptInterface(new LocalJs(), "LocalFileBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                view.evaluateJavascript(
                        "window.fmServerBase=" + org.json.JSONObject.quote(server.baseUrl()) + ";"
                                + "window.fmServerToken=" + org.json.JSONObject.quote(server.token()) + ";"
                                + "if(window.fmOnNativeReady)window.fmOnNativeReady();", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return handleUrl(request == null ? null : request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url == null ? null : Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null) {
                    Log.d(TAG, "web: " + message.message() + " @" + message.lineNumber());
                }
                return true;
            }
        });
    }

    /** Only the local asset page may load; real links go to the browser. */
    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.US);
        if (scheme.isEmpty() || "file".equals(scheme) || "about".equals(scheme)) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("이 링크를 열 수 있는 앱이 없습니다.");
        }
        return true;
    }

    // ----------------------------------------------------------- bridge plumbing

    /** Runs bridge work off the UI thread. */
    public void runIo(Runnable task) {
        ExecutorService pool = ioPool;
        if (pool == null || pool.isShutdown()) {
            task.run();
            return;
        }
        pool.execute(task);
    }

    /** window.fmCallback(reqId, ok, json) */
    public void postResult(String reqId, boolean ok, String json) {
        final String js = "if(window.fmCallback)window.fmCallback("
                + org.json.JSONObject.quote(reqId == null ? "" : reqId)
                + "," + ok + "," + org.json.JSONObject.quote(json == null ? "{}" : json) + ");";
        runOnUiThread(() -> evaluate(js));
    }

    /** window.fmProgress(reqId, percent, message); percent -1 means "indeterminate". */
    public void postProgress(String reqId, int percent, String message) {
        if (reqId == null) {
            return;
        }
        final String js = "if(window.fmProgress)window.fmProgress("
                + org.json.JSONObject.quote(reqId) + "," + percent + ","
                + org.json.JSONObject.quote(message == null ? "" : message) + ");";
        runOnUiThread(() -> evaluate(js));
    }

    private void evaluate(String js) {
        WebView view = webView;
        if (view == null) {
            return;
        }
        try {
            view.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
            // the page may already be gone
        }
    }

    public String serverBase() {
        return server == null ? "" : server.baseUrl();
    }

    public String serverToken() {
        return server == null ? "" : server.token();
    }

    public String videoCacheDir() {
        File dir = new File(getCacheDir(), "video");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir.getAbsolutePath();
    }

    // --------------------------------------------------------------- permissions

    public void requestAllFilesAccess() {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT < 30) {
                requestMediaPermissions();
                return;
            }
            if (android.os.Environment.isExternalStorageManager()) {
                toast("이미 모든 파일에 접근할 수 있습니다.");
                return;
            }
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception second) {
                    toast("설정 화면을 열지 못했습니다. 설정 → 앱 → NAS 파일매니저에서 허용해 주세요.");
                }
            }
        });
    }

    public void requestMediaPermissions() {
        List<String> wanted = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (wanted.isEmpty()) {
            return;
        }
        runOnUiThread(() -> requestPermissions(wanted.toArray(new String[0]), REQ_MEDIA_PERMISSION));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA_PERMISSION) {
            evaluate("if(window.fmOnPermissionResult)window.fmOnPermissionResult();");
        }
    }

    public void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast("설정 화면을 열지 못했습니다.");
        }
    }

    // --------------------------------------------------------------- file actions

    /** Opens a photo/video/document with the app the user picked (MX플레이어 등). */
    public void openWithExternalApp(File file, String mime) {
        if (file == null || !file.isFile()) {
            return;
        }
        runOnUiThread(() -> {
            try {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                String type = mime == null || mime.isEmpty() ? "*/*" : mime;
                intent.setDataAndType(uri, type);
                intent.setClipData(ClipData.newRawUri(file.getName(), uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(intent, "열기"));
            } catch (ActivityNotFoundException e) {
                toast("이 파일을 열 수 있는 앱이 없습니다.");
            } catch (Exception e) {
                Log.w(TAG, "open failed", e);
                toast("파일을 열지 못했습니다.");
            }
        });
    }

    public void shareFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        runOnUiThread(() -> {
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                for (File file : files) {
                    uris.add(FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", file));
                }
                Intent intent;
                if (uris.size() == 1) {
                    intent = new Intent(Intent.ACTION_SEND);
                    intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    intent.setType(mimeOf(files.get(0)));
                } else {
                    intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    intent.setType("*/*");
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "공유"));
            } catch (Exception e) {
                Log.w(TAG, "share failed", e);
                toast("공유할 앱을 찾지 못했습니다.");
            }
        });
    }

    private static String mimeOf(File file) {
        return Core.mimeOf(file.getName());
    }

    /** Lets the gallery see a file the app created or moved. */
    public void scanMedia(File file) {
        if (file == null) {
            return;
        }
        try {
            MediaScannerConnection.scanFile(this,
                    new String[]{file.getAbsolutePath()},
                    new String[]{Core.mimeOf(file.getName())}, null);
        } catch (Exception ignored) {
            // indexing is best effort
        }
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // -------------------------------------------------------------- JS interfaces

    /**
     * window.NasBridge.call(reqId, action, paramsJson) — the page awaits the
     * fmCallback that NasBridge posts back.
     */
    public class NasJs {
        @JavascriptInterface
        public void call(String reqId, String action, String params) {
            nasBridge.call(reqId, action, params);
        }

        @JavascriptInterface
        public void clearThumbCache() {
            runIo(() -> {
                if (nasBridge != null) {
                    nasBridge.clearThumbCache();
                }
            });
        }

        @JavascriptInterface
        public String thumbCacheSize() {
            return nasBridge == null ? "0" : String.valueOf(nasBridge.thumbCacheSize());
        }
    }

    /** window.LocalFileBridge.call(reqId, action, paramsJson). */
    public class LocalJs {
        @JavascriptInterface
        public void call(String reqId, String action, String params) {
            localBridge.call(reqId, action, params);
        }

        @JavascriptInterface
        public void requestAllFiles() {
            requestAllFilesAccess();
        }

        @JavascriptInterface
        public void requestMedia() {
            requestMediaPermissions();
        }

        @JavascriptInterface
        public void openAppSettings() {
            MainActivity.this.openAppSettings();
        }

        @JavascriptInterface
        public String thumbCacheSize() {
            return String.valueOf(server == null ? 0 : server.thumbCacheSize());
        }

        @JavascriptInterface
        public void clearThumbCache() {
            runIo(() -> {
                if (server != null) {
                    server.clearThumbCache();
                }
            });
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(BACK_JS, value -> {
            boolean consumed = value != null && value.contains("1");
            if (!consumed) {
                runOnUiThread(MainActivity.this::finish);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) {
            evaluate("if(window.fmOnResume)window.fmOnResume();");
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) {
            server.stop();
        }
        if (ioPool != null) {
            ioPool.shutdownNow();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
