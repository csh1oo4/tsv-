package com.choi.filemanager;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java helpers shared by the bridges and exercised by the JVM unit tests
 * (app/src/test/java/com/choi/filemanager/CoreTest.java). Nothing here touches the
 * Android framework, so gradle testDebugUnitTest runs it without a device.
 */
public final class Core {

    private Core() {
    }

    // ------------------------------------------------------------------ paths

    /** Turns any mix of separators into a clean POSIX path ("/" root, no trailing slash). */
    public static String normalizePath(String raw) {
        if (raw == null) {
            return "/";
        }
        String p = raw.trim().replace('\\', '/');
        if (p.isEmpty()) {
            return "/";
        }
        StringBuilder sb = new StringBuilder(p.length());
        boolean lastSlash = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '/') {
                if (!lastSlash) {
                    sb.append('/');
                }
                lastSlash = true;
            } else {
                sb.append(c);
                lastSlash = false;
            }
        }
        String out = sb.toString();
        if (out.isEmpty()) {
            return "/";
        }
        if (out.length() > 1 && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.startsWith("/") ? out : "/" + out;
    }

    /** Joins a directory and a name, keeping exactly one slash between them. */
    public static String joinPath(String dir, String name) {
        String d = normalizePath(dir);
        String n = name == null ? "" : name.trim().replace('\\', '/');
        if (n.startsWith("/")) {
            return normalizePath(n);
        }
        if (n.isEmpty()) {
            return d;
        }
        return "/".equals(d) ? "/" + n : d + "/" + n;
    }

    /** "/a/b/c" -> "/a/b", "/a" -> "/", "/" -> "/". */
    public static String parentOf(String path) {
        String p = normalizePath(path);
        int slash = p.lastIndexOf('/');
        if (slash <= 0) {
            return "/";
        }
        return p.substring(0, slash);
    }

    /** "/a/b/c.jpg" -> "c.jpg", "/" -> "". */
    public static String nameOf(String path) {
        String p = normalizePath(path);
        if ("/".equals(p)) {
            return "";
        }
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    /** Lower-case extension without the dot, "" when there is none. */
    public static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static boolean isHiddenName(String name) {
        return name != null && name.startsWith(".") && name.length() > 1;
    }

    // ------------------------------------------------------------------ types

    private static final String[] IMAGE_EXT = {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff",
            "dng", "jfif"
    };
    private static final String[] VIDEO_EXT = {
            "mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "3gpp", "ts", "mts", "m2ts",
            "mpg", "mpeg", "wmv", "flv", "asf", "rm", "rmvb", "ogv"
    };
    private static final String[] AUDIO_EXT = {
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr", "wma"
    };

    public static boolean isImage(String name) {
        return matches(IMAGE_EXT, extOf(name));
    }

    public static boolean isVideo(String name) {
        return matches(VIDEO_EXT, extOf(name));
    }

    public static boolean isAudio(String name) {
        return matches(AUDIO_EXT, extOf(name));
    }

    private static boolean matches(String[] table, String ext) {
        if (ext == null || ext.isEmpty()) {
            return false;
        }
        for (String candidate : table) {
            if (candidate.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /** "image" / "video" / "audio" / "other" — the value the UI filter uses. */
    public static String kindOf(String name) {
        if (isImage(name)) {
            return "image";
        }
        if (isVideo(name)) {
            return "video";
        }
        if (isAudio(name)) {
            return "audio";
        }
        return "other";
    }

    /** A safe Content-Type for the local streaming server (never text/html). */
    public static String mimeOf(String name) {
        String ext = extOf(name);
        switch (ext) {
            case "jpg":
            case "jpeg":
            case "jfif":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "bmp":
                return "image/bmp";
            case "heic":
                return "image/heic";
            case "heif":
                return "image/heif";
            case "avif":
                return "image/avif";
            case "tif":
            case "tiff":
                return "image/tiff";
            case "mp4":
            case "m4v":
                return "video/mp4";
            case "mov":
                return "video/quicktime";
            case "webm":
                return "video/webm";
            case "mkv":
                return "video/x-matroska";
            case "3gp":
            case "3gpp":
                return "video/3gpp";
            case "ts":
            case "mts":
            case "m2ts":
                return "video/mp2t";
            case "avi":
                return "video/x-msvideo";
            case "mp3":
                return "audio/mpeg";
            case "m4a":
                return "audio/mp4";
            case "aac":
                return "audio/aac";
            case "flac":
                return "audio/flac";
            case "wav":
                return "audio/wav";
            case "ogg":
            case "opus":
                return "audio/ogg";
            default:
                return "application/octet-stream";
        }
    }

    // -------------------------------------------------------------- formatting

    /** 0 B / 512 B / 1.5 KB / 24 MB / 3.2 GB — one decimal below 10 units. */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (value >= 10.0 || Math.abs(value - Math.rint(value)) < 0.05) {
            return String.format(Locale.US, "%.0f %s", value, units[unit]);
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    // ------------------------------------------------------------------ names

    /** Removes characters a filesystem or DSM refuses, keeps the extension intact. */
    public static String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return "";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t\\u0000-\\u001f]", "_");
        name = name.replaceAll("\\s+", " ").trim();
        while (name.startsWith(".") && name.length() > 1 && name.replace(".", "").isEmpty()) {
            name = "_";
        }
        if (name.length() > 120) {
            String ext = extOf(name);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String stem = name.substring(0, dot);
                name = stem.substring(0, Math.min(stem.length(), 100)) + "." + ext;
            } else {
                name = name.substring(0, 100);
            }
        }
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "_";
        }
        return name;
    }

    /** "a.jpg" -> "a (1).jpg" when "a.jpg" is already taken. */
    public static String uniqueName(String name, Set<String> existing) {
        String clean = sanitizeName(name);
        if (existing == null || !existing.contains(clean)) {
            return clean;
        }
        String ext = extOf(clean);
        String stem = ext.isEmpty() ? clean : clean.substring(0, clean.length() - ext.length() - 1);
        for (int i = 1; i < 10000; i++) {
            String candidate = stem + " (" + i + ")" + (ext.isEmpty() ? "" : "." + ext);
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
        return stem + "-" + System.currentTimeMillis() + (ext.isEmpty() ? "" : "." + ext);
    }

    // -------------------------------------------------------------- DSM / HTTP

    /** UI sort key -> SYNO.FileStation.List sort_by value. */
    public static String dsmSortBy(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.US);
        switch (k) {
            case "size":
                return "size";
            case "date":
            case "mtime":
            case "time":
                return "mtime";
            case "type":
                return "type";
            default:
                return "name";
        }
    }

    public static String dsmSortDirection(String dir) {
        return "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
    }

    /**
     * Fills in the scheme and the default DSM port so "nas.synology.me" becomes
     * "https://nas.synology.me:5001" and "192.168.0.7" becomes "http://192.168.0.7:5000".
     * A URL that already carries a port or a path is left alone.
     */
    public static String normalizeServerUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        boolean hadScheme = false;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            hadScheme = true;
        } else {
            url = url.contains(":") && !url.contains("/") && looksLikeHostPort(url)
                    ? "http://" + url
                    : "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                return hadScheme ? url : "https://" + raw.trim();
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            boolean plainPath = path.isEmpty() || "/".equals(path);
            if (uri.getPort() < 0 && plainPath) {
                String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
                int port = "http".equalsIgnoreCase(scheme) ? 5000 : 5001;
                return url + ":" + port;
            }
        } catch (Exception ignored) {
            return url;
        }
        return url;
    }

    /** "5001" style input: digits only. */
    private static boolean looksLikeHostPort(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return false;
        }
        for (int i = colon + 1; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Korean explanation for a SYNO error code (File Station / Auth). */
    public static String dsmErrorText(int code) {
        switch (code) {
            case 100:
                return "알 수 없는 오류입니다.";
            case 101:
                return "API를 찾을 수 없습니다.";
            case 102:
                return "이 API는 지원되지 않습니다.";
            case 103:
                return "API 버전이 맞지 않습니다.";
            case 104:
            case 105:
                return "시놀로지에 로그인할 수 없습니다.";
            case 106:
                return "로그인 시간이 만료되었습니다. 다시 로그인해 주세요.";
            case 107:
                return "중복 로그인으로 세션이 종료되었습니다.";
            case 108:
                return "세션이 만료되었습니다. 다시 로그인해 주세요.";
            case 109:
                return "이 계정에는 이 기능을 쓸 권한이 없습니다.";
            case 119:
                return "SID가 없습니다. 다시 로그인해 주세요.";
            case 400:
                return "아이디 또는 비밀번호가 맞지 않습니다.";
            case 401:
                return "계정이 비활성 상태이거나 접속이 제한되어 있습니다.";
            case 402:
                return "이 계정에는 권한이 없습니다.";
            case 403:
                return "2단계 인증(OTP) 코드가 필요합니다.";
            case 404:
                return "OTP 코드가 올바르지 않습니다.";
            case 405:
                return "OTP 코드가 만료되었습니다. 새 코드를 입력해 주세요.";
            case 406:
                return "계정에 2단계 인증이 강제되어 있습니다.";
            case 407:
                return "차단된 IP에서 접속했습니다.";
            case 409:
                return "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.";
            case 410:
                return "비밀번호가 만료되었습니다.";
            case 502:
                return "파일이나 폴더를 찾을 수 없습니다.";
            case 900:
                return "폴더에 접근할 수 없습니다.";
            case 1000:
                return "지정한 경로가 올바르지 않습니다.";
            case 1001:
                return "파일 작업에 실패했습니다.";
            case 1100:
                return "이 공유 폴더를 읽을 권한이 없습니다.";
            case 1101:
                return "공유 폴더가 마운트되지 않았습니다.";
            case 1102:
                return "이 작업은 지원되지 않습니다.";
            case 1103:
                return "같은 이름의 파일이나 폴더가 이미 있습니다.";
            case 1104:
                return "저장 공간이 부족합니다.";
            case 1105:
                return "파일 이름이 올바르지 않습니다.";
            case 1200:
                return "같은 위치로는 복사/이동할 수 없습니다.";
            default:
                return "시놀로지 오류 (코드 " + code + ")";
        }
    }

    // --------------------------------------------------------------- base64url

    public static String b64UrlEncode(String text) {
        if (text == null) {
            return "";
        }
        try {
            byte[] bytes = text.getBytes("UTF-8");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String b64UrlDecode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            int pad = (4 - encoded.length() % 4) % 4;
            StringBuilder sb = new StringBuilder(encoded);
            for (int i = 0; i < pad; i++) {
                sb.append('=');
            }
            byte[] bytes = Base64.getUrlDecoder().decode(sb.toString());
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /** Splits "a,b,c" into a list, dropping blanks. */
    public static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    // --------------------------------------------------------------- http range

    /**
     * Parses a single "bytes=start-end" Range header into {start, end} (inclusive), or null
     * when the request is not a satisfiable byte range and the whole file should be sent.
     * A suffix range ("bytes=-500") means the last 500 bytes.
     */
    public static long[] parseRange(String header, long length) {
        if (header == null || length <= 0) {
            return null;
        }
        String value = header.trim();
        if (!value.toLowerCase(Locale.US).startsWith("bytes=")) {
            return null;
        }
        value = value.substring(6).trim();
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startText = value.substring(0, dash).trim();
            String endText = value.substring(dash + 1).trim();
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? length - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= length || end < start) {
                return null;
            }
            return new long[]{start, Math.min(end, length - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
