package com.choi.filemanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny loopback-only HTTP server that lets the WebView show NAS photos, play NAS
 * videos and preview phone media with plain &lt;img&gt;/&lt;video&gt; tags.
 *
 * A file:// page cannot carry the DSM session cookie, cannot forward a Range header and
 * cannot hold a 4 GB video in memory, so every byte the page displays is handed to it by
 * this server on 127.0.0.1 with a random port and a per-run token:
 *
 *   /s?p=&lt;b64url path&gt;   NAS original — proxied to DSM's download API or a WebDAV GET,
 *                          Range passed straight through (206 + Content-Range)
 *   /nt?p=&lt;b64url path&gt;  NAS thumbnail — DSM's Thumb API, or a scaled WebDAV image
 *   /f?p=&lt;b64url path&gt;   phone file — served from disk with Range support
 *   /lt?p=&lt;b64url path&gt;  phone thumbnail — generated once and cached in cache/thumbs
 *   /ping                  "ok", used by the page to check the server is alive
 *
 * The socket binds to the loopback address only, so nothing outside the phone can reach
 * it; the token keeps other apps on the same phone from reading the user's files through it.
 */
public final class LocalServer {

    private static final String TAG = "NasFileManager";
    private static final int THREADS = 4;

    /** What the NAS bridge has to provide for the /s and /nt routes. */
    public interface NasSource {
        /**
         * Opens the DSM download stream for {@code path}. The caller owns the returned
         * stream, closes it, and has already received the status/type/length via meta.
         */
        InputStream openStream(String path, String rangeHeader, StreamMeta meta) throws Exception;

        byte[] thumbnail(String path, String size) throws Exception;

        boolean isConnected();
    }

    /** Response shape filled in by the NAS bridge before the body is copied. */
    public static final class StreamMeta {
        public int code = 200;
        public String contentType = "application/octet-stream";
        public long contentLength = -1;
        public String contentRange = null;
    }

    private final Context context;
    private final String token;
    private final File thumbDir;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile NasSource nas;
    private int port = -1;

    public LocalServer(Context context) {
        this.context = context.getApplicationContext();
        this.token = randomToken();
        this.thumbDir = new File(context.getCacheDir(), "thumbs");
        if (!thumbDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            thumbDir.mkdirs();
        }
    }

    public void setNasSource(NasSource source) {
        this.nas = source;
    }

