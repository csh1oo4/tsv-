package com.choi.filemanager;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for the helpers the bridges rely on. They need no device: everything
 * tested here is plain Java in Core.
 *
 *   gradle testDebugUnitTest
 */
public class CoreTest {

    @Test
    public void normalizePathHandlesSeparatorsAndRoot() {
        assertEquals("/", Core.normalizePath(null));
        assertEquals("/", Core.normalizePath(""));
        assertEquals("/a/b", Core.normalizePath("a/b/"));
        assertEquals("/a/b", Core.normalizePath("/a//b"));
        assertEquals("/a/b", Core.normalizePath("\\a\\b"));
        assertEquals("/", Core.normalizePath("/"));
    }

    @Test
    public void joinAndSplitPaths() {
        assertEquals("/photo/a.jpg", Core.joinPath("/photo", "a.jpg"));
        assertEquals("/a.jpg", Core.joinPath("/", "a.jpg"));
        assertEquals("/b/c", Core.joinPath("/a", "/b/c"));
        assertEquals("/photo", Core.joinPath("/photo", ""));
        assertEquals("/a", Core.parentOf("/a/b"));
        assertEquals("/", Core.parentOf("/a"));
        assertEquals("/", Core.parentOf("/"));
        assertEquals("b.jpg", Core.nameOf("/a/b.jpg"));
        assertEquals("", Core.nameOf("/"));
    }

    @Test
    public void extensionAndKind() {
        assertEquals("jpg", Core.extOf("A.JPG"));
        assertEquals("", Core.extOf("noext"));
        assertEquals("", Core.extOf("trailing."));
        assertTrue(Core.isImage("IMG_0001.HEIC"));
        assertTrue(Core.isImage("a.jpeg"));
        assertTrue(Core.isVideo("clip.MKV"));
        assertFalse(Core.isVideo("clip.jpg"));
        assertTrue(Core.isAudio("song.mp3"));
        assertEquals("image", Core.kindOf("a.png"));
        assertEquals("video", Core.kindOf("a.mp4"));
        assertEquals("audio", Core.kindOf("a.flac"));
        assertEquals("other", Core.kindOf("report.pdf"));
    }

    @Test
    public void mimeNeverReturnsHtml() {
        assertEquals("image/jpeg", Core.mimeOf("a.jpg"));
        assertEquals("video/mp4", Core.mimeOf("a.mp4"));
        assertEquals("audio/mpeg", Core.mimeOf("a.mp3"));
        assertEquals("application/octet-stream", Core.mimeOf("evil.html"));
        assertEquals("application/octet-stream", Core.mimeOf("noext"));
    }

    @Test
    public void formatSizeIsReadable() {
        assertEquals("0 B", Core.formatSize(0));
        assertEquals("512 B", Core.formatSize(512));
        assertEquals("1.5 KB", Core.formatSize(1536));
        assertEquals("24 MB", Core.formatSize(24L * 1024 * 1024));
        assertEquals("3.2 GB", Core.formatSize((long) (3.2 * 1024 * 1024 * 1024)));
        assertEquals("-", Core.formatSize(-1));
    }

    @Test
    public void sanitizeNameRemovesDangerousCharacters() {
        assertEquals("a_b_c_.jpg", Core.sanitizeName("a/b:c?.jpg"));
        assertEquals("x", Core.sanitizeName("  x  "));
        assertEquals("_", Core.sanitizeName(".."));
        assertEquals("", Core.sanitizeName("   "));
    }

    @Test
    public void uniqueNameAddsCounter() {
        HashSet<String> taken = new HashSet<>(Arrays.asList("a.jpg", "a (1).jpg"));
        assertEquals("b.jpg", Core.uniqueName("b.jpg", taken));
        assertEquals("a (2).jpg", Core.uniqueName("a.jpg", taken));
        assertEquals("noext (1)", Core.uniqueName("noext", new HashSet<>(Arrays.asList("noext"))));
    }

