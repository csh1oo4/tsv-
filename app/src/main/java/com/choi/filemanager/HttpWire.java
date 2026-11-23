package com.choi.filemanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A very small HTTP/1.1 client over a plain socket, used for WebDAV.
 *
 * Why not HttpURLConnection: Android's implementation only allows the six standard verbs and
 * throws ProtocolException for PROPFIND, MKCOL, MOVE and COPY — the verbs WebDAV is made of.
 * This class writes the request line itself, so any method works over both http and https,
 * with Basic auth, Range requests, chunked answers and streaming bodies.
 *
 * Every request sends {@code Connection: close} and owns its socket, so nothing is shared
 * between calls and a stuck transfer cannot poison the next one.
 */
public final class HttpWire {

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_LINE = 16 * 1024;
    private static final int MAX_HEADERS = 128;

    private HttpWire() {
    }

    /** A request body that knows its length and can write itself (a file, a small XML string). */
    public interface Body {
        long length();

        void writeTo(OutputStream out) throws Exception;
    }

    public static Body bytes(byte[] data) {
        final byte[] copy = data == null ? new byte[0] : data;
        return new Body() {
            @Override
            public long length() {
                return copy.length;
            }

            @Override
            public void writeTo(OutputStream out) throws Exception {
                out.write(copy);
            }
        };
    }

    public static Body text(String value) {
        return bytes((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /** How a request should be made: timeouts and the TLS trust decision. */
    public static final class Options {
        public int connectTimeout = 15000;
        public int readTimeout = 60000;
        /** Installed when the user allows a self-signed certificate. */
        public SSLSocketFactory socketFactory;
        public String userAgent = "SynoFilePro/1.2 (Android)";
    }

    /** Status line, headers and a body that is read straight off the socket. */
    public static final class Response implements Closeable {
        public int code;
        public String message = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public InputStream stream;
        private Socket socket;

        public String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.US));
            return value == null ? "" : value;
        }

        public long contentLength() {
            try {
                String value = header("Content-Length");
                return value.isEmpty() ? -1L : Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        /** Reads the whole body (for the small PROPFIND answers), at most {@code limit} bytes. */
        public String readText(int limit) {
            if (stream == null) {
                return "";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[8192];
            int total = 0;
            try {
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    int keep = Math.min(read, limit - total);
                    if (keep > 0) {
                        bos.write(buffer, 0, keep);
                        total += keep;
                    }
                    if (total >= limit) {
                        break;
                    }
                }
            } catch (IOException ignored) {
                // a short answer is still an answer
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            closeQuietly(stream);
            stream = null;
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // already gone
                }
                socket = null;
            }
        }
    }

    public static Response execute(String method, String url, Map<String, String> headers,
                                   Body body, Options options) throws IOException {
        Options opts = options == null ? new Options() : options;
        String target = url;
        Map<String, String> sent = headers == null ? new LinkedHashMap<>() : headers;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Response response = once(method, target, sent, body, opts);
            String location = response.header("Location");
            int code = response.code;
            boolean redirect = code == 301 || code == 302 || code == 303 || code == 307
                    || code == 308;
            if (!redirect || location.isEmpty()) {
                return response;
            }
            response.close();
            try {
                target = new URL(new URL(target), location).toString();
            } catch (Exception e) {
                return response;
            }
        }
        throw new IOException("too many redirects");
    }

    private static Response once(String method, String url, Map<String, String> headers,
                                 Body body, Options options) throws IOException {
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (Exception e) {
            throw new IOException("bad url: " + url);
        }
        boolean https = "https".equalsIgnoreCase(parsed.getProtocol());
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("bad url: " + url);
        }
        int port = parsed.getPort() > 0 ? parsed.getPort() : (https ? 443 : 80);
        String file = parsed.getFile();
        if (file == null || file.isEmpty()) {
            file = "/";
        }

