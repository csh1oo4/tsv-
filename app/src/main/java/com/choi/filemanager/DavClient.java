package com.choi.filemanager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

/**
 * The WebDAV side of the NAS bridge: one Basic-auth account on a WebDAV base URL, spoken over
 * {@link HttpWire} so PROPFIND / MKCOL / MOVE / COPY / PUT / DELETE all work.
 *
 * Every failure comes back as a {@link DavException} carrying the Korean sentence the page
 * shows, so no caller has to interpret HTTP codes itself.
 */
public final class DavClient {

    private static final int MAX_PROPFIND_BYTES = 16 * 1024 * 1024;

    public interface Progress {
        void onProgress(int percent, String message);
    }

    /** A WebDAV failure with the message the user sees. */
    public static final class DavException extends Exception {
        public final int code;

        DavException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final String base;
    private final String authorization;
    private final HttpWire.Options options;
    private final String displayHost;

    public DavClient(String baseUrl, String user, String password, boolean ignoreSsl,
                     SSLSocketFactory insecureFactory) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.base = trimmed;
        String credentials = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        this.options = new HttpWire.Options();
        if (ignoreSsl && insecureFactory != null) {
            this.options.socketFactory = insecureFactory;
        }
        String host = base;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        this.displayHost = host;
    }

    public String base() {
        return base;
    }

    public String host() {
        return displayHost;
    }

    // ---------------------------------------------------------------- login

    /**
     * PROPFIND Depth 0 on the base URL. 207 (and the rare 200) means the account works;
     * 401 means the password is wrong, and a socket failure means the address is wrong.
     */
    public String login() throws DavException {
        HttpWire.Response response = propfind("/", 0);
        try {
            if (response.code == 401) {
                throw new DavException(400, "아이디 또는 비밀번호가 올바르지 않습니다.");
            }
            if (response.code == 403) {
                throw new DavException(402, "이 계정에는 WebDAV 접속 권한이 없습니다. "
                        + "DSM 제어판 → 응용 프로그램 권한에서 WebDAV를 허용해 주세요.");
            }
            if (response.code == 404) {
                throw new DavException(502, "WebDAV 경로를 찾을 수 없습니다. 주소와 포트(예: 5005)를 "
                        + "확인해 주세요.");
            }
            if (response.code == 405 || response.code == 501) {
                throw new DavException(1102, "이 주소는 WebDAV를 지원하지 않습니다. DSM 제어판의 "
                        + "WebDAV Server가 켜져 있는지, 포트가 5005/5006인지 확인해 주세요.");
            }
            if (response.code != 207 && response.code != 200) {
                throw new DavException(-1, "WebDAV 로그인에 실패했습니다. (HTTP "
                        + response.code + ")");
            }
            String body = response.readText(MAX_PROPFIND_BYTES);
            DavXml.Entry root = DavXml.first(body, base);
            if (root != null && !root.name.isEmpty() && !"/".equals(root.name)) {
                return root.name;
            }
            return displayHost;
        } finally {
            response.close();
        }
    }

    // --------------------------------------------------------------- listing

    /** Depth 1 PROPFIND: the folder's own entry is dropped and paths come back page-relative. */
    public JSONArray list(String path) throws DavException {
        String clean = Core.normalizePath(path);
        HttpWire.Response response = propfind(clean, 1);
        try {
            if (response.code == 401) {
                throw new DavException(400, "아이디 또는 비밀번호가 올바르지 않습니다.");
            }
            if (response.code == 404) {
                throw new DavException(502, "폴더를 찾을 수 없습니다. 새로고침해 주세요.");
            }
            if (response.code != 207 && response.code != 200) {
                throw new DavException(httpCode(response.code), httpText(response.code));
            }
            String body = response.readText(MAX_PROPFIND_BYTES);
            List<DavXml.Entry> entries = DavXml.parse(body, base);
            JSONArray out = new JSONArray();
            for (DavXml.Entry entry : entries) {
                if (entry == null || entry.path.isEmpty()) {
                    continue;
                }
                if (Core.normalizePath(entry.path).equals(clean)) {
                    continue;
                }
                if (Core.isHiddenName(entry.name) || isSynologyNoise(entry.name)) {
                    continue;
                }
                try {
                    out.put(toJson(entry));
                } catch (Exception ignored) {
                    // a malformed entry is skipped, not fatal
                }
            }
            return out;
        } finally {
            response.close();
        }
    }

    /**
     * DSM hides these in File Station; WebDAV hands them over, so the list filters them here:
     * thumbnail/index folders and the recycle bin.
     */
    private static boolean isSynologyNoise(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return "@eadir".equals(lower) || "@tmp".equals(lower) || "@recycle".equals(lower)
                || "#recycle".equals(lower) || "#snapshot".equals(lower)
                || "#recycle_".equals(lower);
    }