    @Test
    public void serverUrlGetsSchemeAndDsmPort() {
        assertEquals("https://192.168.0.7:5001", Core.normalizeServerUrl("192.168.0.7"));
        assertEquals("https://nas.synology.me:5001", Core.normalizeServerUrl("nas.synology.me"));
        assertEquals("http://nas.synology.me:5000", Core.normalizeServerUrl("nas.synology.me:5000"));
        assertEquals("http://192.168.0.7:5000", Core.normalizeServerUrl("http://192.168.0.7:5000"));
        assertEquals("https://nas.synology.me:5001", Core.normalizeServerUrl("https://nas.synology.me:5001/"));
        // A URL with a path is left as typed (QuickConnect style).
        assertEquals("https://quickconnect.to/choi", Core.normalizeServerUrl("https://quickconnect.to/choi"));
    }

    @Test
    public void dsmSortMapping() {
        assertEquals("name", Core.dsmSortBy("name"));
        assertEquals("mtime", Core.dsmSortBy("date"));
        assertEquals("size", Core.dsmSortBy("size"));
        assertEquals("name", Core.dsmSortBy(null));
        assertEquals("DESC", Core.dsmSortDirection("desc"));
        assertEquals("ASC", Core.dsmSortDirection("asc"));
    }

    @Test
    public void dsmErrorsAreExplained() {
        assertTrue(Core.dsmErrorText(403).contains("OTP"));
        assertTrue(Core.dsmErrorText(400).contains("비밀번호"));
        assertTrue(Core.dsmErrorText(1103).contains("이미"));
        assertTrue(Core.dsmErrorText(9999).contains("9999"));
    }

