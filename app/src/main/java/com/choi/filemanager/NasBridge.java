package com.choi.filemanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The Synology DSM File Station client behind window.NasBridge.
 *
 * A file:// WebView cannot call the NAS itself: fetch() from that origin is blocked by
 * CORS, an https NAS with a self-signed certificate is refused, and the DSM session has to
 * survive between calls. So every DSM request is made here with HttpURLConnection:
 *
 *   - the session id from SYNO.API.Auth is kept as _sid and appended to each call,
 *   - an optional "ignore certificate" switch installs a trust-all SSL socket factory for
 *     private servers that use a Synology self-signed certificate,
 *   - large transfers stream through without loading the file into memory.
 *
 * Actions arrive as JSON from the page: call(reqId, action, params) and answer through
 * MainActivity.postResult(reqId, ok, json); long transfers also report postProgress().
 */
public final class NasBridge implements LocalServer.NasSource {

    private static final String TAG = "NasFileManager";
    private static final String PREFS = "nas_settings";
    private static final String KEY_SERVERS = "saved_servers";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_LAST_ID = "last_account";
    private static final String KEY_LAST_PW = "last_password";
    private static final String KEY_LAST_SSL = "last_ignore_ssl";
    private static final String KEY_AUTOLOGIN = "auto_login";

    private static final int TIMEOUT_CONNECT = 15000;
    private static final int TIMEOUT_READ = 60000;
    private static final String ADDITIONAL =
            "[\"real_path\",\"size\",\"owner\",\"time\",\"type\",\"perm\"]";

    private final MainActivity activity;
    private final Context context;

    private volatile String baseUrl = "";
    private volatile String sid = "";
    private volatile String account = "";
    private volatile String serverName = "";
    private volatile boolean ignoreSsl = true;
    private volatile boolean connected = false;

    private static SSLSocketFactory trustAllFactory;
    private static HostnameVerifier trustAllVerifier;