    private JSONObject toJson(DavXml.Entry entry) {
        JSONObject json = new JSONObject();
        try {
            json.put("name", entry.name);
            json.put("path", Core.normalizePath(entry.path));
            json.put("isDir", entry.dir);
            json.put("size", entry.dir ? 0L : entry.size);
            json.put("mtime", entry.mtime);
            json.put("kind", entry.dir ? "folder" : Core.kindOf(entry.name));
            json.put("mime", entry.contentType == null || entry.contentType.isEmpty()
                    ? Core.mimeOf(entry.name) : entry.contentType);
            json.put("isShare", Core.normalizePath(entry.path).split("/").length <= 2);
        } catch (Exception ignored) {
            // static keys
        }
        return json;
    }

    /** One folder or file, as the page's `stat` action expects. */
    public JSONObject stat(String path) throws DavException {
        String clean = Core.normalizePath(path);
        HttpWire.Response response = propfind(clean, 0);
        try {
            if (response.code == 404) {
                throw new DavException(502, "파일이나 폴더를 찾을 수 없습니다.");
            }
            if (response.code != 207 && response.code != 200) {
                throw new DavException(httpCode(response.code), httpText(response.code));
            }
            List<DavXml.Entry> entries = DavXml.parse(response.readText(MAX_PROPFIND_BYTES), base);
            JSONObject out = new JSONObject();
            if (!entries.isEmpty()) {
                try {
                    out.put("entry", toJson(entries.get(0)));
                } catch (Exception ignored) {
                    // an unreadable propstat leaves the answer empty
                }
            }
            return out;
        } finally {
            response.close();
        }
    }

    // ------------------------------------------------------------ operations

    /** MKCOL — the folder must not exist yet. */
    public void mkdir(String path) throws DavException {
        String clean = Core.normalizePath(path);
        if ("/".equals(clean)) {
            return;
        }
        HttpWire.Response response = send("MKCOL", clean, "0", null, null, null);
        try {
            if (response.code == 405) {
                throw new DavException(1103, "같은 이름의 폴더가 이미 있습니다.");
            }
            if (response.code == 409) {
                throw new DavException(1000, "상위 폴더가 없습니다. 새로고침 후 다시 시도해 주세요.");
            }
            if (response.code == 401 || response.code == 403) {
                throw new DavException(402, "이 폴더를 만들 권한이 없습니다.");
            }
            if (response.code < 200 || response.code >= 300) {
                throw new DavException(httpCode(response.code), httpText(response.code));
            }
        } finally {
            response.close();
        }
    }

    /** MOVE to a sibling name inside the same folder. */
    public void rename(String path, String newName) throws DavException {
        String clean = Core.normalizePath(path);
        String name = Core.sanitizeName(newName);
        if (clean.isEmpty() || name.isEmpty()) {
            return;
        }
        String target = Core.joinPath(Core.parentOf(clean), name);
        if (target.equals(clean)) {
            return;
        }
        HttpWire.Response response = send("MOVE", clean, "0", null, target, "T");
        try {
            if (response.code == 412) {
                throw new DavException(1103, "같은 이름의 파일이나 폴더가 이미 있습니다.");
            }
            if (response.code < 200 || response.code >= 300) {
                throw new DavException(httpCode(response.code), httpText(response.code));
            }
        } finally {
            response.close();
        }
    }

    /** COPY / MOVE of several paths into one folder. */
    public void copyMove(JSONArray paths, String destDir, boolean move, boolean overwrite,
                         Progress progress) throws DavException {
        String folder = Core.normalizePath(destDir);
        if (paths == null || paths.length() == 0) {
            return;
        }
        int total = paths.length();
        for (int i = 0; i < total; i++) {
            String from = Core.normalizePath(paths.optString(i));
            if (from.isEmpty()) {
                continue;
            }
            String target = Core.joinPath(folder, Core.nameOf(from));
            if (progress != null) {
                progress.onProgress(-1, (move ? "이동 중... " : "복사 중... ")
                        + (i + 1) + "/" + total);
            }
            HttpWire.Response response = send(move ? "MOVE" : "COPY", from, "infinity", null,
                    target, overwrite ? "T" : "F");
            try {
                if (response.code == 412 && !overwrite) {
                    throw new DavException(1103, Core.nameOf(from)
                            + " — 같은 이름의 파일이나 폴더가 이미 있습니다.");
                }
                if (response.code == 409) {
                    throw new DavException(1000, "대상 폴더를 찾을 수 없습니다.");
                }
                if (response.code < 200 || response.code >= 300) {
                    throw new DavException(httpCode(response.code), httpText(response.code));
                }
            } finally {
                response.close();
            }
        }
    }