    @Test
    public void base64UrlRoundTripsKoreanPaths() {
        String path = "/photo/2026년 11월/제주_여행.jpg";
        String encoded = Core.b64UrlEncode(path);
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("="));
        assertEquals(path, Core.b64UrlDecode(encoded));
        assertEquals("", Core.b64UrlDecode(""));
    }

    @Test
    public void splitCsvDropsBlanks() {
        assertEquals(Arrays.asList("a", "b/c"), Core.splitCsv(" a, ,b/c "));
        assertEquals(0, Core.splitCsv(null).size());
    }

    @Test
    public void parseRangeCoversVideoSeeking() {
        assertArrayEquals(new long[]{0, 99}, Core.parseRange("bytes=0-99", 1000));
        assertArrayEquals(new long[]{500, 999}, Core.parseRange("bytes=500-", 1000));
        assertArrayEquals(new long[]{900, 999}, Core.parseRange("bytes=-100", 1000));
        assertArrayEquals(new long[]{0, 999}, Core.parseRange("bytes=0-5000", 1000));
        assertNull(Core.parseRange("bytes=2000-", 1000));
        assertNull(Core.parseRange("items=0-10", 1000));
        assertNull(Core.parseRange(null, 1000));
        assertNull(Core.parseRange("bytes=0-10", 0));
    }

    // ------------------------------------------------------------ WebDAV

    @Test
    public void webDavPortsDecideTheProtocol() {
        assertTrue(Core.isWebDavPort("5005"));
        assertTrue(Core.isWebDavPort("5006"));
        assertFalse(Core.isWebDavPort("5001"));
        assertFalse(Core.isWebDavPort(null));
        assertEquals("webdav", Core.protocolFromHost("nas.synology.me:5005"));
        assertEquals("webdav", Core.protocolFromHost("nas.synology.me:5006/dav"));
        assertEquals("", Core.protocolFromHost("nas.synology.me"));
        assertEquals("webdav", Core.protocolFor("", "http://nas.synology.me:5005"));
        assertEquals("webdav", Core.protocolFor("", "nas.synology.me:5006"));
        assertEquals("webdav", Core.protocolFor("webdav", "https://nas.synology.me:5001"));
        assertEquals("dsm", Core.protocolFor("dsm", "http://nas.synology.me:5005"));
        assertEquals("dsm", Core.protocolFor("", "https://nas.synology.me:5001"));
        assertEquals("webdav", Core.normalizeProtocol(" WebDAV "));
        assertEquals("", Core.normalizeProtocol("ftp"));
    }

    @Test
    public void webDavUrlNormalisesAddresses() {
        assertEquals("http://nas.synology.me:5005", Core.webDavUrl("nas.synology.me"));
        assertEquals("http://nas.synology.me:5005", Core.webDavUrl("nas.synology.me:5005"));
        assertEquals("https://nas.synology.me:5006", Core.webDavUrl("nas.synology.me:5006"));
        assertEquals("https://nas.synology.me:5006", Core.webDavUrl("https://nas.synology.me"));
        assertEquals("http://nas.synology.me:5005", Core.webDavUrl("http://nas.synology.me/"));
        assertEquals("http://nas.synology.me:5005/webdav",
                Core.webDavUrl("nas.synology.me:5005/webdav"));
        // A typed scheme wins over what the port would suggest.
        assertEquals("http://nas.synology.me:5006", Core.webDavUrl("http://nas.synology.me:5006"));
        assertEquals("", Core.webDavUrl(""));
        assertEquals("", Core.webDavUrl("https://"));
    }

    @Test
    public void davPathsEncodeAndDecode() {
        assertEquals("/%EC%82%AC%EC%A7%84/2026%EB%85%84%2011%EC%9B%94.jpg",
                Core.davEncodePath("/사진/2026년 11월.jpg"));
        assertEquals("/사진/2026년 11월.jpg",
                Core.davDecode(Core.davEncodePath("/사진/2026년 11월.jpg")));
        assertEquals("/a-b_c.d~e", Core.davEncodePath("/a-b_c.d~e"));
        assertEquals("/a b+c+d", Core.davDecode("/a%20b%2Bc+d"));
        assertEquals("가", Core.davDecode("%EA%B0%80"));
        assertEquals("/a%25b", Core.davEncodePath("/a%b"));
        assertFalse(Core.davEncodePath("/a b").contains(" "));
        assertEquals("/", Core.davEncodePath(null));
    }

    @Test
    public void davUrlsAndHrefs() {
        assertEquals("http://h:5005/", Core.davUrl("http://h:5005", "/"));
        assertEquals("http://h:5005/%EC%82%AC%EC%A7%84", Core.davUrl("http://h:5005/", "/사진"));
        assertEquals("", Core.davUrl("", "/a"));
        assertEquals("/", Core.davBasePath("http://h:5005"));
        assertEquals("/dav", Core.davBasePath("http://h:5005/dav"));
        assertEquals("/photo/가.jpg",
                Core.davPathOf("http://h:5005/photo/%EA%B0%80.jpg", "http://h:5005"));
        assertEquals("/photo", Core.davPathOf("/photo/", "http://h:5005"));
        assertEquals("/photo/1.jpg",
                Core.davPathOf("http://h:5005/dav/photo/1.jpg", "http://h:5005/dav"));
        assertEquals("/a.jpg", Core.davPathOf("http://h:5005/dav/a.jpg", "http://h:5005/dav/"));
        assertEquals("", Core.davPathOf("http://h:5005/a.jpg", "http://h:5005/dav"));
        assertEquals("", Core.davPathOf("", "http://h:5005"));
    }

    @Test
    public void httpDatesFromWebDav() {
        long ms = Core.parseHttpDate("Tue, 24 Nov 2026 03:10:00 GMT");
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        calendar.setTimeInMillis(ms);
        assertEquals(2026, calendar.get(Calendar.YEAR));
        assertEquals(Calendar.NOVEMBER, calendar.get(Calendar.MONTH));
        assertEquals(24, calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(3, calendar.get(Calendar.HOUR_OF_DAY));
        assertEquals(10, calendar.get(Calendar.MINUTE));
        assertEquals(0L, Core.parseHttpDate(""));
        assertEquals(0L, Core.parseHttpDate(null));
        assertEquals(0L, Core.parseHttpDate("not a date"));
    }

    private static final String MULTISTATUS =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<D:multistatus xmlns:D=\"DAV:\">"
                    + "<D:response><D:href>/photo/</D:href>"
                    + "<D:propstat><D:prop><D:displayname>photo</D:displayname>"
                    + "<D:resourcetype><D:collection/></D:resourcetype>"
                    + "<D:getlastmodified>Tue, 24 Nov 2026 03:10:00 GMT</D:getlastmodified>"
                    + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
                    + "</D:response>"
                    + "<D:response><D:href>/photo/%EC%A0%9C%EC%A3%BC%20%EC%97%AC%ED%96%89.jpg</D:href>"
                    + "<D:propstat><D:prop><D:displayname>제주 여행.jpg</D:displayname>"
                    + "<D:resourcetype/><D:getcontentlength>4312</D:getcontentlength>"
                    + "<D:getlastmodified>Tue, 24 Nov 2026 04:20:00 GMT</D:getlastmodified>"
                    + "<D:getcontenttype>image/jpeg</D:getcontenttype>"
                    + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
                    + "</D:response>"
                    + "<D:response><D:href>/photo/clip.mp4</D:href>"
                    + "<D:propstat><D:prop><D:resourcetype/>"
                    + "<D:getcontentlength>900</D:getcontentlength></D:prop>"
                    + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
                    + "<D:propstat><D:prop><D:displayname/></D:prop>"
                    + "<D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>"
                    + "</D:response>"
                    + "</D:multistatus>";

    @Test
    public void davXmlReadsAFolderListing() {
        List<DavXml.Entry> entries = DavXml.parse(MULTISTATUS, "http://nas:5005");
        assertEquals(3, entries.size());

        DavXml.Entry folder = entries.get(0);
        assertEquals("/photo", folder.path);
        assertEquals("photo", folder.name);
        assertTrue(folder.dir);

        DavXml.Entry photo = entries.get(1);
        assertEquals("/photo/제주 여행.jpg", photo.path);
        assertEquals("제주 여행.jpg", photo.name);
        assertFalse(photo.dir);
        assertEquals(4312L, photo.size);
        assertEquals("image/jpeg", photo.contentType);
        assertTrue(photo.mtime > 0);

        DavXml.Entry clip = entries.get(2);
        assertEquals("/photo/clip.mp4", clip.path);
        assertEquals("clip.mp4", clip.name);
        assertEquals(900L, clip.size);
    }

    @Test
    public void davXmlDropsOtherServersAndExplainsTheRoot() {
        String xml = "<d:multistatus xmlns:d=\"DAV:\">"
                + "<d:response><d:href>http://nas:5005/dav/</d:href>"
                + "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                + "<d:response><d:href>http://nas:5005/elsewhere/a.txt</d:href>"
                + "<d:propstat><d:prop><d:resourcetype/></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                + "</d:multistatus>";
        List<DavXml.Entry> entries = DavXml.parse(xml, "http://nas:5005/dav");
        assertEquals(1, entries.size());
        assertEquals("/", entries.get(0).path);
        assertTrue(entries.get(0).dir);
        assertEquals(0, DavXml.parse("", "http://nas:5005").size());
        assertEquals(0, DavXml.parse("<broken", "http://nas:5005").size());
        assertEquals("/", DavXml.first(xml, "http://nas:5005/dav").path);
    }

    @Test
    public void davXmlRefusesExternalEntities() {
        String evil = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE lolz [<!ENTITY lol \"lollollol\">]>"
                + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&lol;</d:href>"
                + "</d:response></d:multistatus>";
        assertEquals(0, DavXml.parse(evil, "http://nas:5005").size());
    }

    @Test
    public void httpWireParsesTheStatusLine() {
        HttpWire.Response response = new HttpWire.Response();
        HttpWire.parseStatus("HTTP/1.1 207 Multi-Status", response);
        assertEquals(207, response.code);
        assertEquals("Multi-Status", response.message);
        HttpWire.Response plain = new HttpWire.Response();
        HttpWire.parseStatus("HTTP/1.1 404", plain);
        assertEquals(404, plain.code);
        assertEquals("", plain.message);
    }

    @Test
    public void httpWireDecodesChunkedBodies() throws Exception {
        byte[] raw = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream in = new HttpWire.ChunkedInputStream(new ByteArrayInputStream(raw));
        StringBuilder text = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            text.append((char) c);
        }
        assertEquals("Wikipedia", text.toString());
    }

    @Test
    public void httpWireStopsAtTheContentLength() throws Exception {
        byte[] raw = "0123456789".getBytes(StandardCharsets.UTF_8);
        InputStream in = new HttpWire.LimitedInputStream(new ByteArrayInputStream(raw), 4);
        byte[] buffer = new byte[10];
        int read = in.read(buffer, 0, buffer.length);
        assertEquals(4, read);
        assertEquals(-1, in.read());
    }

    @Test
    public void httpWireReadsALine() throws Exception {
        InputStream in = new ByteArrayInputStream("Depth: 1\r\nrest".getBytes(StandardCharsets.UTF_8));
        assertEquals("Depth: 1", HttpWire.readLine(in));
        assertNull(HttpWire.readLine(new ByteArrayInputStream(new byte[0])));
    }

    /**
     * End to end through a real loopback socket: the request line really says PROPFIND (the
     * reason this client exists — HttpURLConnection refuses those verbs) and a 207 with a
     * declared length is read back as text.
     */
    @Test
    public void httpWireSpeaksDavOverALoopbackSocket() throws Exception {
        ServerSocket server;
        try {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            return; // no loopback sockets in this sandbox: nothing to prove here
        }
        final String[] seen = new String[1];
        final String body = "<d:multistatus xmlns:d=\"DAV:\"/>"
                + "<d:multistatus xmlns:d=\"DAV:\"/>";
        Thread worker = new Thread(() -> {
            try {
                server.setSoTimeout(8000);
                Socket client = server.accept();
                try {
                    InputStream in = client.getInputStream();
                    StringBuilder head = new StringBuilder();
                    int c;
                    while ((c = in.read()) >= 0) {
                        head.append((char) c);
                        if (head.length() >= 4
                                && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                            break;
                        }
                    }
                    seen[0] = head.toString();
                    int length = 0;
                    for (String line : seen[0].split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    for (int i = 0; i < length; i++) {
                        in.read();
                    }
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 207 Multi-Status\r\n"
                            + "Content-Type: application/xml; charset=utf-8\r\n"
                            + "Content-Length: " + body.getBytes("UTF-8").length + "\r\n\r\n"
                            + body).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } finally {
                    client.close();
                }
            } catch (Exception ignored) {
                // the assertions below report what came back
            }
        });
        worker.setDaemon(true);
        worker.start();

        Map<String, String> headers = new HashMap<>();
        headers.put("Depth", "0");
        headers.put("Authorization", "Basic dGVzdDp0ZXN0");
        HttpWire.Options options = new HttpWire.Options();
        options.connectTimeout = 4000;
        options.readTimeout = 4000;
        HttpWire.Response response = HttpWire.execute("PROPFIND",
                "http://127.0.0.1:" + server.getLocalPort() + "/",
                headers, HttpWire.text("<d:propfind/>"), options);
        try {
            assertEquals(207, response.code);
            assertEquals(body, response.readText(4096));
            String head = seen[0] == null ? "" : seen[0];
            assertTrue(head, head.startsWith("PROPFIND / HTTP/1.1"));
            assertTrue(head, head.contains("Depth: 0"));
            assertTrue(head, head.contains("Authorization: Basic dGVzdDp0ZXN0"));
            assertTrue(head, head.contains("Connection: close"));
        } finally {
            response.close();
            server.close();
        }
    }
}