    public NasBridge(MainActivity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    // ------------------------------------------------------------- dispatch

    /** Called from JS as NasBridge.call(reqId, action, paramsJson). */
    public void call(final String reqId, final String action, final String paramsJson) {
        activity.runIo(() -> {
            boolean ok;
            JSONObject payload;
            try {
                JSONObject params = parse(paramsJson);
                switch (action == null ? "" : action) {
                    case "login":
                        payload = doLogin(params, reqId);
                        ok = payload.optBoolean("success", false);
                        break;
                    case "logout":
                        payload = doLogout();
                        ok = true;
                        break;
                    case "status":
                        payload = status();
                        ok = true;
                        break;
                    case "autoLogin":
                        payload = autoLogin();
                        ok = payload.optBoolean("success", false);
                        break;
                    case "apiInfo":
                        payload = apiInfo();
                        ok = true;
                        break;
                    case "savedServers":
                        payload = savedServers();
                        ok = true;
                        break;
                    case "forgetServer":
                        payload = forgetServer(params);
                        ok = true;
                        break;
                    case "listShares":
                        payload = wrap(listShares());
                        ok = true;
                        break;
                    case "list":
                        payload = wrap(list(params));
                        ok = true;
                        break;
                    case "stat":
                        payload = wrap(stat(params));
                        ok = true;
                        break;
                    case "mkdir":
                        payload = wrap(mkdir(params));
                        ok = true;
                        break;
                    case "rename":
                        payload = wrap(rename(params));
                        ok = true;
                        break;
                    case "delete":
                        payload = wrap(delete(params));
                        ok = true;
                        break;
                    case "copyMove":
                        payload = wrap(copyMove(params, reqId));
                        ok = true;
                        break;
                    case "download":
                        payload = download(params, reqId);
                        ok = payload.optBoolean("success", false);
                        break;
                    case "upload":
                        payload = upload(params, reqId);
                        ok = payload.optBoolean("success", false);
                        break;
                    case "openExternal":
                        payload = wrap(openExternal(params));
                        ok = true;
                        break;
                    case "quota":
                        payload = wrap(quota());
                        ok = true;
                        break;
                    default:
                        payload = error(101, "알 수 없는 요청입니다: " + action);
                        ok = false;
                        break;
                }
            } catch (DsmException e) {
                payload = error(e.code, Core.dsmErrorText(e.code));
                ok = false;
            } catch (Exception e) {
                Log.w(TAG, "nas action failed: " + action, e);
                payload = error(-1, "시놀로지와 통신하지 못했습니다. 주소와 네트워크를 확인해 주세요.");
                ok = false;
            }
            activity.postResult(reqId, ok, payload.toString());
        });
    }

    private static JSONObject parse(String json) {
        try {
            return json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject wrap(JSONObject data) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", true);
            out.put("data", data);
        } catch (Exception ignored) {
            // keys are static strings
        }
        return out;
    }

    private static JSONObject error(int code, String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false);
            out.put("error", code);
            out.put("message", message);
        } catch (Exception ignored) {
            // keys are static strings
        }
        return out;
    }

    // ---------------------------------------------------------------- login

    private JSONObject doLogin(JSONObject params, String reqId) throws Exception {
        String rawUrl = params.optString("url", "").trim();
        String user = params.optString("username", "").trim();
        String pass = params.optString("password", "");
        String otp = params.optString("otp", "").trim();
        boolean ssl = params.optBoolean("ignoreSsl", true);
        boolean save = params.optBoolean("save", true);
        boolean autoLogin = params.optBoolean("autoLogin", true);

        if (rawUrl.isEmpty() || user.isEmpty()) {
            return error(400, "서버 주소와 아이디를 입력해 주세요.");
        }
        baseUrl = Core.normalizeServerUrl(rawUrl);
        ignoreSsl = ssl;
        connected = false;
        sid = "";

        JSONObject data = new JSONObject();
        data.put("api", "SYNO.API.Auth");
        data.put("version", "6");
        data.put("method", "login");
        data.put("account", user);
        data.put("passwd", pass);
        data.put("session", "FileStation");
        data.put("format", "sid");
        if (!otp.isEmpty()) {
            data.put("otp_code", otp);
        } else {
            data.put("enable_device_token", "no");
        }

        JSONObject res;
        try {
            res = apiCall("SYNO.API.Auth", 6, "login", data, false);
        } catch (DsmException e) {
            // DSM 6.0 and older only speak Auth v3, and v3 has no otp_code field.
            if (e.code == 103 || e.code == 102 || e.code == 101) {
                data.remove("enable_device_token");
                if (!otp.isEmpty()) {
                    data.put("otp_code", otp);
                }
                res = apiCall("SYNO.API.Auth", 3, "login", data, false);
            } else {
                throw e;
            }
        }

        JSONObject body = res.optJSONObject("data");
        String sessionId = body == null ? "" : body.optString("sid", "");
        if (sessionId.isEmpty()) {
            throw new DsmException(400);
        }
        sid = sessionId;
        account = user;
        connected = true;
        serverName = probeServerName();
        try {
            // Logs which File Station versions this DSM speaks (SYNO.API.Info).
            apiInfo();
        } catch (Exception ignored) {
            // Old firmware may not answer API.Info; every call still works.
        }

        SharedPreferences prefs = prefs();
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_LAST_URL, baseUrl)
                .putString(KEY_LAST_ID, user)
                .putBoolean(KEY_LAST_SSL, ssl)
                .putBoolean(KEY_AUTOLOGIN, autoLogin);
        if (save && !pass.isEmpty()) {
            editor.putString(KEY_LAST_PW, CryptoStore.encrypt(context, pass));
        }
        editor.apply();
        rememberServer(baseUrl, user);

        JSONObject out = status();
        out.put("success", true);
        return out;
    }

    /** Best effort: the DSM prompt calls this the "server name". */
    private String probeServerName() {
        JSONObject res = null;
        try {
            JSONObject data = new JSONObject();
            data.put("type", "storage");
            res = apiCall("SYNO.FileStation.Info", 2, "get", data, true);
        } catch (Exception ignored) {
            return "";
        }
        JSONObject body = res.optJSONObject("data");
        if (body == null) {
            return "";
        }
        String hostname = body.optString("hostname", "");
        return hostname == null ? "" : hostname;
    }

    private JSONObject doLogout() {
        try {
            JSONObject data = new JSONObject();
            data.put("session", "FileStation");
            apiCall("SYNO.API.Auth", 6, "logout", data, true);
        } catch (Exception ignored) {
            // the session may already be gone
        }
        sid = "";
        connected = false;
        account = "";
        return status();
    }

    private JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("connected", connected);
            out.put("baseUrl", baseUrl);
            out.put("account", account);
            out.put("serverName", serverName == null ? "" : serverName);
            out.put("ignoreSsl", ignoreSsl);
            SharedPreferences prefs = prefs();
            out.put("lastUrl", prefs.getString(KEY_LAST_URL, ""));
            out.put("lastAccount", prefs.getString(KEY_LAST_ID, ""));
            out.put("hasSavedPassword", !prefs.getString(KEY_LAST_PW, "").isEmpty());
            out.put("autoLogin", prefs.getBoolean(KEY_AUTOLOGIN, true));
            out.put("servers", savedServers().optJSONArray("servers"));
        } catch (Exception ignored) {
            // static keys
        }
        return out;
    }

    /** Silent sign-in with the password kept in the keystore (설정 → 자동 로그인). */
    private JSONObject autoLogin() throws Exception {
        SharedPreferences prefs = prefs();
        String url = prefs.getString(KEY_LAST_URL, "");
        String user = prefs.getString(KEY_LAST_ID, "");
        String password = CryptoStore.decrypt(context, prefs.getString(KEY_LAST_PW, ""));
        if (!prefs.getBoolean(KEY_AUTOLOGIN, true) || url.isEmpty() || user.isEmpty()
                || password.isEmpty()) {
            JSONObject out = status();
            out.put("success", false);
            out.put("message", "저장된 로그인 정보가 없습니다.");
            return out;
        }
        JSONObject params = new JSONObject();
        params.put("url", url);
        params.put("username", user);
        params.put("password", password);
        params.put("ignoreSsl", prefs.getBoolean(KEY_LAST_SSL, true));
        params.put("save", true);
        params.put("autoLogin", true);
        JSONObject result = doLogin(params, null);
        result.put("auto", true);
        return result;
    }

    private JSONObject savedServers() {
        JSONObject out = new JSONObject();
        JSONArray list = new JSONArray();
        try {
            JSONArray stored = new JSONArray(prefs().getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                list.put(stored.getJSONObject(i));
            }
        } catch (Exception ignored) {
            // a broken list is treated as empty
        }
        try {
            out.put("servers", list);
        } catch (Exception ignored) {
            // static key
        }
        return out;
    }

    private void rememberServer(String url, String user) {
        try {
            JSONArray list = new JSONArray();
            JSONArray stored = new JSONArray(prefs().getString(KEY_SERVERS, "[]"));
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("account", user);
            list.put(entry);
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.getJSONObject(i);
                if (!url.equalsIgnoreCase(item.optString("url"))
                        || !user.equals(item.optString("account"))) {
                    list.put(item);
                }
            }
            prefs().edit().putString(KEY_SERVERS, list.toString()).apply();
        } catch (Exception ignored) {
            // remembering is a convenience only
        }
    }

    private JSONObject forgetServer(JSONObject params) {
        String url = params.optString("url", "");
        try {
            JSONArray stored = new JSONArray(prefs().getString(KEY_SERVERS, "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.getJSONObject(i);
                if (!url.equalsIgnoreCase(item.optString("url"))) {
                    kept.put(item);
                }
            }
            prefs().edit().putString(KEY_SERVERS, kept.toString()).apply();
        } catch (Exception ignored) {
            // nothing to forget
        }
        return savedServers();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- DSM calls

    private static final class DsmException extends Exception {
        final int code;

        DsmException(int code) {
            super("DSM error " + code);
            this.code = code;
        }
    }

    /**
     * One entry.cgi call. {@code data} holds the API parameters; {@code withSid} appends
     * the File Station session so share paths can be used.
     */
    private JSONObject apiCall(String api, int version, String method, JSONObject data,
                               boolean withSid) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl);
        url.append("/webapi/entry.cgi?api=").append(Core.urlEncode(api))
                .append("&version=").append(version)
                .append("&method=").append(Core.urlEncode(method));
        if (data != null) {
            java.util.Iterator<String> it = data.keys();
            while (it.hasNext()) {
                String key = it.next();
                if ("api".equals(key) || "version".equals(key) || "method".equals(key)) {
                    continue;
                }
                url.append('&').append(Core.urlEncode(key)).append('=')
                        .append(Core.urlEncode(data.optString(key)));
            }
        }
        if (withSid) {
            if (sid == null || sid.isEmpty()) {
                throw new DsmException(119);
            }
            url.append("&_sid=").append(Core.urlEncode(sid));
        }

        String body = httpGetString(url.toString());
        if (body == null || body.isEmpty()) {
            throw new IOException("empty response");
        }
        JSONObject json = new JSONObject(body);
        if (!json.optBoolean("success", false)) {
            throw new DsmException(json.optJSONObject("error") == null
                    ? 100 : json.optJSONObject("error").optInt("code", 100));
        }
        return json;
    }

    private String httpGetString(String url) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = open(url);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cookie", "id=" + sid);
            int code = conn.getResponseCode();
            if (code >= 400) {
                // DSM answers 200 for API errors; a 4xx is a proxy/URL problem.
                throw new IOException("HTTP " + code);
            }
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            return bos.toString("UTF-8");
        } finally {
            closeQuietly(in);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_CONNECT);
        conn.setReadTimeout(TIMEOUT_READ);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "NASFileManager/1.0 (Android)");
        if (conn instanceof HttpsURLConnection && ignoreSsl) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(trustAllSocketFactory());
            https.setHostnameVerifier(trustAllHostnameVerifier());
        }
        return conn;
    }

    private static synchronized SSLSocketFactory trustAllSocketFactory() throws Exception {
        if (trustAllFactory == null) {
            TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // The user explicitly allowed a self-signed private server.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // Same as above: trust is a user decision for their own NAS.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, new java.security.SecureRandom());
            trustAllFactory = context.getSocketFactory();
        }
        return trustAllFactory;
    }

    private static synchronized HostnameVerifier trustAllHostnameVerifier() {
        if (trustAllVerifier == null) {
            trustAllVerifier = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };
        }
        return trustAllVerifier;
    }

    private JSONObject apiInfo() throws Exception {
        JSONObject data = new JSONObject();
        data.put("query", "SYNO.API.Auth,SYNO.FileStation.List,SYNO.FileStation.Thumb,"
                + "SYNO.FileStation.Download,SYNO.FileStation.Upload,"
                + "SYNO.FileStation.CreateFolder,SYNO.FileStation.Rename,"
                + "SYNO.FileStation.Delete,SYNO.FileStation.CopyMove,"
                + "SYNO.FileStation.Info");
        JSONObject res = apiCall("SYNO.API.Info", 1, "query", data, false);
        JSONObject body = res.optJSONObject("data");
        JSONObject out = new JSONObject();
        out.put("apis", body == null ? new JSONObject() : body);
        return out;
    }

    // ---------------------------------------------------------------- listing

    private JSONObject listShares() throws Exception {
        JSONObject data = new JSONObject();
        data.put("additional", ADDITIONAL);
        data.put("limit", "500");
        data.put("offset", "0");
        JSONObject res = apiCall("SYNO.FileStation.List", 2, "list_share", data, true);
        JSONObject body = res.optJSONObject("data");
        JSONArray shares = body == null ? new JSONArray() : body.optJSONArray("shares");
        JSONObject out = new JSONObject();
        out.put("path", "/");
        out.put("parent", "/");
        out.put("entries", toEntries(shares, true));
        return out;
    }

    private JSONObject list(JSONObject params) throws Exception {
        String path = Core.normalizePath(params.optString("path", "/"));
        if ("/".equals(path)) {
            // File Station cannot list "/" itself: the root is the list of shared folders.
            JSONObject shares = listShares();
            shares.put("total", shares.optJSONArray("entries") == null
                    ? 0 : shares.optJSONArray("entries").length());
            shares.put("offset", 0);
            return shares;
        }
        JSONArray all = new JSONArray();
        int total = 0;
        int offset = 0;
        int page = 500;
        String sortBy = Core.dsmSortBy(params.optString("sortBy", "name"));
        String direction = Core.dsmSortDirection(params.optString("sortDirection", "asc"));
        String pattern = params.optString("pattern", "");
        for (int guard = 0; guard < 12; guard++) {
            JSONObject data = new JSONObject();
            data.put("folder_path", path);
            data.put("additional", ADDITIONAL);
            data.put("offset", String.valueOf(offset));
            data.put("limit", String.valueOf(page));
            data.put("sort_by", sortBy);
            data.put("sort_direction", direction);
            if (!pattern.isEmpty()) {
                data.put("pattern", pattern);
            }
            JSONObject res = apiCall("SYNO.FileStation.List", 2, "list", data, true);
            JSONObject body = res.optJSONObject("data");
            JSONArray chunk = body == null ? null : body.optJSONArray("files");
            if (body != null) {
                total = body.optInt("total", total);
            }
            if (chunk == null || chunk.length() == 0) {
                break;
            }
            JSONArray mapped = toEntries(chunk, false);
            for (int i = 0; i < mapped.length(); i++) {
                all.put(mapped.get(i));
            }
            offset += chunk.length();
            if (chunk.length() < page || offset >= total) {
                break;
            }
        }

        JSONObject out = new JSONObject();
        out.put("path", path);
        out.put("parent", Core.parentOf(path));
        out.put("entries", all);
        out.put("total", Math.max(total, all.length()));
        out.put("offset", 0);
        return out;
    }

    private JSONObject stat(JSONObject params) throws Exception {
        JSONObject data = new JSONObject();
        data.put("path", params.optString("path", "/"));
        data.put("additional", "[\"size\",\"time\",\"type\",\"real_path\"]");
        JSONObject res = apiCall("SYNO.FileStation.List", 2, "get", data, true);
        JSONObject body = res.optJSONObject("data");
        JSONArray files = body == null ? null : body.optJSONArray("files");
        JSONObject out = new JSONObject();
        if (files != null && files.length() > 0) {
            JSONArray entries = toEntries(files, false);
            out.put("entry", entries.optJSONObject(0));
        }
        return out;
    }

    /** Normalises a DSM files[]/shares[] array into what the page renders. */
    private JSONArray toEntries(JSONArray source, boolean share) throws Exception {
        JSONArray out = new JSONArray();
        if (source == null) {
            return out;
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject addition = item.optJSONObject("additional");
            String name = item.optString("name", "");
            String path = item.optString("path", "");
            boolean isDir = item.optBoolean("isdir", false);
            JSONObject entry = new JSONObject();
            entry.put("name", name);
            entry.put("path", path);
            entry.put("isDir", isDir);
            entry.put("size", addition == null ? 0 : addition.optLong("size", 0));
            long mtime = 0;
            if (addition != null) {
                JSONObject time = addition.optJSONObject("time");
                if (time != null) {
                    mtime = time.optLong("mtime", 0);
                }
                long size = addition.optLong("size", 0);
                entry.put("size", size);
            }
            entry.put("mtime", mtime * 1000L);
            String kind = isDir ? "folder" : Core.kindOf(name);
            entry.put("kind", kind);
            entry.put("mime", Core.mimeOf(name));
            entry.put("isShare", share);
            out.put(entry);
        }
        return out;
    }

    // ------------------------------------------------------------ operations

    private JSONObject mkdir(JSONObject params) throws Exception {
        String path = Core.normalizePath(params.optString("path", "/"));
        String name = Core.nameOf(path);
        if (name.isEmpty()) {
            name = Core.sanitizeName(params.optString("name", ""));
            if (name.isEmpty()) {
                return new JSONObject();
            }
            path = Core.joinPath(Core.parentOf(path), name);
        }
        JSONObject data = new JSONObject();
        data.put("folder_path", Core.parentOf(path));
        data.put("name", name);
        data.put("force_parent", "true");
        apiCall("SYNO.FileStation.CreateFolder", 2, "create", data, true);
        JSONObject out = new JSONObject();
        out.put("path", path);
        return out;
    }

    private JSONObject rename(JSONObject params) throws Exception {
        String path = params.optString("path", "");
        String newName = Core.sanitizeName(params.optString("newName", ""));
        if (path.isEmpty() || newName.isEmpty()) {
            return new JSONObject();
        }
        JSONObject data = new JSONObject();
        data.put("path", path);
        data.put("name", newName);
        apiCall("SYNO.FileStation.Rename", 2, "rename", data, true);
        JSONObject out = new JSONObject();
        out.put("path", Core.joinPath(Core.parentOf(path), newName));
        return out;
    }

    private JSONObject delete(JSONObject params) throws Exception {
        JSONArray paths = params.optJSONArray("paths");
        if (paths == null || paths.length() == 0) {
            return new JSONObject();
        }
        JSONArray list = new JSONArray();
        for (int i = 0; i < paths.length(); i++) {
            list.put(paths.optString(i));
        }
        JSONObject data = new JSONObject();
        data.put("path", list.toString());
        data.put("recursive", "true");
        apiCall("SYNO.FileStation.Delete", 2, "delete", data, true);
        JSONObject out = new JSONObject();
        out.put("count", list.length());
        return out;
    }

    private JSONObject copyMove(JSONObject params, String reqId) throws Exception {
        JSONArray paths = params.optJSONArray("paths");
        String dest = params.optString("destDir", "");
        boolean move = params.optBoolean("move", false);
        boolean overwrite = params.optBoolean("overwrite", false);
        if (paths == null || paths.length() == 0 || dest.isEmpty()) {
            return new JSONObject();
        }
        JSONArray list = new JSONArray();
        for (int i = 0; i < paths.length(); i++) {
            list.put(paths.optString(i));
        }
        JSONObject data = new JSONObject();
        data.put("path", list.toString());
        data.put("dest_folder_path", dest);
        data.put("overwrite", overwrite ? "true" : "false");
        data.put("remove_src", move ? "true" : "false");
        data.put("accurate_progress", "true");

        JSONObject res = apiCall("SYNO.FileStation.CopyMove", 2, "start", data, true);
        JSONObject body = res.optJSONObject("data");
        String taskId = body == null ? "" : body.optString("taskid", "");
        JSONObject out = new JSONObject();
        out.put("taskId", taskId);
        out.put("count", list.length());

        if (!taskId.isEmpty()) {
            // CopyMove runs as a background task; wait for it so the page can refresh.
            for (int i = 0; i < 600; i++) {
                JSONObject statusData = new JSONObject();
                statusData.put("taskid", taskId);
                JSONObject statusRes = apiCall("SYNO.FileStation.CopyMove", 2, "status",
                        statusData, true);
                JSONObject statusBody = statusRes.optJSONObject("data");
                if (statusBody != null && statusBody.optBoolean("finished", false)) {
                    break;
                }
                activity.postProgress(reqId, -1, move ? "이동 중..." : "복사 중...");
                Thread.sleep(500L);
            }
        }
        return out;
    }

    /**
     * Downloads one NAS file into a phone folder. The caller passes destDir (a real
     * phone path) and gets {savedPath, name, size} back; 0..100 progress is posted.
     */
    private JSONObject download(JSONObject params, String reqId) throws Exception {
        String path = params.optString("path", "");
        String destDir = params.optString("destDir", "");
        String wantedName = Core.sanitizeName(params.optString("name", Core.nameOf(path)));
        if (path.isEmpty()) {
            return error(1000, "다운로드할 경로가 없습니다.");
        }
        File external = activity.getExternalFilesDir(null);
        String fallbackDir = external == null ? activity.getFilesDir().getAbsolutePath()
                : external.getAbsolutePath();
        File dir = new File(destDir.isEmpty() ? fallbackDir : destDir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return error(1104, "저장할 폴더를 만들지 못했습니다.");
        }
        File target = uniqueTarget(dir, wantedName);
        File part = new File(target.getAbsolutePath() + ".part");

        String url = baseUrl + "/webapi/entry.cgi?api=SYNO.FileStation.Download"
                + "&version=2&method=download&mode=open&path=" + Core.urlEncode(path)
                + "&_sid=" + Core.urlEncode(sid);
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        long written = 0;
        try {
            conn = open(url);
            conn.setRequestProperty("Cookie", "id=" + sid);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            in = new BufferedInputStream(conn.getInputStream());
            out = new FileOutputStream(part);
            byte[] buffer = new byte[128 * 1024];
            int read;
            int lastPct = -1;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                written += read;
                if (total > 0) {
                    int pct = (int) Math.min(99, written * 100 / total);
                    if (pct >= lastPct + 3) {
                        lastPct = pct;
                        activity.postProgress(reqId, pct, "내려받는 중... " + pct + "%");
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            if (part.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return error(502, "빈 파일을 받았습니다. 접근 권한을 확인해 주세요.");
            }
            if (!part.renameTo(target)) {
                return error(1001, "파일을 저장하지 못했습니다.");
            }
            activity.scanMedia(target);
            JSONObject outJson = new JSONObject();
            outJson.put("success", true);
            outJson.put("savedPath", target.getAbsolutePath());
            outJson.put("name", target.getName());
            outJson.put("size", target.length());
            return outJson;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (conn != null) {
                conn.disconnect();
            }
            if (part.exists() && !target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    /** Never overwrites: photo (1).jpg beside photo.jpg. */
    private static File uniqueTarget(File dir, String name) {
        String safe = name.isEmpty() ? "file" : name;
        File candidate = new File(dir, safe);
        if (!candidate.exists()) {
            return candidate;
        }
        String ext = Core.extOf(safe);
        String stem = ext.isEmpty() ? safe : safe.substring(0, safe.length() - ext.length() - 1);
        for (int i = 1; i < 10000; i++) {
            File next = new File(dir, stem + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext));
            if (!next.exists()) {
                return next;
            }
        }
        return new File(dir, stem + "-" + System.currentTimeMillis()
                + (ext.isEmpty() ? "" : "." + ext));
    }

    /** Uploads phone files into a NAS folder (SYNO.FileStation.Upload, multipart). */
    private JSONObject upload(JSONObject params, String reqId) throws Exception {
        JSONArray localPaths = params.optJSONArray("localPaths");
        String destDir = params.optString("destDir", "");
        boolean overwrite = params.optBoolean("overwrite", false);
        if (localPaths == null || localPaths.length() == 0 || destDir.isEmpty()) {
            return error(1000, "업로드할 파일과 대상 폴더를 확인해 주세요.");
        }
        int total = localPaths.length();
        int done = 0;
        JSONArray saved = new JSONArray();
        JSONArray failed = new JSONArray();
        for (int i = 0; i < total; i++) {
            String local = localPaths.optString(i);
            File file = new File(local);
            if (!file.isFile()) {
                failed.put(Core.nameOf(local));
                continue;
            }
            try {
                uploadOne(file, destDir, overwrite, reqId, i, total);
                JSONObject item = new JSONObject();
                item.put("name", file.getName());
                item.put("dest", Core.joinPath(destDir, file.getName()));
                saved.put(item);
                done++;
            } catch (DsmException e) {
                failed.put(file.getName() + " (" + Core.dsmErrorText(e.code) + ")");
            } catch (Exception e) {
                failed.put(file.getName() + " (전송 실패)");
            }
        }
        JSONObject out = new JSONObject();
        out.put("success", failed.length() == 0 && done > 0);
        out.put("uploaded", saved);
        out.put("failed", failed);
        out.put("count", done);
        return out;
    }

    private void uploadOne(File file, String destDir, boolean overwrite, String reqId,
                           int index, int total) throws Exception {
        String boundary = "----fm" + System.nanoTime();
        String url = baseUrl + "/webapi/entry.cgi?api=SYNO.FileStation.Upload"
                + "&version=2&method=upload&path=" + Core.urlEncode(destDir)
                + "&create_parents=true&overwrite=" + (overwrite ? "true" : "false")
                + "&_sid=" + Core.urlEncode(sid);

        String safeName = Core.sanitizeName(file.getName());
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + Core.mimeOf(safeName) + "\r\n\r\n").getBytes("UTF-8");
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
        long length = file.length();
        long totalBytes = head.length + length + tail.length;

        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = open(url);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(totalBytes);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Cookie", "id=" + sid);
            conn.setRequestProperty("Connection", "close");

            out = conn.getOutputStream();
            out.write(head);
            in = new FileInputStream(file);
            byte[] buffer = new byte[128 * 1024];
            long sent = 0;
            int read;
            int lastPct = -1;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                sent += read;
                int pct = (int) Math.min(99, sent * 100 / Math.max(1, length));
                if (pct >= lastPct + 3) {
                    lastPct = pct;
                    int overall = (index * 100 + pct) / Math.max(1, total);
                    activity.postProgress(reqId, overall,
                            "올리는 중... " + (index + 1) + "/" + total + " (" + pct + "%)");
                }
            }
            out.write(tail);
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;

            int code = conn.getResponseCode();
            InputStream response = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(response);
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            JSONObject json = body == null || body.trim().isEmpty()
                    ? new JSONObject("{\"success\":true}") : new JSONObject(body.trim());
            if (!json.optBoolean("success", false)) {
                JSONObject err = json.optJSONObject("error");
                throw new DsmException(err == null ? 1001 : err.optInt("code", 1001));
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    /** NAS 파일을 캐시에 받아 외부 플레이어/뷰어로 연다 (MX플레이어 등). */
    private JSONObject openExternal(JSONObject params) throws Exception {
        String path = params.optString("path", "");
        if (path.isEmpty()) {
            return new JSONObject();
        }
        File dir = new File(activity.getCacheDir(), "open");
        if (!dir.exists() && !dir.mkdirs()) {
            return new JSONObject();
        }
        String name = Core.sanitizeName(Core.nameOf(path));
        File target = new File(dir, name);
        if (!target.exists() || target.length() == 0) {
            JSONObject downloadParams = new JSONObject();
            downloadParams.put("path", path);
            downloadParams.put("destDir", dir.getAbsolutePath());
            JSONObject result = download(downloadParams, null);
            String saved = result.optString("savedPath", "");
            if (saved.isEmpty()) {
                return new JSONObject();
            }
            target = new File(saved);
        }
        activity.openWithExternalApp(target, Core.mimeOf(name));
        JSONObject out = new JSONObject();
        out.put("file", target.getAbsolutePath());
        return out;
    }

    private JSONObject quota() throws Exception {
        JSONObject data = new JSONObject();
        JSONObject res = apiCall("SYNO.FileStation.Info", 2, "get", data, true);
        JSONObject body = res.optJSONObject("data");
        JSONObject out = new JSONObject();
        if (body != null && body.optJSONObject("share") != null) {
            JSONObject share = body.optJSONObject("share");
            out.put("total", share.optLong("total_space", 0));
            out.put("free", share.optLong("free_space", 0));
        }
        return out;
    }

    // ---------------------------------------------------------- stream source

    @Override
    public boolean isConnected() {
        return connected && sid != null && !sid.isEmpty() && baseUrl != null && !baseUrl.isEmpty();
    }

    @Override
    public InputStream openStream(String path, String rangeHeader, LocalServer.StreamMeta meta)
            throws Exception {
        if (!isConnected()) {
            meta.code = 503;
            return null;
        }
        String url = baseUrl + "/webapi/entry.cgi?api=SYNO.FileStation.Download"
                + "&version=2&method=download&mode=open&path=" + Core.urlEncode(path)
                + "&_sid=" + Core.urlEncode(sid);
        HttpURLConnection conn = open(url);
        conn.setRequestProperty("Cookie", "id=" + sid);
        if (rangeHeader != null && !rangeHeader.isEmpty()) {
            conn.setRequestProperty("Range", rangeHeader);
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            meta.code = code >= 500 ? 502 : 404;
            conn.disconnect();
            return null;
        }
        meta.code = code == 206 ? 206 : 200;
        meta.contentType = LocalServer.safeType(Core.nameOf(path));
        long length = conn.getContentLengthLong();
        meta.contentLength = length;
        String contentRange = conn.getHeaderField("Content-Range");
        if (contentRange != null) {
            meta.contentRange = contentRange;
        } else if (meta.code == 206 && length > 0) {
            meta.contentRange = "bytes 0-" + (length - 1) + "/" + length;
        }
        return conn.getInputStream();
    }

    /** /nt route: DSM's own thumbnail, disk-cached so scrolling is smooth. */
    @Override
    public byte[] thumbnail(String path, String size) throws Exception {
        if (!isConnected()) {
            return null;
        }
        String safeSize = "small".equals(size) || "large".equals(size) || "original".equals(size)
                ? size : "medium";
        File cacheDir = new File(activity.getCacheDir(), "nasthumbs");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return null;
        }
        File cached = new File(cacheDir, sha1(path + "|" + safeSize) + ".jpg");
        if (cached.isFile() && cached.length() > 0 && System.currentTimeMillis() - cached.lastModified() < 7L * 24 * 3600 * 1000) {
            byte[] hit = readBytes(cached);
            if (hit != null) {
                return hit;
            }
        }
        String url = baseUrl + "/webapi/entry.cgi?api=SYNO.FileStation.Thumb"
                + "&version=2&method=get&size=" + safeSize
                + "&path=" + Core.urlEncode(path) + "&_sid=" + Core.urlEncode(sid);
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = open(url);
            conn.setRequestProperty("Cookie", "id=" + sid);
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            String type = conn.getContentType();
            if (type != null && type.toLowerCase(Locale.US).contains("json")) {
                // "no thumbnail for this file" arrives as a JSON success:false body.
                return null;
            }
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            byte[] bytes = bos.toByteArray();
            if (bytes.length < 100) {
                return null;
            }
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(cached);
                fos.write(bytes);
            } catch (Exception ignored) {
                // caching only
            } finally {
                closeQuietly(fos);
            }
            return bytes;
        } finally {
            closeQuietly(in);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public void clearThumbCache() {
        File dir = new File(activity.getCacheDir(), "nasthumbs");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public long thumbCacheSize() {
        File dir = new File(activity.getCacheDir(), "nasthumbs");
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            total += f.length();
        }
        return total;
    }

    private static byte[] readBytes(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(file.length(), 1 << 20));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }
}