    /** DELETE — WebDAV deletes a collection together with everything inside it. */
    public void delete(String path) throws DavException {
        String clean = Core.normalizePath(path);
        if ("/".equals(clean)) {
            throw new DavException(1105, "루트 폴더는 지울 수 없습니다.");
        }
        HttpWire.Response response = send("DELETE", clean, "infinity", null, null, null);
        try {
            if (response.code == 404) {
                throw new DavException(502, "이미 지워졌거나 찾을 수 없는 항목입니다.");
            }
            if (response.code == 423) {
                throw new DavException(1102, "다른 곳에서 쓰고 있는 파일입니다. 잠시 후 다시 시도해 주세요.");
            }
            if (response.code < 200 || response.code >= 300) {
                throw new DavException(httpCode(response.code), httpText(response.code));
            }
        } finally {
            response.close();
        }
    }

    /** PUT one phone file into a NAS folder, reporting 0..100 as it goes. */
    public void put(File file, String destPath, boolean overwrite, Progress progress)
            throws DavException {
        final File source = file;
        final String target = Core.normalizePath(destPath);
        if (!overwrite && exists(target)) {
            // PUT replaces without asking, so the "do not overwrite" promise is kept here.
            throw new DavException(1103, file.getName() + " — 같은 이름의 파일이 이미 있습니다.");
        }
        final long length = file.length();
        final Progress reporter = progress;
        HttpWire.Body body = new HttpWire.Body() {
            @Override
            public long length() {
                return length;
            }

            @Override
            public void writeTo(OutputStream out) throws Exception {
                InputStream in = null;
                try {
                    in = new FileInputStream(source);
                    byte[] buffer = new byte[128 * 1024];
                    long sent = 0;
                    int last = -1;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        sent += read;
                        int percent = (int) Math.min(99, sent * 100 / Math.max(1L, length));
                        if (percent >= last + 3) {
                            last = percent;
                            if (reporter != null) {
                                reporter.onProgress(percent, "올리는 중... " + percent + "%");
                            }
                        }
                    }
                } finally {
                    HttpWire.closeQuietly(in);
                }
            }
        };
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", Core.mimeOf(file.getName()));
        headers.put("Overwrite", overwrite ? "T" : "F");
        HttpWire.Response response = send("PUT", Core.normalizePath(destPath), null, body,
                null, null, headers);
        try {
            if (response.code == 412) {
                throw new DavException(1103, "같은 이름의 파일이 이미 있습니다.");
            }
            if (response.code == 409) {
                throw new DavException(1000, "대상 폴더가 없습니다.");
            }
            if (response.code == 507) {
                throw new DavException(1104, "NAS의 저장 공간이 부족합니다.");
            }
            if (response.code < 200 || response.code >= 300) {
                throw new DavException(httpCode(response.code), httpText(response.code));
            }
            if (progress != null) {
                progress.onProgress(100, "완료");
            }
        } finally {
            response.close();
        }
    }

    /** GET into a local file, reporting 0..100. */
    public void download(String path, File target, Progress progress) throws DavException {
        HttpWire.Response response = get(path, null);
        FileOutputStream out = null;
        File part = new File(target.getAbsolutePath() + ".part");
        try {
            if (response.code != 200 && response.code != 206) {
                throw new DavException(httpCode(response.code), httpText(response.code));
            }
            long total = response.contentLength();
            out = new FileOutputStream(part);
            byte[] buffer = new byte[128 * 1024];
            long written = 0;
            int last = -1;
            int read;
            while ((read = response.stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                written += read;
                if (total > 0 && progress != null) {
                    int percent = (int) Math.min(99, written * 100 / total);
                    if (percent >= last + 3) {
                        last = percent;
                        progress.onProgress(percent, "내려받는 중... " + percent + "%");
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            if (part.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new DavException(502, "빈 파일을 받았습니다. 접근 권한을 확인해 주세요.");
            }
            if (!part.renameTo(target)) {
                throw new DavException(1001, "파일을 저장하지 못했습니다.");
            }
            if (progress != null) {
                progress.onProgress(100, "완료");
            }
        } catch (IOException e) {
            throw new DavException(1001, "파일을 저장하지 못했습니다.");
        } finally {
            HttpWire.closeQuietly(out);
            response.close();
            if (part.exists() && !target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    /** An open GET stream (Range passed through) for the loopback server's /s route. */
    public HttpWire.Response get(String path, String rangeHeader) throws DavException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (rangeHeader != null && !rangeHeader.isEmpty()) {
            headers.put("Range", rangeHeader);
        }
        HttpWire.Response response = send("GET", Core.normalizePath(path), null, null, null, null,
                headers);
        if (response.code == 401 || response.code == 403) {
            response.close();
            throw new DavException(402, "이 파일을 읽을 권한이 없습니다.");
        }
        if (response.code == 404) {
            response.close();
            throw new DavException(502, "파일을 찾을 수 없습니다.");
        }
        return response;
    }

    /** The bytes of one image, capped, for the thumbnail cache. */
    public byte[] readImage(String path, long maxBytes) throws DavException {
        HttpWire.Response response = get(path, null);
        try {
            if (response.code != 200) {
                return null;
            }
            long length = response.contentLength();
            if (length > maxBytes) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    (int) Math.min(Math.max(length, 8192), 1 << 20));
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = response.stream.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    return null;
                }
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            response.close();
        }
    }

    // ------------------------------------------------------------- plumbing

    /** PROPFIND Depth 0 on one path: true when the server knows it. */
    private boolean exists(String path) throws DavException {
        HttpWire.Response response = propfind(path, 0);
        try {
            return response.code == 207 || response.code == 200;
        } finally {
            response.close();
        }
    }

    private HttpWire.Response propfind(String path, int depth) throws DavException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/xml; charset=utf-8");
        return send("PROPFIND", path, String.valueOf(depth), HttpWire.text(DavXml.PROPFIND_BODY),
                null, null, headers);
    }

    private HttpWire.Response send(String method, String path, String depth, HttpWire.Body body,
                                   String destination, String overwrite) throws DavException {
        return send(method, path, depth, body, destination, overwrite, null);
    }

    private HttpWire.Response send(String method, String path, String depth, HttpWire.Body body,
                                   String destination, String overwrite,
                                   Map<String, String> extra) throws DavException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", authorization);
        if (depth != null) {
            headers.put("Depth", depth);
        }
        if (destination != null && !destination.isEmpty()) {
            // Destination is an absolute URL: MOVE/COPY only works with the full path.
            headers.put("Destination", Core.davUrl(base, destination));
            headers.put("Overwrite", overwrite == null ? "T" : overwrite);
        }
        if (extra != null) {
            headers.putAll(extra);
        }
        String url = Core.davUrl(base, path);
        try {
            return HttpWire.execute(method, url, headers, body, options);
        } catch (UnknownHostException e) {
            throw new DavException(-2, "주소를 찾지 못했습니다. 주소를 확인해 주세요.");
        } catch (ConnectException e) {
            throw new DavException(-2, "서버에 연결할 수 없습니다. 주소와 포트(예: 5005), "
                    + "방화벽을 확인해 주세요.");
        } catch (SocketTimeoutException e) {
            throw new DavException(-4, "서버가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.");
        } catch (SSLException e) {
            throw portHint(e);
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.US);
            if (message.contains("ssl") || message.contains("certificate")) {
                throw portHint(e);
            }
            throw new DavException(-2, "서버와 통신하지 못했습니다. 주소와 포트(예: 5005)를 "
                    + "확인해 주세요.");
        }
    }

    private DavException portHint(Exception e) {
        String hint = base.startsWith("https")
                ? "자체 서명 인증서 허용을 켜거나 http:// 주소와 5005 포트를 써 보세요."
                : "https:// 주소라면 5006 포트를, http:// 주소라면 5005 포트를 확인해 주세요.";
        return new DavException(-3, "보안 연결을 맺지 못했습니다. " + hint);
    }

    private int httpCode(int code) {
        if (code == 412) {
            return 1103;
        }
        if (code == 507) {
            return 1104;
        }
        if (code == 403) {
            return 402;
        }
        if (code == 404) {
            return 502;
        }
        return 1001;
    }

    private String httpText(int code) {
        switch (code) {
            case 401:
                return "아이디 또는 비밀번호가 올바르지 않습니다.";
            case 403:
                return "이 계정에는 권한이 없습니다.";
            case 404:
                return "파일이나 폴더를 찾을 수 없습니다.";
            case 405:
                return "서버가 이 작업을 허용하지 않습니다.";
            case 409:
                return "상위 폴더가 없거나 이름이 올바르지 않습니다.";
            case 412:
                return "같은 이름의 파일이나 폴더가 이미 있습니다.";
            case 423:
                return "다른 곳에서 쓰고 있는 파일입니다.";
            case 507:
                return "NAS의 저장 공간이 부족합니다.";
            default:
                return "WebDAV 오류 (HTTP " + code + ")";
        }
    }
}
