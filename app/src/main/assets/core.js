/*
 * core.js — pure logic of 「NAS 파일매니저」: path handling, file typing, sorting,
 * filtering, size/date formatting and the loopback stream URLs. No DOM, no bridge.
 *
 * It is loaded first in the app and is also what tools/tests/core.test.js runs under
 * Node, so every rule here is checked without a phone.
 */
(function (root) {
    'use strict';

    var IMAGE_EXT = ['jpg', 'jpeg', 'jfif', 'png', 'gif', 'webp', 'bmp', 'heic', 'heif',
        'avif', 'tif', 'tiff', 'dng'];
    var VIDEO_EXT = ['mp4', 'm4v', 'mkv', 'mov', 'webm', 'avi', '3gp', '3gpp', 'ts', 'mts',
        'm2ts', 'mpg', 'mpeg', 'wmv', 'flv', 'asf', 'rm', 'rmvb', 'ogv'];
    var AUDIO_EXT = ['mp3', 'm4a', 'aac', 'flac', 'wav', 'ogg', 'opus', 'amr', 'wma'];

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
        var dot = String(name).lastIndexOf('.');
        if (dot < 0 || dot === String(name).length - 1) {
            return '';
        }
        return String(name).substring(dot + 1).toLowerCase();
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

    function sanitizeName(raw) {
        var name = raw === null || raw === undefined ? '' : String(raw).trim();
        if (!name) {
            return '';
        }
        name = name.replace(/[\\/:*?"<>|\r\n\t]/g, '_').replace(/\s+/g, ' ').trim();
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

    /** UTF-8 safe base64url for the loopback server's ?p= parameter. */
    function b64url(text) {
        var value = text === null || text === undefined ? '' : String(text);
        var bytes;
        if (typeof TextEncoder !== 'undefined') {
            bytes = new TextEncoder().encode(value);
        } else {
            bytes = [];
            for (var i = 0; i < value.length; i++) {
                bytes.push(value.charCodeAt(i) & 0xff);
            }
        }
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
            return binary;
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
            parts.push('동영상 ' + counts.video);
        }
        if (counts.file) {
            parts.push('파일 ' + counts.file);
        }
        return parts.join(' · ');
    }

    var API = {
        normalizePath: normalizePath,
        joinPath: joinPath,
        parentOf: parentOf,
        nameOf: nameOf,
        extOf: extOf,
        isImage: isImage,
        isVideo: isVideo,
        isAudio: isAudio,
        kindOf: kindOf,
        formatSize: formatSize,
        formatDate: formatDate,
        sortEntries: sortEntries,
        filterEntries: filterEntries,
        countByKind: countByKind,
        sanitizeName: sanitizeName,
        uniqueName: uniqueName,
        b64url: b64url,
        b64urlDecode: b64urlDecode,
        streamUrl: streamUrl,
        breadcrumbs: breadcrumbs,
        displayPath: displayPath,
        summary: summary
    };

    root.FMCore = API;
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = API;
    }
})(typeof window !== 'undefined' ? window : globalThis);
