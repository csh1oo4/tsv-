package com.choi.filemanager;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

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
}