    public String token() {
        return token;
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return port > 0 ? "http://127.0.0.1:" + port : "";
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Starts once; safe to call from onCreate and again after a resume. */
    public synchronized int start() {
        if (running.get() && port > 0) {
            return port;
        }
        try {
            ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            socket.setReuseAddress(true);
            serverSocket = socket;
            port = socket.getLocalPort();
            pool = Executors.newFixedThreadPool(THREADS);
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "fm-http-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.d(TAG, "local server on " + baseUrl());
            return port;
        } catch (Exception e) {
            Log.w(TAG, "local server failed to start", e);
            running.set(false);
            port = -1;
            return -1;
        }
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
            // closing anyway
        }
        serverSocket = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        port = -1;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                ExecutorService executor = pool;
                if (executor == null) {
                    closeQuietly(client);
                    continue;
                }
                executor.execute(() -> handle(client));
            } catch (Exception e) {
                if (running.get()) {
                    Log.w(TAG, "accept failed", e);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------- request

    private void handle(Socket client) {
        OutputStream out = null;
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            Map<String, String> headers = readHeaders(in);
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            out = client.getOutputStream();
            if (!"GET".equalsIgnoreCase(parts[0]) && !"HEAD".equalsIgnoreCase(parts[0])) {
                writeEmpty(out, 405, "text/plain");
                return;
            }
            boolean headOnly = "HEAD".equalsIgnoreCase(parts[0]);

            Uri uri = Uri.parse("http://127.0.0.1" + parts[1]);
            String route = uri.getPath() == null ? "" : uri.getPath();
            if (!token.equals(uri.getQueryParameter("t"))) {
                writeEmpty(out, 403, "text/plain");
                return;
            }
            String path = Core.b64UrlDecode(uri.getQueryParameter("p"));

            if ("/ping".equals(route)) {
                byte[] body = "ok".getBytes("UTF-8");
                writeHeaders(out, 200, "text/plain; charset=utf-8", body.length, null);
                if (!headOnly) {
                    out.write(body);
                }
                return;
            }
            if ("/f".equals(route)) {
                serveLocalFile(out, path, headers.get("range"), headOnly);
                return;
            }
            if ("/lt".equals(route)) {
                serveLocalThumb(out, path, intParam(uri, "s", 256));
                return;
            }
            if ("/s".equals(route)) {
                serveNasStream(out, path, headers.get("range"), headOnly);
                return;
            }
            if ("/nt".equals(route)) {
                serveNasThumb(out, path, uri.getQueryParameter("s"));
                return;
            }
            writeEmpty(out, 404, "text/plain");
        } catch (Exception e) {
            Log.d(TAG, "request failed: " + e);
        } finally {
            try {
                if (out != null) {
                    out.flush();
                }
            } catch (Exception ignored) {
                // client went away
            }
            closeQuietly(client);
        }
    }

    private void serveLocalFile(OutputStream out, String path, String range, boolean headOnly)
            throws IOException {
        File file = new File(path);
        if (path.isEmpty() || !file.isFile()) {
            writeEmpty(out, 404, "text/plain");
            return;
        }
        long length = file.length();
        long start = 0;
        long end = length - 1;
        boolean partial = false;
        long[] span = parseRange(range, length);
        if (span != null) {
            start = span[0];
            end = span[1];
            partial = true;
        }
        if (length <= 0) {
            writeHeaders(out, 200, safeType(file.getName()), 0, null);
            return;
        }
        long count = end - start + 1;
        String extra = partial
                ? "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n"
                : null;
        writeHeaders(out, partial ? 206 : 200, safeType(file.getName()), count, extra);
        if (headOnly) {
            return;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = count;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, want);
                if (read <= 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                    // nothing left to do
                }
            }
        }
    }

    private void serveLocalThumb(OutputStream out, String path, int size) throws IOException {
        File file = new File(path);
        if (path.isEmpty() || !file.exists()) {
            writeEmpty(out, 404, "text/plain");
            return;
        }
        byte[] jpeg = buildLocalThumb(file, size);
        if (jpeg == null || jpeg.length == 0) {
            writeEmpty(out, 404, "text/plain");
            return;
        }
        writeHeaders(out, 200, "image/jpeg", jpeg.length, "Cache-Control: max-age=86400\r\n");
        out.write(jpeg);
    }

    private void serveNasStream(OutputStream out, String path, String range, boolean headOnly)
            throws IOException {
        NasSource source = nas;
        if (source == null || !source.isConnected()) {
            writeEmpty(out, 503, "text/plain");
            return;
        }
        StreamMeta meta = new StreamMeta();
        InputStream body = null;
        try {
            body = source.openStream(path, range, meta);
            if (body == null) {
                writeEmpty(out, meta.code >= 400 ? meta.code : 502, "text/plain");
                return;
            }
            String extra = meta.contentRange == null ? null
                    : "Content-Range: " + meta.contentRange + "\r\n";
            writeHeaders(out, meta.code, meta.contentType, meta.contentLength, extra);
            if (headOnly) {
                return;
            }
            // Copied as it arrives: a 4 GB video never sits in memory.
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "nas stream failed", e);
            try {
                writeEmpty(out, 502, "text/plain");
            } catch (IOException ignored) {
                // client already gone
            }
        } finally {
            if (body != null) {
                try {
                    body.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
    }

    private void serveNasThumb(OutputStream out, String path, String size) throws IOException {
        NasSource source = nas;
        if (source == null || !source.isConnected()) {
            writeEmpty(out, 503, "text/plain");
            return;
        }
        try {
            byte[] bytes = source.thumbnail(path, size == null ? "medium" : size);
            if (bytes == null || bytes.length == 0) {
                writeEmpty(out, 404, "text/plain");
                return;
            }
            writeHeaders(out, 200, "image/jpeg", bytes.length, "Cache-Control: max-age=86400\r\n");
            out.write(bytes);
        } catch (Exception e) {
            Log.d(TAG, "nas thumb failed", e);
            writeEmpty(out, 404, "text/plain");
        }
    }

    // ------------------------------------------------------------ thumbnails

    private byte[] buildLocalThumb(File file, int size) {
        String key = sha1(file.getAbsolutePath() + "|" + file.lastModified() + "|"
                + file.length() + "|" + size);
        File cached = new File(thumbDir, key + ".jpg");
        if (cached.isFile() && cached.length() > 0) {
            byte[] hit = readAll(cached);
            if (hit != null && hit.length > 0) {
                return hit;
            }
        }
        Bitmap bitmap = null;
        try {
            String name = file.getName();
            if (Core.isVideo(name)) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(file.getAbsolutePath());
                    bitmap = retriever.getFrameAtTime(1_000_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } finally {
                    try {
                        retriever.release();
                    } catch (Exception ignored) {
                        // released best-effort
                    }
                }
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                int longest = Math.max(bounds.outWidth, bounds.outHeight);
                while (longest / (sample * 2) >= size) {
                    sample *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            }
            if (bitmap == null) {
                return null;
            }
            byte[] jpeg = compress(bitmap, size);
            if (jpeg != null) {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(cached);
                    fos.write(jpeg);
                } catch (Exception ignored) {
                    // a cache miss next time is acceptable
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (Exception ignored) {
                            // nothing to do
                        }
                    }
                }
            }
            return jpeg;
        } catch (Throwable t) {
            Log.d(TAG, "thumb failed for " + file.getName() + ": " + t);
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static byte[] compress(Bitmap source, int target) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        double scale = Math.min(1.0, (double) target / Math.max(width, height));
        Bitmap scaled = source;
        if (scale < 1.0) {
            scaled = Bitmap.createScaledBitmap(source,
                    Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 84, bos);
        if (scaled != source) {
            scaled.recycle();
        }
        return bos.toByteArray();
    }

    /** Clears the generated thumbnail cache (설정 → 캐시 비우기). */
    public void clearThumbCache() {
        File[] files = thumbDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public long thumbCacheSize() {
        long total = 0;
        File[] files = thumbDir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            total += f.length();
        }
        return total;
    }

    // ------------------------------------------------------------- http utils

    static long[] parseRange(String header, long length) {
        return Core.parseRange(header, length);
    }

    static void writeHeaders(OutputStream out, int code, String type, long length,
                             String extra) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) {
            sb.append("Content-Length: ").append(length).append("\r\n");
        }
        sb.append("Accept-Ranges: bytes\r\n");
        if (extra == null || !extra.contains("Cache-Control")) {
            sb.append("Cache-Control: no-store\r\n");
        }
        sb.append("Connection: close\r\n");
        if (extra != null) {
            sb.append(extra);
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.flush();
    }

    static void writeEmpty(OutputStream out, int code, String type) throws IOException {
        writeHeaders(out, code, type, 0, null);
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 206:
                return "Partial Content";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 416:
                return "Requested Range Not Satisfiable";
            case 502:
                return "Bad Gateway";
            case 503:
                return "Service Unavailable";
            default:
                return "Error";
        }
    }

    /** Never hands the WebView an HTML/script content type, whatever the file is named. */
    static String safeType(String name) {
        String mime = Core.mimeOf(name);
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
            return mime;
        }
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
            if (bos.size() > 8192) {
                break;
            }
        }
        if (c == -1 && bos.size() == 0) {
            return null;
        }
        return bos.toString("UTF-8");
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 64; i++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static int intParam(Uri uri, String key, int fallback) {
        try {
            String value = uri.getQueryParameter(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static byte[] readAll(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(file.length(), 4 << 20));
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
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

    private static String randomToken() {
        StringBuilder sb = new StringBuilder(32);
        java.util.Random random = new java.util.Random();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
            // already closed
        }
    }
}
