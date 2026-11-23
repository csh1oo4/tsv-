/*
 * core.js — pure logic of 「SynoFile Pro」: path handling, file typing, sorting, filtering,
 * size/date formatting, the loopback stream URLs, the server-address fields and the transfer
 * bookkeeping. No DOM, no bridge: everything here is testable without a phone.
 *
 * Loaded first in the app, and also what tools/tests/suite.js checks under Node or V8.
 */
(function (root) {
    'use strict';

    var IMAGE_EXT = ['jpg', 'jpeg', 'jfif', 'png', 'gif', 'webp', 'bmp', 'heic', 'heif',
        'avif', 'tif', 'tiff', 'dng'];
    var VIDEO_EXT = ['mp4', 'm4v', 'mkv', 'mov', 'webm', 'avi', '3gp', '3gpp', 'ts', 'mts',
        'm2ts', 'mpg', 'mpeg', 'wmv', 'flv', 'asf', 'rm', 'rmvb', 'ogv'];
    var AUDIO_EXT = ['mp3', 'm4a', 'aac', 'flac', 'wav', 'ogg', 'opus', 'amr', 'wma'];

    var SORT_KEYS = ['date', 'name', 'size'];
    var SORT_LABELS = {date: '날짜순', name: '이름순', size: '크기순'};
    var FILTER_KEYS = ['all', 'image', 'video', 'folder'];
    var FILTER_LABELS = {all: '전체', image: '사진', video: '영상', folder: '폴더'};

    function normalizePath(path) {
        if (path === null || path === undefined) {
            return '/';
        }
        var p = String(path).trim().replace(/\\/g, '/');
        if (!p) {
            return '/';
        }
        p = p.replace(/\/{2,}/g, '/');
        if (p.length > 1 && p.charAt(p.length - 1) === '/') {
            p = p.substring(0, p.length - 1);
        }
        if (p.charAt(0) !== '/') {
            p = '/' + p;
        }
        return p || '/';
    }

    function joinPath(dir, name) {
        var d = normalizePath(dir);
        var n = name === null || name === undefined ? '' : String(name).trim().replace(/\\/g, '/');
        if (n.charAt(0) === '/') {
            return normalizePath(n);
        }
        if (!n) {
            return d;
        }
        return d === '/' ? '/' + n : d + '/' + n;
    }

    function parentOf(path) {
        var p = normalizePath(path);
        var slash = p.lastIndexOf('/');
        if (slash <= 0) {
            return '/';
        }
        return p.substring(0, slash);
    }

    function nameOf(path) {
        var p = normalizePath(path);
        if (p === '/') {
            return '';
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }

    function extOf(name) {
        if (!name) {
            return '';
        }
        var text = String(name);
        var dot = text.lastIndexOf('.');
        if (dot < 0 || dot === text.length - 1) {
            return '';
        }
        return text.substring(dot + 1).toLowerCase();
    }

    function hasExt(list, ext) {
        return !!ext && list.indexOf(ext) >= 0;
    }

    function isImage(name) {
        return hasExt(IMAGE_EXT, extOf(name));
    }

    function isVideo(name) {
        return hasExt(VIDEO_EXT, extOf(name));
    }

    function isAudio(name) {
        return hasExt(AUDIO_EXT, extOf(name));
    }

    function kindOf(name) {
        if (isImage(name)) {
            return 'image';
        }
        if (isVideo(name)) {
            return 'video';
        }
        if (isAudio(name)) {
            return 'audio';
        }
        return 'file';
    }

    /** '사진' / '영상' / '파일' — what the tile badges and the info sheet show. */
    function kindLabel(name) {
        var kind = kindOf(name);
        return kind === 'image' ? '사진' : (kind === 'video' ? '영상'
            : (kind === 'audio' ? '음악' : '파일'));
    }

    /** The short badge printed over a thumbnail: JPG, MP4, PDF … ('' for folders). */
    function badgeOf(name) {
        var ext = extOf(name);
        if (ext) {
            return ext.length > 4 ? ext.substring(0, 4).toUpperCase() : ext.toUpperCase();
        }
        return 'FILE';
    }

    function formatSize(bytes) {
        var n = Number(bytes);
        if (!isFinite(n) || n < 0) {
            return '-';
        }
        if (n < 1024) {
            return n + ' B';
        }
        var units = ['KB', 'MB', 'GB', 'TB', 'PB'];
        var value = n;
        var unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value = value / 1024;
            unit++;
        }
        var text = value >= 10 || Math.abs(value - Math.round(value)) < 0.05
            ? String(Math.round(value))
            : value.toFixed(1);
        return text + ' ' + units[unit];
    }

    function pad(n) {
        return n < 10 ? '0' + n : String(n);
    }

    /** 2026-11-24 02:27 style; today shows only the time. */
    function formatDate(ms, now) {
        var t = Number(ms);
        if (!isFinite(t) || t <= 0) {
            return '';
        }
        var d = new Date(t);
        var today = now ? new Date(now) : new Date();
        var sameDay = d.getFullYear() === today.getFullYear()
            && d.getMonth() === today.getMonth()
            && d.getDate() === today.getDate();
        if (sameDay) {
            return pad(d.getHours()) + ':' + pad(d.getMinutes());
        }
        if (d.getFullYear() === today.getFullYear()) {
            return (d.getMonth() + 1) + '월 ' + d.getDate() + '일';
        }
        return d.getFullYear() + '.' + (d.getMonth() + 1) + '.' + d.getDate();
    }

    /** "2026-11-24" for the folder subtitle. */
    function formatDay(ms) {
        var t = Number(ms);
        if (!isFinite(t) || t <= 0) {
            return '';
        }
        var d = new Date(t);
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
    }

    function compareText(a, b) {
        return String(a || '').localeCompare(String(b || ''), 'ko', {numeric: true, sensitivity: 'base'});
    }

    /**
     * Folders first, then the requested key. 'date' and 'size' read newest/largest first;
     * 'name' reads A→Z (한글 가나다순).
     */
    function sortEntries(entries, key, direction) {
        var list = (entries || []).slice();
        var byDate = key === 'date';
        var bySize = key === 'size';
        list.sort(function (a, b) {
            var aDir = !!a.isDir;
            var bDir = !!b.isDir;
            if (aDir !== bDir) {
                return aDir ? -1 : 1;
            }
            var result;
            if (byDate) {
                result = (Number(b.mtime) || 0) - (Number(a.mtime) || 0);
            } else if (bySize) {
                result = (Number(b.size) || 0) - (Number(a.size) || 0);
            } else {
                result = compareText(a.name, b.name);
            }
            if (result === 0) {
                result = compareText(a.name, b.name);
            }
            if (direction === 'desc' && !byDate && !bySize) {
                result = -result;
            }
            return result;
        });
        return list;
    }

    /** filter: all | image | video | folder — folders stay visible only in all/folder. */
    function filterEntries(entries, filter, query) {
        var q = query ? String(query).trim().toLowerCase() : '';
        var want = filter || 'all';
        var out = [];
        (entries || []).forEach(function (entry) {
            if (!entry) {
                return;
            }
            if (want === 'image' && (entry.isDir || !isImage(entry.name))) {
                return;
            }
            if (want === 'video' && (entry.isDir || !isVideo(entry.name))) {
                return;
            }
            if (want === 'folder' && !entry.isDir) {
                return;
            }
            if (q && String(entry.name).toLowerCase().indexOf(q) < 0) {
                return;
            }
            out.push(entry);
        });
        return out;
    }

    /** The list as the screen draws it: filtered, then sorted. */
    function visibleEntries(entries, filter, query, sortKey) {
        return sortEntries(filterEntries(entries, filter, query), sortKey || 'date', 'asc');
    }

    function countByKind(entries) {
        var counts = {image: 0, video: 0, folder: 0, file: 0};
        (entries || []).forEach(function (entry) {
            if (!entry) {
                return;
            }
            if (entry.isDir) {
                counts.folder++;
            } else if (isImage(entry.name)) {
                counts.image++;
            } else if (isVideo(entry.name)) {
                counts.video++;
            } else {
                counts.file++;
            }
        });
        return counts;
    }

    /** Files only — what a transfer can actually move. */
    function fileCount(entries) {
        var n = 0;
        (entries || []).forEach(function (entry) {
            if (entry && !entry.isDir) {
                n++;
            }
        });
        return n;
    }

    function sanitizeName(raw) {
        var name = raw === null || raw === undefined ? '' : String(raw).trim();
        if (!name) {
            return '';
        }
        name = name.replace(/[\\/:*?"<>\r\n\t]/g, '_').replace(/\s+/g, ' ').trim();
        if (name.length > 120) {
            var ext = extOf(name);
            var dot = name.lastIndexOf('.');
            name = dot > 0
                ? name.substring(0, Math.min(dot, 100)) + '.' + ext
                : name.substring(0, 100);
        }
        if (!name || name === '.' || name === '..') {
            return '_';
        }
        return name;
    }

    function uniqueName(name, existing) {
        var clean = sanitizeName(name);
        var taken = existing || [];
        if (taken.indexOf(clean) < 0) {
            return clean;
        }
        var ext = extOf(clean);
        var stem = ext ? clean.substring(0, clean.length - ext.length - 1) : clean;
        for (var i = 1; i < 10000; i++) {
            var candidate = stem + ' (' + i + ')' + (ext ? '.' + ext : '');
            if (taken.indexOf(candidate) < 0) {
                return candidate;
            }
        }
        return stem + '-' + Date.now() + (ext ? '.' + ext : '');
    }

    /** UTF-8 bytes without TextEncoder (old WebViews, V8 in a test harness). */
    function utf8Encode(value) {
        var bytes = [];
        for (var i = 0; i < value.length; i++) {
            var code = value.charCodeAt(i);
            if (code < 0x80) {
                bytes.push(code);
            } else if (code < 0x800) {
                bytes.push(0xC0 | (code >> 6), 0x80 | (code & 0x3F));
            } else if (code >= 0xD800 && code <= 0xDBFF && i + 1 < value.length
                && value.charCodeAt(i + 1) >= 0xDC00 && value.charCodeAt(i + 1) <= 0xDFFF) {
                var next = value.charCodeAt(i + 1);
                var cp = 0x10000 + ((code - 0xD800) << 10) + (next - 0xDC00);
                bytes.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F),
                    0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
                i++;
            } else {
                bytes.push(0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F));
            }
        }
        return bytes;
    }

    /** The other direction, for the same reason. */
    function utf8Decode(bytes) {
        var out = '';
        for (var i = 0; i < bytes.length; i++) {
            var b = bytes[i] & 0xFF;
            if (b < 0x80) {
                out += String.fromCharCode(b);
            } else if (b >= 0xC0 && b < 0xE0 && i + 1 < bytes.length) {
                out += String.fromCharCode(((b & 0x1F) << 6) | (bytes[++i] & 0x3F));
            } else if (b >= 0xE0 && b < 0xF0 && i + 2 < bytes.length) {
                out += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[++i] & 0x3F) << 6)
                    | (bytes[++i] & 0x3F));
            } else if (b >= 0xF0 && i + 3 < bytes.length) {
                var cp = ((b & 0x07) << 18) | ((bytes[++i] & 0x3F) << 12)
                    | ((bytes[++i] & 0x3F) << 6) | (bytes[++i] & 0x3F);
                cp -= 0x10000;
                out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
            }
        }
        return out;
    }

    /** UTF-8 safe base64url for the loopback server's ?p= parameter. */
    function b64url(text) {
        var value = text === null || text === undefined ? '' : String(text);
        var bytes = typeof TextEncoder !== 'undefined'
            ? new TextEncoder().encode(value) : utf8Encode(value);
        var binary = '';
        for (var j = 0; j < bytes.length; j++) {
            binary += String.fromCharCode(bytes[j]);
        }
        var encoded = typeof btoa === 'function'
            ? btoa(binary)
            : (typeof Buffer !== 'undefined'
                ? Buffer.from(binary, 'binary').toString('base64') : '');
        return encoded.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    function b64urlDecode(encoded) {
        var value = String(encoded || '').replace(/-/g, '+').replace(/_/g, '/');
        while (value.length % 4) {
            value += '=';
        }
        try {
            var binary = typeof atob === 'function'
                ? atob(value)
                : (typeof Buffer !== 'undefined'
                    ? Buffer.from(value, 'base64').toString('binary') : '');
            if (typeof TextDecoder !== 'undefined') {
                var bytes = new Uint8Array(binary.length);
                for (var i = 0; i < binary.length; i++) {
                    bytes[i] = binary.charCodeAt(i);
                }
                return new TextDecoder().decode(bytes);
            }
            var plain = [];
            for (var j = 0; j < binary.length; j++) {
                plain.push(binary.charCodeAt(j));
            }
            return utf8Decode(plain);
        } catch (e) {
            return '';
        }
    }

    /** Builds the loopback URL the page puts in <img>/<video> src. */
    function streamUrl(base, token, route, path, size) {
        if (!base) {
            return '';
        }
        var url = base + '/' + route + '?t=' + encodeURIComponent(token || '')
            + '&p=' + b64url(path);
        if (size) {
            url += '&s=' + encodeURIComponent(size);
        }
        return url;
    }

    /** [{label, path}] for the breadcrumb bar; the first crumb is the root. */
    function breadcrumbs(path, rootLabel, rootPrefix) {
        var full = normalizePath(path);
        var relative = full;
        if (rootPrefix && full.indexOf(normalizePath(rootPrefix)) === 0) {
            relative = full.substring(normalizePath(rootPrefix).length) || '/';
            relative = normalizePath(relative);
        }
        var parts = relative === '/' ? [] : relative.split('/').filter(Boolean);
        var crumbs = [{label: rootLabel || '루트', path: normalizePath(rootPrefix || '/')}];
        var walked = normalizePath(rootPrefix || '/');
        parts.forEach(function (part) {
            walked = joinPath(walked, part);
            crumbs.push({label: part, path: walked});
        });
        return crumbs;
    }

    /** "/storage/emulated/0/DCIM/Camera" -> "/DCIM/Camera" for the title bar. */
    function displayPath(path, rootPrefix) {
        var full = normalizePath(path);
        if (!rootPrefix) {
            return full;
        }
        var root = normalizePath(rootPrefix);
        if (full === root) {
            return '/';
        }
        if (full.indexOf(root + '/') === 0) {
            return full.substring(root.length);
        }
        return full;
    }

    /** One line "사진 12 · 동영상 3 · 폴더 4" for the folder header. */
    function summary(entries) {
        var counts = countByKind(entries);
        var parts = [];
        if (counts.folder) {
            parts.push('폴더 ' + counts.folder);
        }
        if (counts.image) {
            parts.push('사진 ' + counts.image);
        }
        if (counts.video) {
            parts.push('영상 ' + counts.video);
        }
        if (counts.file) {
            parts.push('파일 ' + counts.file);
        }
        return parts.join(' · ');
    }

    // ------------------------------------------------------------ sort / filter

    function sortLabel(key) {
        return SORT_LABELS[key] || SORT_LABELS.date;
    }

    function nextSort(key) {
        var i = SORT_KEYS.indexOf(key);
        return SORT_KEYS[(i + 1) % SORT_KEYS.length];
    }

    function filterLabel(key) {
        return FILTER_LABELS[key] || FILTER_LABELS.all;
    }

    function nextFilter(key) {
        var i = FILTER_KEYS.indexOf(key);
        return FILTER_KEYS[(i + 1) % FILTER_KEYS.length];
    }

    // ------------------------------------------------------------ server fields

    /**
     * The 주소 / 포트 boxes become one URL.
     *
     *   DSM     "192.168.0.7" + "5001"  -> https://192.168.0.7:5001   (5000 stays http)
     *   WebDAV  "nas.synology.me" + "5005" -> http://nas.synology.me:5005
     *           "nas.synology.me" + "5006" -> https://nas.synology.me:5006
     *           "nas.synology.me" + ""     -> http://nas.synology.me:5005
     *
     * A scheme typed by the user wins over the port default, and a path (QuickConnect, a
     * WebDAV prefix) is kept exactly as typed.
     */
    function serverUrl(host, port, protocol) {
        var h = host === null || host === undefined ? '' : String(host).trim();
        if (!h) {
            return '';
        }
        var scheme = '';
        var typed = /^(https?):\/\//i.exec(h);
        if (typed) {
            scheme = typed[1].toLowerCase();
            h = h.substring(typed[0].length);
        }
        var path = '';
        var slash = h.indexOf('/');
        if (slash >= 0) {
            path = h.substring(slash).replace(/\/+$/, '');
            h = h.substring(0, slash);
        }
        var embedded = '';
        var colon = h.lastIndexOf(':');
        if (colon > 0 && /^[0-9]+$/.test(h.substring(colon + 1))) {
            embedded = h.substring(colon + 1);
            h = h.substring(0, colon);
        }
        if (!h) {
            return '';
        }
        var boxPort = port === null || port === undefined ? '' : String(port).trim().replace(/[^0-9]/g, '');
        var p = boxPort || embedded;
        var proto = normalizeProtocol(protocol) || suggestProtocol('', p) || 'dsm';

        if (proto === 'webdav') {
            var secure = scheme ? scheme === 'https' : p === DAV_HTTPS_PORT;
            if (!scheme) {
                scheme = secure ? 'https' : 'http';
            }
            if (!p) {
                p = secure ? DAV_HTTPS_PORT : DAV_HTTP_PORT;
            }
            return scheme + '://' + h + ':' + p + path;
        }

        if (!scheme) {
            scheme = p === '5000' ? 'http' : 'https';
        }
        return scheme + '://' + h + (p ? ':' + p : '') + path;
    }

    /** The other direction, so the settings form can open filled in from the saved server. */
    function splitServerUrl(url) {
        var raw = url === null || url === undefined ? '' : String(url).trim();
        var out = {host: raw, port: '5001', secure: true, protocol: 'dsm', path: '', scheme: 'https'};
        if (!raw) {
            out.host = '';
            return out;
        }
        var match = /^(https?):\/\/([^/:]+)(?::(\d+))?(\/[^?#]*)?/i.exec(raw);
        if (!match) {
            var bare = /^([^/:]+)(?::(\d+))?(\/[^?#]*)?$/.exec(raw);
            if (bare) {
                out.host = bare[1];
                out.port = bare[2] || '5001';
                out.secure = bare[2] ? bare[2] !== '5000' : true;
                out.scheme = out.secure ? 'https' : 'http';
                out.path = bare[3] ? bare[3].replace(/\/+$/, '') : '';
                out.protocol = isWebDavPort(out.port) ? 'webdav' : 'dsm';
            }
            return out;
        }
        out.host = match[2];
        out.secure = match[1].toLowerCase() === 'https';
        out.scheme = out.secure ? 'https' : 'http';
        out.port = match[3] || (out.secure ? '5001' : '5000');
        out.path = match[4] ? match[4].replace(/\/+$/, '') : '';
        out.protocol = isWebDavPort(out.port) ? 'webdav' : 'dsm';
        return out;
    }

    // ---------------------------------------------------------------- protocol

    var PROTOCOLS = ['dsm', 'webdav'];
    var PROTOCOL_LABELS = {dsm: 'DSM (File Station)', webdav: 'WebDAV'};
    var DAV_HTTP_PORT = '5005';
    var DAV_HTTPS_PORT = '5006';

    /** '' / 'dsm' / 'webdav' — anything else is empty ("no opinion"). */
    function normalizeProtocol(value) {
        var p = value === null || value === undefined ? '' : String(value).trim().toLowerCase();
        return p === 'webdav' ? 'webdav' : (p === 'dsm' ? 'dsm' : '');
    }

    /** 5005 (WebDAV over HTTP) and 5006 (WebDAV over HTTPS) are Synology's WebDAV ports. */
    function isWebDavPort(port) {
        var p = port === null || port === undefined ? '' : String(port).trim();
        return p === DAV_HTTP_PORT || p === DAV_HTTPS_PORT;
    }

    /** ":5005" typed inside the address box already says WebDAV. */
    function protocolFromHost(host) {
        var h = host === null || host === undefined ? '' : String(host).trim();
        return /:(5005|5006)(?:$|[\/?#])/.test(h) ? 'webdav' : '';
    }

    /** The protocol the address itself hints at; '' when it says nothing. */
    function suggestProtocol(host, port) {
        if (protocolFromHost(host) === 'webdav' || isWebDavPort(port)) {
            return 'webdav';
        }
        return '';
    }

    /** What the form should select: the address hint first, then the current choice. */
    function protocolFor(host, port, current) {
        var suggested = suggestProtocol(host, port);
        if (suggested) {
            return suggested;
        }
        return normalizeProtocol(current) || 'dsm';
    }

    function protocolLabel(protocol) {
        return PROTOCOL_LABELS[normalizeProtocol(protocol)] || PROTOCOL_LABELS.dsm;
    }

    function nextProtocol(protocol) {
        return normalizeProtocol(protocol) === 'webdav' ? 'dsm' : 'webdav';
    }

    // ------------------------------------------------------------- WebDAV

    var DAV_KEEP = /[A-Za-z0-9\-._~\/!$&'()*+,;=:@]/;

    function percentOf(ch) {
        var bytes = utf8Encode(ch);
        var out = '';
        for (var i = 0; i < bytes.length; i++) {
            var hex = bytes[i].toString(16).toUpperCase();
            out += '%' + (hex.length < 2 ? '0' + hex : hex);
        }
        return out;
    }

    /** "/사진/2026년 11월.jpg" -> "/%EC%82%AC%EC%A7%84/2026%EB%85%84%2011%EC%9B%94.jpg" */
    function davEncodePath(path) {
        var p = normalizePath(path);
        var out = '';
        for (var i = 0; i < p.length; i++) {
            var ch = p.charAt(i);
            if (DAV_KEEP.test(ch)) {
                out += ch;
                continue;
            }
            if (ch >= '\uD800' && ch <= '\uDBFF' && i + 1 < p.length) {
                out += percentOf(ch + p.charAt(++i));
                continue;
            }
            out += percentOf(ch);
        }
        return out;
    }

    /** The inverse; '+' stays a plus because that is what a path means. */
    function davDecode(text) {
        var value = text === null || text === undefined ? '' : String(text);
        var bytes = [];
        for (var i = 0; i < value.length; i++) {
            var ch = value.charAt(i);
            if (ch === '%' && i + 2 < value.length) {
                var hex = value.substring(i + 1, i + 3);
                if (/^[0-9A-Fa-f]{2}$/.test(hex)) {
                    bytes.push(parseInt(hex, 16));
                    i += 2;
                    continue;
                }
            }
            var literal = ch;
            if (ch >= '\uD800' && ch <= '\uDBFF' && i + 1 < value.length) {
                literal = ch + value.charAt(++i);
            }
            var encoded = utf8Encode(literal);
            for (var j = 0; j < encoded.length; j++) {
                bytes.push(encoded[j]);
            }
        }
        return utf8Decode(bytes);
    }

    /** The absolute URL of a page path under the WebDAV base (no trailing slash on base). */
    function davUrl(base, path) {
        var b = base === null || base === undefined ? '' : String(base).trim().replace(/\/+$/, '');
        if (!b) {
            return '';
        }
        var p = normalizePath(path);
        return p === '/' ? b + '/' : b + davEncodePath(p);
    }

    /** The path part of the WebDAV base ("http://h:5005/dav" -> "/dav"). */
    function davBasePath(base) {
        var raw = base === null || base === undefined ? '' : String(base).trim();
        var match = /^(?:https?:)?\/\/[^\/]*(\/[^?#]*)?/i.exec(raw);
        if (!match) {
            return normalizePath(raw);
        }
        return normalizePath(match[1] ? davDecode(match[1]) : '/');
    }

    /**
     * A PROPFIND href becomes the page path: absolute or absolute-path hrefs are decoded and
     * stripped of the WebDAV prefix. '' means "not inside this server" (and is skipped).
     */
    function davPathOf(href, base) {
        var raw = href === null || href === undefined ? '' : String(href).trim();
        if (!raw) {
            return '';
        }
        var match = /^(?:https?:)?\/\/[^\/]*(\/[^?#]*)?/i.exec(raw);
        var pathOnly = match ? (match[1] || '/') : raw;
        var cut = pathOnly.indexOf('?');
        if (cut >= 0) {
            pathOnly = pathOnly.substring(0, cut);
        }
        cut = pathOnly.indexOf('#');
        if (cut >= 0) {
            pathOnly = pathOnly.substring(0, cut);
        }
        var decoded = davDecode(pathOnly);
        var root = davBasePath(base);
        if (root !== '/') {
            if (decoded === root) {
                return '/';
            }
            if (decoded.indexOf(root + '/') === 0) {
                decoded = decoded.substring(root.length);
            } else {
                return '';
            }
        }
        return normalizePath(decoded);
    }

    // --------------------------------------------------------------- transfers

    function clampPercent(value) {
        var n = Number(value);
        if (!isFinite(n) || n < 0) {
            return 0;
        }
        return Math.min(100, Math.round(n));
    }

    /** {running, done, failed} for the header badge and the drawer's summary line. */
    function transferSummary(transfers) {
        var out = {running: 0, done: 0, failed: 0};
        (transfers || []).forEach(function (t) {
            if (!t) {
                return;
            }
            if (t.status === 'running') {
                out.running++;
            } else if (t.status === 'failed') {
                out.failed++;
            } else {
                out.done++;
            }
        });
        return out;
    }

    /** "동영상 12 · 사진 3" style line above the list. */
    function transferLine(transfers) {
        var s = transferSummary(transfers);
        var parts = [];
        if (s.running) {
            parts.push('전송 중 ' + s.running);
        }
        if (s.done) {
            parts.push('완료 ' + s.done);
        }
        if (s.failed) {
            parts.push('실패 ' + s.failed);
        }
        return parts.join(' · ') || '전송 내역 없음';
    }

    var API = {
        IMAGE_EXT: IMAGE_EXT,
        VIDEO_EXT: VIDEO_EXT,
        SORT_KEYS: SORT_KEYS,
        FILTER_KEYS: FILTER_KEYS,
        normalizePath: normalizePath,
        joinPath: joinPath,
        parentOf: parentOf,
        nameOf: nameOf,
        extOf: extOf,
        isImage: isImage,
        isVideo: isVideo,
        isAudio: isAudio,
        kindOf: kindOf,
        kindLabel: kindLabel,
        badgeOf: badgeOf,
        formatSize: formatSize,
        formatDate: formatDate,
        formatDay: formatDay,
        sortEntries: sortEntries,
        filterEntries: filterEntries,
        visibleEntries: visibleEntries,
        countByKind: countByKind,
        fileCount: fileCount,
        sanitizeName: sanitizeName,
        uniqueName: uniqueName,
        b64url: b64url,
        b64urlDecode: b64urlDecode,
        streamUrl: streamUrl,
        breadcrumbs: breadcrumbs,
        displayPath: displayPath,
        summary: summary,
        sortLabel: sortLabel,
        nextSort: nextSort,
        filterLabel: filterLabel,
        nextFilter: nextFilter,
        serverUrl: serverUrl,
        splitServerUrl: splitServerUrl,
        PROTOCOLS: PROTOCOLS,
        normalizeProtocol: normalizeProtocol,
        isWebDavPort: isWebDavPort,
        protocolFromHost: protocolFromHost,
        suggestProtocol: suggestProtocol,
        protocolFor: protocolFor,
        protocolLabel: protocolLabel,
        nextProtocol: nextProtocol,
        davEncodePath: davEncodePath,
        davDecode: davDecode,
        davUrl: davUrl,
        davBasePath: davBasePath,
        davPathOf: davPathOf,
        clampPercent: clampPercent,
        transferSummary: transferSummary,
        transferLine: transferLine
    };

    root.FMCore = API;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = API;
    }
})(typeof window !== 'undefined' ? window : globalThis);