        Socket socket;
        if (https) {
            SSLSocketFactory factory = options.socketFactory != null
                    ? options.socketFactory
                    : (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = factory.createSocket();
            socket.connect(new InetSocketAddress(host, port), options.connectTimeout);
            SSLSocket tls = (SSLSocket) socket;
            try {
                SSLParameters params = tls.getSSLParameters();
                params.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
                tls.setSSLParameters(params);
            } catch (Exception ignored) {
                // SNI is a nicety; an IP address has no name to send
            }
            tls.startHandshake();
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), options.connectTimeout);
        }
        socket.setSoTimeout(options.readTimeout);

        Response response = new Response();
        response.socket = socket;
        try {
            OutputStream raw = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            StringBuilder head = new StringBuilder(256);
            head.append(method).append(' ').append(file).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host);
            if (parsed.getPort() > 0 && parsed.getPort() != (https ? 443 : 80)) {
                head.append(':').append(parsed.getPort());
            }
            head.append("\r\n");
            head.append("User-Agent: ").append(options.userAgent).append("\r\n");
            head.append("Accept: */*\r\n");
            head.append("Connection: close\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() == null || header.getValue() == null) {
                    continue;
                }
                String key = header.getKey();
                if ("Host".equalsIgnoreCase(key) || "Connection".equalsIgnoreCase(key)
                        || "Content-Length".equalsIgnoreCase(key)) {
                    continue;
                }
                head.append(key).append(": ").append(header.getValue()).append("\r\n");
            }
            if (body != null) {
                head.append("Content-Length: ").append(body.length()).append("\r\n");
            }
            head.append("\r\n");
            raw.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                body.writeTo(raw);
            }
            raw.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream(), 32 * 1024);
            String status = readLine(in);
            if (status == null || status.isEmpty()) {
                throw new IOException("empty response");
            }
            parseStatus(status, response);
            for (int i = 0; i < MAX_HEADERS; i++) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    response.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            String encoding = response.header("Transfer-Encoding").toLowerCase(Locale.US);
            if ("HEAD".equalsIgnoreCase(method) || response.code == 204 || response.code == 304) {
                response.stream = new EmptyInputStream();
            } else if (encoding.contains("chunked")) {
                response.stream = new ChunkedInputStream(in);
            } else if (response.contentLength() >= 0) {
                response.stream = new LimitedInputStream(in, response.contentLength());
            } else {
                response.stream = in;
            }
            return response;
        } catch (IOException e) {
            response.close();
            throw e;
        } catch (Exception e) {
            response.close();
            throw new IOException(e.getMessage() == null ? "request failed" : e.getMessage());
        }
    }

    /** "HTTP/1.1 207 Multi-Status" -> {207, "Multi-Status"}. */
    static void parseStatus(String line, Response into) {
        String text = line.trim();
        String[] parts = text.split(" ", 3);
        if (parts.length >= 2) {
            try {
                into.code = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                into.code = 0;
            }
        }
        into.message = parts.length >= 3 ? parts[2].trim() : "";
    }

    /** Reads one CRLF (or LF) terminated line; null at end of stream. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
            if (bos.size() > MAX_LINE) {
                break;
            }
        }
        if (c == -1 && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- bodies

    private static final class EmptyInputStream extends InputStream {
        @Override
        public int read() {
            return -1;
        }
    }

    static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedInputStream(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int value = in.read();
            if (value >= 0) {
                left--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int want = (int) Math.min(length, left);
            int read = in.read(buffer, offset, want);
            if (read > 0) {
                left -= read;
            }
            return read;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), left);
        }
    }

    /** Decodes "Transfer-Encoding: chunked" so callers can read it like any other stream. */
    static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long chunkLeft = 0;
        private boolean finished = false;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        private boolean nextChunk() throws IOException {
            if (finished) {
                return false;
            }
            if (chunkLeft > 0) {
                return true;
            }
            String line = readLine(in);
            if (line == null) {
                finished = true;
                return false;
            }
            String size = line.trim();
            int semicolon = size.indexOf(';');
            if (semicolon >= 0) {
                size = size.substring(0, semicolon).trim();
            }
            if (size.isEmpty()) {
                return nextChunk();
            }
            try {
                chunkLeft = Long.parseLong(size, 16);
            } catch (NumberFormatException e) {
                finished = true;
                return false;
            }
            if (chunkLeft == 0) {
                // trailer headers, then the body is done
                for (int guard = 0; guard < MAX_HEADERS; guard++) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) {
                        break;
                    }
                }
                finished = true;
                return false;
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            if (!nextChunk()) {
                return -1;
            }
            int value = in.read();
            if (value >= 0) {
                chunkLeft--;
                if (chunkLeft == 0) {
                    readLine(in);
                }
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!nextChunk()) {
                return -1;
            }
            int want = (int) Math.min(length, chunkLeft);
            int read = in.read(buffer, offset, want);
            if (read > 0) {
                chunkLeft -= read;
                if (chunkLeft == 0) {
                    readLine(in);
                }
            }
            return read;
        }
    }

    static void closeQuietly(Closeable closeable) {
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
