/*
 * tools/tests/suite.js — every check for SynoFile Pro, engine-agnostic.
 *
 * The same file runs twice:
 *   node tools/tests/run.js        (GitHub Actions: assembleDebug → jsTests)
 *   python3 tools/tests/run.py     (this machine: no node, so V8 through py_mini_racer)
 *
 * It is pure JS: the caller hands the project files in as strings, and the scripts under test
 * are evaluated with `new Function` against a hand-written DOM stub — no `require`, `fs`,
 * `vm` or real timers, because V8 as embedded by Python has none.
 *
 *   suite.init(env)      core.js logic + structure checks; then boots the real app.js
 *   suite.results()      render smoke assertions; returns {passed, failures}
 *
 * Timers run immediately, so the only thing between init() and results() is the promise
 * microtask queue — the embedder drains it by returning from one eval and calling again.
 */
var SynoFileTestSuite = (function () {
    'use strict';

    var SCRIPT_GLOBALS = ['window', 'document', 'localStorage', 'setTimeout', 'clearTimeout',
        'setInterval', 'clearInterval', 'console', 'navigator', 'btoa', 'atob',
        'TextEncoder', 'TextDecoder', 'module'];

    var state = {
        files: {},
        exists: {},
        passed: 0,
        failures: [],
        page: null,
        dom: null,
        bootError: '',
        booted: false,
        phase: 0
    };

    function check(name, condition, detail) {
        if (condition) {
            state.passed++;
        } else {
            state.failures.push(name + (detail ? ' — ' + detail : ''));
        }
    }

    function eq(actual, expected, name) {
        check(name, Object.is(actual, expected) || JSON.stringify(actual) === JSON.stringify(expected),
            'expected ' + JSON.stringify(expected) + ', got ' + JSON.stringify(actual));
    }

    function read(path) {
        return state.files[path] === undefined ? '' : state.files[path];
    }

    /** No real timers: a callback scheduled by the page runs right away. */
    function immediateTimeout(fn) {
        try {
            fn();
        } catch (e) {
            // a page timer that throws must not stop the suite
        }
        return 0;
    }

    // ------------------------------------------------------------------ DOM stub

    function createDom(html) {
        var registry = new Map();

        function makeElement(tag) {
            var listeners = {};
            var element = {
                tagName: tag,
                _html: '',
                textContent: '',
                value: '',
                placeholder: '',
                disabled: false,
                onclick: null,
                onchange: null,
                oninput: null,
                parentNode: null,
                style: {display: '', width: '', transform: '', cursor: ''},
                dataset: {},
                attributes: {},
                classList: {
                    _set: new Set(),
                    add: function (name) { this._set.add(name); },
                    remove: function (name) { this._set.delete(name); },
                    contains: function (name) { return this._set.has(name); },
                    toggle: function (name, force) {
                        var on = force === undefined ? !this._set.has(name) : !!force;
                        if (on) { this._set.add(name); } else { this._set.delete(name); }
                        return on;
                    }
                },
                get innerHTML() { return this._html; },
                set innerHTML(value) {
                    this._html = String(value);
                    var pattern = /id="([^"]+)"/g;
                    var found;
                    while ((found = pattern.exec(this._html)) !== null) {
                        if (!registry.has(found[1])) {
                            registry.set(found[1], makeElement('div'));
                        }
                    }
                },
                getAttribute: function (name) {
                    if (name === 'id') { return this.id || null; }
                    return Object.prototype.hasOwnProperty.call(this.attributes, name)
                        ? this.attributes[name] : null;
                },
                setAttribute: function (name, value) {
                    this.attributes[name] = String(value);
                },
                removeAttribute: function (name) {
                    if (name === 'src') { this.src = ''; }
                    delete this.attributes[name];
                },
                appendChild: function (child) {
                    child.parentNode = this;
                    return child;
                },
                addEventListener: function (type, handler) {
                    if (!listeners[type]) { listeners[type] = []; }
                    listeners[type].push(handler);
                },
                removeEventListener: function (type, handler) {
                    if (!listeners[type]) { return; }
                    listeners[type] = listeners[type].filter(function (fn) { return fn !== handler; });
                },
                dispatch: function (type, event) {
                    (listeners[type] || []).slice().forEach(function (fn) { fn(event || {}); });
                    if (type === 'input' && typeof this.oninput === 'function') {
                        this.oninput(event || {});
                    }
                },
                closest: function () { return null; },
                querySelectorAll: function () { return []; },
                querySelector: function () { return null; },
                focus: function () {},
                blur: function () {},
                setSelectionRange: function () {},
                getBoundingClientRect: function () {
                    return {left: 0, top: 0, width: 400, height: 800};
                },
                play: function () { return Promise.resolve(); },
                pause: function () {},
                load: function () {}
            };
            return element;
        }

        var ids = new Set();
        var idPattern = /id="([^"]+)"/g;
        var match;
        while ((match = idPattern.exec(html)) !== null) {
            ids.add(match[1]);
        }
        ids.forEach(function (id) {
            var element = makeElement('div');
            element.id = id;
            registry.set(id, element);
        });

        // data-* attributes written before the id in the same tag (the storage tabs).
        var dataPattern = /data-([a-z-]+)="([^"]*)"[^>]*?\bid="([^"]+)"/g;
        var dataMatch;
        while ((dataMatch = dataPattern.exec(html)) !== null) {
            var element = registry.get(dataMatch[3]);
            if (element) {
                var key = dataMatch[1].replace(/-([a-z])/g, function (m, c) { return c.toUpperCase(); });
                element.dataset[key] = dataMatch[2];
                element.attributes['data-' + dataMatch[1]] = dataMatch[2];
            }
        }

        // class="…" right before id="…" so the smoke test can see the base classes.
        var classPattern = /class="([^"]*)"[^>]*?\bid="([^"]+)"/g;
        var classMatch;
        while ((classMatch = classPattern.exec(html)) !== null) {
            var holder = registry.get(classMatch[2]);
            if (holder) {
                classMatch[1].split(/\s+/).filter(Boolean).forEach(function (name) {
                    holder.classList.add(name);
                });
            }
        }

        var document = {
            readyState: 'complete',
            getElementById: function (id) {
                return registry.get(id) || null;
            },
            querySelectorAll: function (selector) {
                if (selector === '.tab') {
                    return ['tab-phone', 'tab-nas', 'tab-fav', 'tab-settings']
                        .map(function (id) { return registry.get(id); })
                        .filter(Boolean);
                }
                return [];
            },
            querySelector: function () { return null; },
            addEventListener: function () {},
            removeEventListener: function () {},
            createElement: function (tag) { return makeElement(tag); }
        };

        return {document: document, registry: registry};
    }

    function createStorage() {
        var data = new Map();
        return {
            getItem: function (key) { return data.has(key) ? data.get(key) : null; },
            setItem: function (key, value) { data.set(key, String(value)); },
            removeItem: function (key) { data.delete(key); }
        };
    }

    /**
     * base64 for the harness only: V8 as embedded by Python has neither btoa/atob nor Buffer,
     * and core.js takes whichever exists. Plain JS keeps both engines identical.
     */
    var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

    function btoaPure(input) {
        var str = String(input);
        var out = '';
        for (var i = 0; i < str.length; i += 3) {
            var c1 = str.charCodeAt(i);
            var c2 = i + 1 < str.length ? str.charCodeAt(i + 1) : -1;
            var c3 = i + 2 < str.length ? str.charCodeAt(i + 2) : -1;
            out += B64.charAt(c1 >> 2);
            out += B64.charAt(((c1 & 3) << 4) | (c2 < 0 ? 0 : c2 >> 4));
            out += c2 < 0 ? '=' : B64.charAt(((c2 & 15) << 2) | (c3 < 0 ? 0 : c3 >> 6));
            out += c3 < 0 ? '=' : B64.charAt(c3 & 63);
        }
        return out;
    }

    function atobPure(input) {
        var clean = String(input).replace(/[^A-Za-z0-9+/=]/g, '');
        var out = '';
        for (var i = 0; i < clean.length; i += 4) {
            var n1 = B64.indexOf(clean.charAt(i));
            var n2 = B64.indexOf(clean.charAt(i + 1));
            var n3 = clean.charAt(i + 2) === '=' ? -1 : B64.indexOf(clean.charAt(i + 2));
            var n4 = clean.charAt(i + 3) === '=' ? -1 : B64.indexOf(clean.charAt(i + 3));
            if (n1 < 0 || n2 < 0) {
                break;
            }
            out += String.fromCharCode((n1 << 2) | (n2 >> 4));
            if (n3 >= 0) {
                out += String.fromCharCode(((n2 & 15) << 4) | (n3 >> 2));
            }
            if (n3 >= 0 && n4 >= 0) {
                out += String.fromCharCode(((n3 & 3) << 6) | n4);
            }
        }
        return out;
    }

    function base64Tools() {
        return {btoa: btoaPure, atob: atobPure};
    }

    function evaluateIn(sandbox, code, filename) {
        var fn = new Function(SCRIPT_GLOBALS.join(','), code + '\n//# sourceURL=' + filename);
        fn(sandbox.window, sandbox.document, sandbox.localStorage, sandbox.setTimeout,
            sandbox.clearTimeout, sandbox.setInterval, sandbox.clearInterval, sandbox.console,
            sandbox.navigator, sandbox.btoa, sandbox.atob, sandbox.TextEncoder,
            sandbox.TextDecoder, undefined);
    }

    // ------------------------------------------------------------ part 1: core

    function coreChecks() {
        var b64 = base64Tools();
        var sandbox = {
            console: {log: function () {}, warn: function () {}, error: function () {}},
            setTimeout: immediateTimeout,
            clearTimeout: function () {},
            setInterval: immediateTimeout,
            clearInterval: function () {},
            navigator: {userAgent: 'suite'},
            btoa: b64.btoa,
            atob: b64.atob,
            localStorage: createStorage()
        };
        sandbox.window = sandbox;
        sandbox.globalThis = sandbox;
        sandbox.self = sandbox;
        sandbox.document = createDom('<div id="root"></div>').document;

        evaluateIn(sandbox, read('app/src/main/assets/core.js'), 'core.js');
        var C = sandbox.FMCore;
        check('core.js exposes FMCore', !!C);
        if (!C) {
            return;
        }

        eq(C.normalizePath('a/b/'), '/a/b', 'normalizePath adds root and drops trailing slash');
        eq(C.normalizePath('/a//b'), '/a/b', 'normalizePath collapses double slashes');
        eq(C.normalizePath(null), '/', 'normalizePath(null) is root');
        eq(C.joinPath('/photo', 'a.jpg'), '/photo/a.jpg', 'joinPath');
        eq(C.joinPath('/', 'a'), '/a', 'joinPath from root');
        eq(C.joinPath('/a', '/b/c'), '/b/c', 'joinPath with an absolute name');
        eq(C.parentOf('/a/b'), '/a', 'parentOf');
        eq(C.parentOf('/a'), '/', 'parentOf top level');
        eq(C.nameOf('/a/b.jpg'), 'b.jpg', 'nameOf');
        eq(C.extOf('A.JPG'), 'jpg', 'extOf lowercases');
        eq(C.extOf('noext'), '', 'extOf without a dot');
        check('isImage heic', C.isImage('IMG_1.HEIC'));
        check('isVideo mkv', C.isVideo('a.MKV'));
        check('not an image for txt', !C.isImage('a.txt'));
        eq(C.kindOf('a.mp4'), 'video', 'kindOf video');
        eq(C.kindOf('a.txt'), 'file', 'kindOf file');
        eq(C.kindLabel('a.png'), '사진', 'kindLabel image');
        eq(C.kindLabel('a.mkv'), '영상', 'kindLabel video');
        eq(C.badgeOf('IMG_0001.JPG'), 'JPG', 'badgeOf extension');
        eq(C.badgeOf('noext'), 'FILE', 'badgeOf without extension');
        eq(C.formatSize(0), '0 B', 'formatSize 0');
        eq(C.formatSize(1536), '1.5 KB', 'formatSize KB');
        eq(C.formatSize(24 * 1024 * 1024), '24 MB', 'formatSize MB');
        eq(C.formatSize(-1), '-', 'formatSize negative');
        eq(C.sanitizeName('a/b:c?.jpg'), 'a_b_c_.jpg', 'sanitizeName');
        eq(C.sanitizeName('  x  '), 'x', 'sanitizeName trims');
        eq(C.uniqueName('a.jpg', ['a.jpg']), 'a (1).jpg', 'uniqueName');
        eq(C.uniqueName('a.jpg', ['a.jpg', 'a (1).jpg']), 'a (2).jpg', 'uniqueName second');
        eq(C.b64urlDecode(C.b64url('/사진/2026년 11월.jpg')), '/사진/2026년 11월.jpg',
            'b64url round trip with Korean');
        check('b64url has no padding', C.b64url('a?').indexOf('=') < 0);
        eq(C.displayPath('/storage/emulated/0/DCIM/Camera', '/storage/emulated/0'),
            '/DCIM/Camera', 'displayPath strips the phone root');
        eq(C.displayPath('/storage/emulated/0', '/storage/emulated/0'), '/', 'displayPath at root');
        eq(C.breadcrumbs('/storage/emulated/0/DCIM', '내부 저장소', '/storage/emulated/0').length,
            2, 'breadcrumbs depth');
        eq(C.breadcrumbs('/storage/emulated/0/DCIM', '내부 저장소', '/storage/emulated/0')[1].label,
            'DCIM', 'breadcrumbs label');

        var sample = [
            {name: 'b.jpg', isDir: false, size: 200, mtime: 2000},
            {name: 'a.mp4', isDir: false, size: 900, mtime: 3000},
            {name: 'Zeta', isDir: true, size: 0, mtime: 1000},
            {name: 'c.jpg', isDir: false, size: 100, mtime: 4000}
        ];
        eq(C.sortEntries(sample, 'name', 'asc').map(function (e) { return e.name; }),
            ['Zeta', 'a.mp4', 'b.jpg', 'c.jpg'], 'sort name keeps folders first');
        eq(C.sortEntries(sample, 'date', 'asc').map(function (e) { return e.name; }),
            ['Zeta', 'c.jpg', 'a.mp4', 'b.jpg'], 'sort date newest first');
        eq(C.sortEntries(sample, 'size', 'asc').map(function (e) { return e.name; }),
            ['Zeta', 'a.mp4', 'b.jpg', 'c.jpg'], 'sort size largest first');
        eq(C.filterEntries(sample, 'image', '').map(function (e) { return e.name; }),
            ['b.jpg', 'c.jpg'], 'filter images hides folders');
        eq(C.filterEntries(sample, 'video', '').map(function (e) { return e.name; }),
            ['a.mp4'], 'filter videos');
        eq(C.filterEntries(sample, 'folder', '').map(function (e) { return e.name; }),
            ['Zeta'], 'filter folders');
        eq(C.filterEntries(sample, 'all', 'c').map(function (e) { return e.name; }),
            ['c.jpg'], 'search filter');
        eq(C.visibleEntries(sample, 'all', '', 'size').map(function (e) { return e.name; }),
            ['Zeta', 'a.mp4', 'b.jpg', 'c.jpg'], 'visibleEntries sorts after filtering');
        eq(C.countByKind(sample).image, 2, 'countByKind images');
        eq(C.countByKind(sample).folder, 1, 'countByKind folders');
        eq(C.fileCount(sample), 3, 'fileCount ignores folders');
        eq(C.summary(sample), '폴더 1 · 사진 2 · 영상 1', 'summary line');

        var url = C.streamUrl('http://127.0.0.1:8000', 'tok', 's', '/photo/a b.jpg');
        check('streamUrl points at the loopback server',
            url.indexOf('http://127.0.0.1:8000/s?t=tok&p=') === 0, url);
        check('streamUrl encodes spaces', url.indexOf(' ') < 0, url);
        check('streamUrl adds the size', C.streamUrl('http://x', 't', 'lt', '/a.jpg', 256)
            .indexOf('&s=256') > 0, 'size parameter');

        eq(C.sortLabel('date'), '날짜순', 'sortLabel date');
        eq(C.nextSort('date'), 'name', 'nextSort date -> name');
        eq(C.nextSort('size'), 'date', 'nextSort wraps to date');
        eq(C.filterLabel('video'), '영상', 'filterLabel');
        eq(C.nextFilter('all'), 'image', 'nextFilter');
        eq(C.nextFilter('folder'), 'all', 'nextFilter wraps');

        eq(C.serverUrl('192.168.0.7', '5001'), 'https://192.168.0.7:5001', 'serverUrl https');
        eq(C.serverUrl('192.168.0.7', '5000'), 'http://192.168.0.7:5000', 'serverUrl http on 5000');
        eq(C.serverUrl('nas.synology.me', ''), 'https://nas.synology.me', 'serverUrl without port');
        eq(C.serverUrl('https://nas.example:5001/', '5001'), 'https://nas.example:5001',
            'serverUrl keeps a typed scheme');
        eq(C.splitServerUrl('https://nas.example:5001').host, 'nas.example', 'splitServerUrl host');
        eq(C.splitServerUrl('https://nas.example:5001').port, '5001', 'splitServerUrl port');
        eq(C.splitServerUrl('http://192.168.0.7:5000').secure, false, 'splitServerUrl http');
        eq(C.splitServerUrl('').host, '', 'splitServerUrl empty');

        // ---- WebDAV: the address decides the protocol, the scheme the user typed wins ----
        eq(C.isWebDavPort('5005'), true, 'isWebDavPort 5005');
        eq(C.isWebDavPort('5006'), true, 'isWebDavPort 5006');
        eq(C.isWebDavPort('5001'), false, 'isWebDavPort 5001');
        eq(C.protocolFromHost('nas.synology.me:5005'), 'webdav', 'protocolFromHost :5005');
        eq(C.protocolFromHost('nas.synology.me'), '', 'protocolFromHost without a port');
        eq(C.suggestProtocol('nas.synology.me', '5006'), 'webdav', 'suggestProtocol 5006');
        eq(C.suggestProtocol('nas.synology.me', '5001'), '', 'suggestProtocol 5001 is quiet');
        eq(C.protocolFor('nas.synology.me', '5005', 'dsm'), 'webdav',
            'protocolFor follows the port over the current choice');
        eq(C.protocolFor('nas.synology.me', '5001', 'webdav'), 'webdav',
            'protocolFor keeps an explicit choice when the port is silent');
        eq(C.protocolFor('nas.synology.me', '5001', ''), 'dsm', 'protocolFor defaults to DSM');
        eq(C.normalizeProtocol('WebDAV'), 'webdav', 'normalizeProtocol is case-blind');
        eq(C.normalizeProtocol('ftp'), '', 'normalizeProtocol rejects other words');
        eq(C.nextProtocol('dsm'), 'webdav', 'nextProtocol dsm -> webdav');
        eq(C.protocolLabel('webdav'), 'WebDAV', 'protocolLabel');

        eq(C.serverUrl('nas.synology.me', '5005', 'webdav'), 'http://nas.synology.me:5005',
            'serverUrl webdav 5005 is http');
        eq(C.serverUrl('nas.synology.me', '5006', 'webdav'), 'https://nas.synology.me:5006',
            'serverUrl webdav 5006 is https');
        eq(C.serverUrl('nas.synology.me', '5005'), 'http://nas.synology.me:5005',
            'serverUrl infers webdav from the port');
        eq(C.serverUrl('nas.synology.me', '', 'webdav'), 'http://nas.synology.me:5005',
            'serverUrl webdav without a port uses 5005');
        eq(C.serverUrl('https://nas.synology.me', '', 'webdav'), 'https://nas.synology.me:5006',
            'serverUrl keeps a typed https scheme');
        eq(C.serverUrl('http://nas.synology.me', '5006', 'webdav'), 'http://nas.synology.me:5006',
            'serverUrl keeps a typed http scheme');
        eq(C.serverUrl('nas.synology.me:5005', '', 'webdav'), 'http://nas.synology.me:5005',
            'serverUrl reads a port typed into the address box');
        eq(C.serverUrl('choi-film.synology.me:5005/webdav', '', 'webdav'),
            'http://choi-film.synology.me:5005/webdav', 'serverUrl keeps a typed WebDAV path');
        eq(C.splitServerUrl('http://nas.synology.me:5005').protocol, 'webdav',
            'splitServerUrl sees WebDAV');
        eq(C.splitServerUrl('http://nas.synology.me:5005').secure, false,
            'splitServerUrl webdav is not secure');
        eq(C.splitServerUrl('https://nas.synology.me:5006').protocol, 'webdav',
            'splitServerUrl sees secure WebDAV');
        eq(C.splitServerUrl('https://nas.synology.me:5001').protocol, 'dsm',
            'splitServerUrl sees DSM');
        eq(C.splitServerUrl('http://nas:5005/webdav').path, '/webdav', 'splitServerUrl keeps the path');
        eq(C.serverUrl(C.splitServerUrl('http://nas.synology.me:5005').host,
            C.splitServerUrl('http://nas.synology.me:5005').port, 'webdav'),
            'http://nas.synology.me:5005', 'split then serverUrl round trips');

        eq(C.davDecode(C.davEncodePath('/사진/2026년 11월/A B.jpg')),
            '/사진/2026년 11월/A B.jpg', 'davEncodePath round trips Korean and spaces');
        eq(C.davEncodePath('/사진/2026년 11월.jpg'),
            '/%EC%82%AC%EC%A7%84/2026%EB%85%84%2011%EC%9B%94.jpg', 'davEncodePath encodes Korean');
        check('davEncodePath never leaves a space', C.davEncodePath('/a b/c d').indexOf(' ') < 0);
        eq(C.davEncodePath('/a-b_c.d~e'), '/a-b_c.d~e', 'davEncodePath keeps unreserved letters');
        eq(C.davDecode('/a%20b%2Bc+d'), '/a b+c+d', 'davDecode keeps a literal plus');
        eq(C.davDecode('%EA%B0%80'), '가', 'davDecode UTF-8');

        eq(C.davUrl('http://h:5005', '/'), 'http://h:5005/', 'davUrl root');
        eq(C.davUrl('http://h:5005', '/사진'), 'http://h:5005/%EC%82%AC%EC%A7%84', 'davUrl encodes');
        eq(C.davUrl('http://h:5005/', '/a'), 'http://h:5005/a', 'davUrl trims the base slash');
        eq(C.davBasePath('http://h:5005'), '/', 'davBasePath at the root');
        eq(C.davBasePath('http://h:5005/dav'), '/dav', 'davBasePath with a prefix');
        eq(C.davPathOf('http://h:5005/photo/%EA%B0%80.jpg', 'http://h:5005'), '/photo/가.jpg',
            'davPathOf decodes an absolute href');
        eq(C.davPathOf('/photo/', 'http://h:5005'), '/photo', 'davPathOf trims the trailing slash');
        eq(C.davPathOf('http://h:5005/dav/photo/1.jpg', 'http://h:5005/dav'), '/photo/1.jpg',
            'davPathOf strips the WebDAV prefix');
        eq(C.davPathOf('http://h:5005/dav/a.jpg', 'http://h:5005/dav/'), '/a.jpg',
            'davPathOf survives a base with a trailing slash');
        eq(C.davPathOf('http://other:5005/a.jpg', 'http://h:5005/dav'), '',
            'davPathOf drops a path outside the server');

        eq(C.clampPercent(38.6), 39, 'clampPercent rounds');
        eq(C.clampPercent(-2), 0, 'clampPercent floors at 0');
        eq(C.clampPercent(310), 100, 'clampPercent caps at 100');
        eq(C.transferSummary([{status: 'running'}, {status: 'done'}, {status: 'failed'}]),
            {running: 1, done: 1, failed: 1}, 'transferSummary');
        eq(C.transferLine([]), '전송 내역 없음', 'transferLine empty');
        eq(C.transferLine([{status: 'running'}, {status: 'done'}]), '전송 중 1 · 완료 1',
            'transferLine mixed');
    }

    // ------------------------------------------------------ part 2: structure

    function structureChecks() {
        var html = read('app/src/main/assets/index.html');
        var appJs = read('app/src/main/assets/app.js');
        var bridgeJs = read('app/src/main/assets/bridge.js');
        var iconsJs = read('app/src/main/assets/icons.js');
        var manifest = read('app/src/main/AndroidManifest.xml');
        var activity = read('app/src/main/java/com/choi/filemanager/MainActivity.java');
        var nasBridge = read('app/src/main/java/com/choi/filemanager/NasBridge.java');
        var davClient = read('app/src/main/java/com/choi/filemanager/DavClient.java');
        var davXml = read('app/src/main/java/com/choi/filemanager/DavXml.java');
        var httpWire = read('app/src/main/java/com/choi/filemanager/HttpWire.java');
        var davAll = nasBridge + davClient + davXml + httpWire;
        var localBridge = read('app/src/main/java/com/choi/filemanager/LocalFileBridge.java');
        var localServer = read('app/src/main/java/com/choi/filemanager/LocalServer.java');
        var gradle = read('app/build.gradle');
        var strings = read('app/src/main/res/values/strings.xml');

        var coreAt = html.indexOf('core.js');
        var iconsAt = html.indexOf('icons.js');
        var bridgeAt = html.indexOf('bridge.js');
        var appAt = html.indexOf('app.js');
        check('core.js loads before bridge.js and app.js',
            coreAt >= 0 && coreAt < bridgeAt && bridgeAt < appAt,
            coreAt + '/' + bridgeAt + '/' + appAt);
        check('icons.js loads before app.js', iconsAt >= 0 && iconsAt < appAt, String(iconsAt));
        check('icons are an inline SVG bundle', iconsJs.indexOf('FMIcons') >= 0
            && iconsJs.indexOf("'download'") >= 0 && iconsJs.indexOf('<path') >= 0);
        check('bridge unwraps the NAS {success,data} payload',
            bridgeJs.indexOf('function unwrap') >= 0);

        // Offline safety: nothing may come from a CDN.
        check('index.html loads nothing from a CDN',
            !/(src|href)\s*=\s*"https?:\/\//i.test(html));
        ['cdn.tailwindcss', 'unpkg.com', 'fonts.googleapis', 'fonts.gstatic', 'placehold.co']
            .forEach(function (host) {
                check('index.html does not reference ' + host, html.indexOf(host) < 0);
            });
        check('index.html has no <link rel=stylesheet>', html.indexOf('rel="stylesheet"') < 0);

        var requiredIds = [
            'app', 'connBadge', 'connLabel', 'serverSubtitle', 'btnTransfers', 'transferBadge',
            'btnServerSettings', 'tab-nas', 'tab-phone', 'nasCountPill', 'phoneCountPill',
            'favbar', 'searchInput', 'clearSearch', 'sortBtn', 'sortLabel', 'viewToggle',
            'selectModeBtn', 'btnUp', 'crumbs', 'btnFav', 'btnNewFolder', 'btnUpload',
            'btnReload', 'countText', 'filterChip', 'fileContainer', 'emptyState', 'emptyTitle',
            'emptyHint', 'emptyAction', 'selectionBar', 'btnSelectAll', 'selCount',
            'btnExitSelect', 'btnPrimaryTransfer', 'primaryTransferLabel', 'primaryTransferIcon',
            'btnMove', 'btnCopy', 'btnRename', 'btnShare', 'btnShareLabel', 'btnInfo', 'btnDelete',
            'miniToast', 'miniTitle', 'miniSub', 'miniPercent', 'serverModal', 'cfgHost',
            'cfgPort', 'cfgUser', 'cfgPass', 'cfgOtp', 'cfgState', 'cfgSsl', 'cfgAuto',
            'savedServers', 'btnCloseServer', 'btnConnect', 'btnPermAction', 'permLine',
            'permTitle', 'permText', 'viewerModal', 'viewerTitle', 'viewerMeta', 'viewerStage',
            'viewerImg', 'viewerVideo', 'viewerPrev', 'viewerNext', 'viewerZoom',
            'viewerDownload', 'viewerSave', 'viewerSaveLabel', 'viewerRename', 'viewerDelete',
            'viewerClose', 'viewerCount', 'transferDrawer', 'transferList', 'transferSummary',
            'btnCloseTransfers', 'btnClearTransfers', 'menuSheet', 'menuFilter', 'menuHidden',
            'menuServer', 'menuCache', 'menuLogout', 'menuAbout', 'btnCloseMenu',
            'menuFilterValue', 'menuHiddenValue', 'menuServerValue', 'menuCacheValue',
            'pickerModal', 'pickerTitle', 'pickerPath', 'pickerList', 'btnPickerChoose',
            'btnPickerCancel', 'btnClosePicker', 'dialog', 'dlgTitle', 'dlgDesc', 'dlgInput',
            'dlgInputWrap', 'dlgSelect', 'dlgSelectWrap', 'dlgBody', 'dlgOk', 'dlgCancel',
            'toast', 'toastMsg'
        ];
        requiredIds.forEach(function (id) {
            check('index.html has #' + id, html.indexOf('id="' + id + '"') >= 0);
        });

        var usedIds = new Set();
        var idPattern = /\$\('([A-Za-z0-9_-]+)'\)/g;
        var match;
        while ((match = idPattern.exec(appJs)) !== null) {
            usedIds.add(match[1]);
        }
        check('app.js looks up a healthy number of ids', usedIds.size > 60, String(usedIds.size));
        usedIds.forEach(function (id) {
            check('app.js only uses existing #' + id, html.indexOf('id="' + id + '"') >= 0);
        });

        var nasActions = new Set();
        var localActions = new Set();
        var actionPattern = /FM\.(nas|local)\('([a-zA-Z]+)'/g;
        while ((match = actionPattern.exec(appJs)) !== null) {
            (match[1] === 'nas' ? nasActions : localActions).add(match[2]);
        }
        check('app.js drives the NAS bridge', nasActions.size >= 8, Array.from(nasActions).join(','));
        nasActions.forEach(function (action) {
            check('NasBridge handles ' + action, nasBridge.indexOf('case "' + action + '"') >= 0);
        });
        localActions.forEach(function (action) {
            check('LocalFileBridge handles ' + action, localBridge.indexOf('case "' + action + '"') >= 0);
        });

        check('MainActivity registers window.NasBridge',
            activity.indexOf('addJavascriptInterface(new NasJs(), "NasBridge")') >= 0);
        check('MainActivity registers window.LocalFileBridge',
            activity.indexOf('addJavascriptInterface(new LocalJs(), "LocalFileBridge")') >= 0);
        check('MainActivity starts the loopback server', activity.indexOf('server.start()') >= 0);
        check('MainActivity hands the back key to the page', activity.indexOf('window.fmHandleBack') >= 0);
        check('MainActivity paints the dark background', activity.indexOf('0xFF0B0F19') >= 0);
        check('FileProvider is used for external apps',
            activity.indexOf('FileProvider.getUriForFile') >= 0);
        check('bridge.js exposes fmCallback/fmProgress',
            bridgeJs.indexOf('window.fmCallback') >= 0 && bridgeJs.indexOf('window.fmProgress') >= 0);
        check('bridge.js exposes the loopback media URL helper', bridgeJs.indexOf('FM.mediaUrl') >= 0);
        check('app.js implements window.fmHandleBack', appJs.indexOf('window.fmHandleBack') >= 0);
        check('app.js asks for the storage permission', appJs.indexOf('FM.allowAllFiles') >= 0);
        check('app.js keeps restore-on-resume', appJs.indexOf('FM.onResume') >= 0);

        [
            'SYNO.API.Auth', 'SYNO.API.Info', 'SYNO.FileStation.List', 'SYNO.FileStation.Thumb',
            'SYNO.FileStation.Download', 'SYNO.FileStation.Upload', 'SYNO.FileStation.CreateFolder',
            'SYNO.FileStation.Rename', 'SYNO.FileStation.Delete', 'SYNO.FileStation.CopyMove'
        ].forEach(function (api) {
            check('NasBridge calls ' + api, nasBridge.indexOf(api) >= 0);
        });
        check('NAS list_share is used for share roots', nasBridge.indexOf('"list_share"') >= 0);
        check('NAS login sends the OTP code', nasBridge.indexOf('otp_code') >= 0);
        check('NAS login supports a self-signed certificate',
            nasBridge.indexOf('setSSLSocketFactory') >= 0 && nasBridge.indexOf('X509TrustManager') >= 0);
        check('NAS uploads as multipart', nasBridge.indexOf('multipart/form-data') >= 0);
        check('NAS password is keystore-encrypted', nasBridge.indexOf('CryptoStore.encrypt') >= 0);
        check('NasBridge speaks WebDAV',
            ['PROPFIND', 'MKCOL', 'MOVE', 'COPY', 'PUT', 'DELETE', 'Depth', 'Authorization',
                'Basic '].every(function (word) { return davAll.indexOf(word) >= 0; }));
        check('NasBridge keeps the WebDAV branch beside DSM',
            nasBridge.indexOf('PROTOCOL_WEBDAV') >= 0 && nasBridge.indexOf('SYNO.API.Auth') >= 0);
        check('MOVE/COPY send an absolute Destination and Overwrite',
            davClient.indexOf('"Destination"') >= 0 && davClient.indexOf('"Overwrite"') >= 0
            && davClient.indexOf('Core.davUrl(base') >= 0);
        check('NasBridge sends the WebDAV body itself', httpWire.indexOf('class HttpWire') >= 0);
        check('the DAV multistatus parser is pure Java', davXml.indexOf('DAV:') >= 0);
        check('app.js sends the chosen protocol on login',
            appJs.indexOf('protocol: protocol') >= 0 && appJs.indexOf("FM.nas('login'") >= 0);
        check('index.html offers the protocol switch',
            html.indexOf('id="cfgProtoDsm"') >= 0 && html.indexOf('id="cfgProtoDav"') >= 0
            && html.indexOf('id="cfgProto"') >= 0);
        check('LocalServer serves /f /lt /s /nt',
            ['"/f"', '"/lt"', '"/s"', '"/nt"'].every(function (route) {
                return localServer.indexOf(route) >= 0;
            }));
        check('LocalServer supports Range responses', localServer.indexOf('"Partial Content"') >= 0);

        [
            'android.permission.INTERNET',
            'android.permission.ACCESS_NETWORK_STATE',
            'android.permission.MANAGE_EXTERNAL_STORAGE',
            'android.permission.READ_MEDIA_IMAGES',
            'android.permission.READ_MEDIA_VIDEO',
            'android.permission.READ_EXTERNAL_STORAGE',
            'android.permission.WRITE_EXTERNAL_STORAGE'
        ].forEach(function (permission) {
            check('manifest declares ' + permission, manifest.indexOf(permission) >= 0);
        });
        check('manifest allows cleartext HTTP for a LAN NAS',
            manifest.indexOf('android:usesCleartextTraffic="true"') >= 0);
        check('manifest keeps legacy storage for Android 10',
            manifest.indexOf('android:requestLegacyExternalStorage="true"') >= 0);
        check('manifest declares the FileProvider',
            manifest.indexOf('${applicationId}.fileprovider') >= 0);
        check('manifest declares MainActivity as launcher',
            manifest.indexOf('android.intent.action.MAIN') >= 0);

        check('applicationId stays com.choi.filemanager',
            gradle.indexOf('applicationId "com.choi.filemanager"') >= 0);
        check('versionCode is 3', /versionCode 3\b/.test(gradle));
        check('versionName is 1.2', gradle.indexOf('versionName "1.2"') >= 0);
        check('signing uses the fixed keystore',
            gradle.indexOf("storeFile file('filemanager-fixed.p12')") >= 0);
        check('signing alias is filemanager', gradle.indexOf("keyAlias 'filemanager'") >= 0);
        check('the build checks the v1.0 certificate fingerprint',
            gradle.indexOf('42297011297aed34a76fd5325267f9f30c52258da96835f1d15179475f494623') >= 0);
        check('fixed keystore is committed', !!state.exists['app/filemanager-fixed.p12']);
        check('launcher icon is an adaptive vector',
            !!state.exists['app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml']
            && !!state.exists['app/src/main/res/drawable/ic_launcher_foreground.xml']);
        check('app label is SynoFile Pro', strings.indexOf('SynoFile Pro') >= 0);
        check('theme paints the dark background',
            read('app/src/main/res/values/colors.xml').indexOf('#FF0B0F19') >= 0);

        check('app.js never calls fetch()', !/\bfetch\s*\(/.test(appJs));
        check('app.js never uses XMLHttpRequest', appJs.indexOf('XMLHttpRequest') < 0);
        check('app.js carries no leftover mock data',
            appJs.indexOf('myhome.synology') < 0 && appJs.indexOf('INITIAL_DATA') < 0
            && appJs.indexOf('unsplash') < 0 && appJs.indexOf('startTransferSimulation') < 0);
        check('the markup carries no mock server address',
            html.indexOf('myhome.synology') < 0 && html.indexOf('192.168.0.100') < 0);
    }

    // --------------------------------------------------------- part 3: render

    function buildPage() {
        var html = read('app/src/main/assets/index.html');
        var b64 = base64Tools();
        var dom = createDom(html);
        state.dom = dom;

        var page = {
            console: {log: function () {}, warn: function () {}, error: function () {}},
            setTimeout: immediateTimeout,
            clearTimeout: function () {},
            setInterval: immediateTimeout,
            clearInterval: function () {},
            navigator: {userAgent: 'suite'},
            localStorage: createStorage(),
            document: dom.document,
            btoa: b64.btoa,
            atob: b64.atob
        };
        page.window = page;
        page.globalThis = page;
        page.self = page;

        var phoneEntries = [
            {name: '2026 제주 여행', path: '/storage/emulated/0/DCIM/Camera/2026 제주 여행',
                isDir: true, size: 0, mtime: Date.now() - 3600000, kind: 'folder'},
            {name: 'IMG_0001.jpg', path: '/storage/emulated/0/DCIM/Camera/IMG_0001.jpg',
                isDir: false, size: 4200000, mtime: Date.now() - 600000, kind: 'image'},
            {name: 'VID_0002.mp4', path: '/storage/emulated/0/DCIM/Camera/VID_0002.mp4',
                isDir: false, size: 92000000, mtime: Date.now() - 9000000, kind: 'video'},
            {name: '메모.txt', path: '/storage/emulated/0/DCIM/Camera/메모.txt',
                isDir: false, size: 1200, mtime: Date.now() - 1600000000, kind: 'file'}
        ];
        var nasEntries = [
            {name: 'photo', path: '/photo', isDir: true, size: 0, mtime: Date.now(), kind: 'folder'},
            {name: 'video', path: '/video', isDir: true, size: 0, mtime: Date.now(), kind: 'folder'}
        ];

        function answer(reqId, ok, payload) {
            page.fmCallback(reqId, ok, JSON.stringify(payload));
        }

        page.LocalFileBridge = {
            call: function (reqId, action, raw) {
                var params = JSON.parse(raw || '{}');
                if (action === 'status') {
                    answer(reqId, true, {
                        success: true, sdk: 34, allFiles: true,
                        serverBase: 'http://127.0.0.1:1', serverToken: 'token',
                        videoCacheDir: '/cache/video',
                        roots: [{label: '내부 저장소', path: '/storage/emulated/0', kind: 'phone'}],
                        storage: {total: 1, free: 1}
                    });
                    return;
                }
                if (action === 'list') {
                    answer(reqId, true, {
                        success: true, path: params.path || '/storage/emulated/0',
                        parent: '/storage/emulated/0', entries: phoneEntries,
                        quick: [{path: '/storage/emulated/0/DCIM/Camera', label: '카메라'}],
                        storage: {total: 1, free: 1}
                    });
                    return;
                }
                if (action === 'copyMove') {
                    answer(reqId, true, {success: true, count: 1, failed: []});
                    return;
                }
                answer(reqId, true, {success: true});
            },
            thumbCacheSize: function () { return 0; },
            clearThumbCache: function () {},
            requestAllFiles: function () {}
        };

        page.NasBridge = {
            call: function (reqId, action) {
                if (action === 'status') {
                    answer(reqId, true, {
                        connected: false, lastUrl: 'https://nas.example:5001', lastAccount: 'choi',
                        hasSavedPassword: false, servers: []
                    });
                    return;
                }
                if (action === 'list') {
                    // Wrapped exactly as the real NasBridge wraps it — that is what unwrap() is for.
                    answer(reqId, true, {
                        success: true,
                        data: {path: '/', parent: '/', entries: nasEntries, total: 2}
                    });
                    return;
                }
                answer(reqId, true, {success: true});
            },
            thumbCacheSize: function () { return 0; },
            clearThumbCache: function () {}
        };

        state.page = page;

        try {
            evaluateIn(page, read('app/src/main/assets/core.js'), 'core.js');
            evaluateIn(page, read('app/src/main/assets/icons.js'), 'icons.js');
            evaluateIn(page, read('app/src/main/assets/bridge.js'), 'bridge.js');
            evaluateIn(page, read('app/src/main/assets/app.js'), 'app.js');
        } catch (error) {
            state.bootError = error && error.message ? error.message : String(error);
        }
        state.booted = true;
    }

    function get(id) {
        return state.dom.document.getElementById(id);
    }

    /** A tap on a generated row/tile: the handler reads data-* off the walk-up target. */
    function tap(node, attrs) {
        node.onclick({
            target: {
                getAttribute: function (name) {
                    return attrs[name] === undefined ? null : attrs[name];
                },
                parentNode: null
            }
        });
    }

    // The smoke test is split into phases because each tap that loads a folder resolves
    // through promise microtasks; the runner drains the queue between results() calls.
    var PHASES = [

        // ---- everything that settles synchronously ----
        function () {
            var page = state.page;
            check('phone folder is rendered on boot',
                get('fileContainer').innerHTML.indexOf('IMG_0001.jpg') >= 0,
                get('fileContainer').innerHTML.slice(0, 120));
            check('folder row is rendered',
                get('fileContainer').innerHTML.indexOf('2026 제주 여행') >= 0);
            check('video tile shows a play badge',
                get('fileContainer').innerHTML.indexOf('playbtn') >= 0);
            check('count line summarises the folder',
                get('countText').textContent.indexOf('사진') >= 0, get('countText').textContent);
            check('breadcrumb starts at the phone root',
                get('crumbs').innerHTML.indexOf('내부 저장소') >= 0, get('crumbs').innerHTML);
            check('favourite bar shows the quick folder',
                get('favbar').innerHTML.indexOf('카메라') >= 0, get('favbar').innerHTML);
            check('phone tab is active', get('tab-phone').classList.contains('on'));
            check('nas tab is not active', !get('tab-nas').classList.contains('on'));
            check('header shows the disconnected badge',
                get('connLabel').textContent.indexOf('연결 안 됨') >= 0, get('connLabel').textContent);

            get('sortBtn').onclick();
            check('sort cycles to 이름순', get('sortLabel').textContent === '이름순',
                get('sortLabel').textContent);
            get('sortBtn').onclick();
            get('sortBtn').onclick();
            check('sort cycles back to 날짜순', get('sortLabel').textContent === '날짜순',
                get('sortLabel').textContent);

            get('viewToggle').onclick();
            check('list view renders row items',
                get('fileContainer').innerHTML.indexOf('rowitem') >= 0);
            get('viewToggle').onclick();

            get('searchInput').value = 'IMG';
            get('searchInput').dispatch('input', {});
            check('search narrows the list',
                get('fileContainer').innerHTML.indexOf('IMG_0001.jpg') >= 0
                && get('fileContainer').innerHTML.indexOf('VID_0002.mp4') < 0,
                get('fileContainer').innerHTML.slice(0, 160));
            get('clearSearch').onclick();
            check('clearing the search brings files back',
                get('fileContainer').innerHTML.indexOf('VID_0002.mp4') >= 0);

            get('selectModeBtn').onclick();
            check('select mode shows the selection bar',
                !get('selectionBar').classList.contains('hide'));
            get('btnSelectAll').onclick();
            check('select all counts every visible item',
                get('selCount').textContent === '4개 선택됨', get('selCount').textContent);
            check('primary button says upload on the phone tab',
                get('primaryTransferLabel').textContent.indexOf('업로드') >= 0,
                get('primaryTransferLabel').textContent);
            get('btnExitSelect').onclick();
            check('cancel clears the selection', get('selectionBar').classList.contains('hide'));

            get('tab-nas').onclick();
            check('nas tab asks to connect the server',
                get('fileContainer').innerHTML.indexOf('서버를 연결해 주세요') >= 0,
                get('fileContainer').innerHTML.slice(0, 200));
            check('connect card has the settings button',
                get('fileContainer').innerHTML.indexOf('openServer') >= 0);
            check('header shows the saved server address',
                get('serverSubtitle').textContent.indexOf('nas.example') >= 0,
                get('serverSubtitle').textContent);

            get('btnServerSettings').onclick();
            check('server modal opens', !get('serverModal').classList.contains('hide'));
            check('server modal prefills the address', get('cfgHost').value === 'nas.example',
                get('cfgHost').value);
            check('server modal prefills the port', get('cfgPort').value === '5001',
                get('cfgPort').value);
            check('server modal starts on DSM', get('cfgProtoDsm').classList.contains('on')
                && !get('cfgProtoDav').classList.contains('on'));
            tap(get('cfgProto'), {'data-proto': 'webdav'});
            check('choosing WebDAV turns its button on', get('cfgProtoDav').classList.contains('on')
                && !get('cfgProtoDsm').classList.contains('on'));
            check('choosing WebDAV moves the port to 5005', get('cfgPort').value === '5005',
                get('cfgPort').value);
            check('the WebDAV hint explains the ports',
                get('cfgProtoHint').textContent.indexOf('5005') >= 0,
                get('cfgProtoHint').textContent);
            tap(get('cfgProto'), {'data-proto': 'dsm'});
            check('going back to DSM restores port 5001', get('cfgPort').value === '5001',
                get('cfgPort').value);
            get('cfgPort').value = '5006';
            get('cfgPort').dispatch('input', {});
            check('typing 5006 selects WebDAV by itself', get('cfgProtoDav').classList.contains('on'));
            get('cfgPort').value = '5001';
            get('cfgPort').dispatch('input', {});
            get('btnCloseServer').onclick();
            check('server modal closes', get('serverModal').classList.contains('hide'));

            get('btnTransfers').onclick();
            check('transfer drawer opens', !get('transferDrawer').classList.contains('hide'));
            check('empty transfer drawer explains itself',
                get('transferList').innerHTML.indexOf('전송 내역이 없습니다') >= 0);
            get('btnCloseTransfers').onclick();

            get('menuSheet').classList.remove('hide');
            get('menuAbout').onclick();
            check('about dialog shows the version',
                get('dlgBody').innerHTML.indexOf('1.2') >= 0,
                get('dlgBody').innerHTML.slice(0, 200));
            get('dlgCancel').onclick();

            get('tab-phone').onclick();
            get('btnNewFolder').onclick();
            check('new folder dialog opens', !get('dialog').classList.contains('hide'));
            check('new folder dialog asks for a name',
                get('dlgTitle').textContent.indexOf('새 폴더') >= 0, get('dlgTitle').textContent);
            get('dlgInput').value = '새 앨범';
            get('dlgOk').onclick();
            check('new folder dialog closes after create',
                get('dialog').classList.contains('hide'));
        },

        // ---- tap a folder row (loads through the bridge, resolves next phase) ----
        function () {
            tap(get('fileContainer'), {
                'data-path': '/storage/emulated/0/DCIM/Camera/2026 제주 여행'
            });
        },

        function () {
            check('tapping a folder row navigates into it',
                get('crumbs').innerHTML.indexOf('2026 제주 여행') >= 0, get('crumbs').innerHTML);
            get('btnUp').onclick();
        },

        function () {
            check('up button walks back out',
                get('crumbs').innerHTML.indexOf('2026 제주 여행') < 0, get('crumbs').innerHTML);
            tap(get('fileContainer'), {
                'data-path': '/storage/emulated/0/DCIM/Camera/IMG_0001.jpg'
            });
        },

        function () {
            var page = state.page;
            check('tapping a photo opens the viewer',
                !get('viewerModal').classList.contains('hide'));
            check('viewer shows the file name',
                get('viewerTitle').textContent === 'IMG_0001.jpg', get('viewerTitle').textContent);
            check('viewer draws the photo from the loopback server',
                String(get('viewerImg').src || '').indexOf('/f?t=') > 0,
                String(get('viewerImg').src || ''));
            check('viewer shows the position',
                get('viewerCount').textContent.indexOf('/') > 0, get('viewerCount').textContent);
            get('viewerNext').onclick();
            check('viewer moves to the next photo',
                get('viewerTitle').textContent !== 'IMG_0001.jpg',
                get('viewerTitle').textContent);
            var handled = page.fmHandleBack();
            check('back closes the viewer',
                handled === true && get('viewerModal').classList.contains('hide'), String(handled));
            tap(get('fileContainer'), {
                'data-path': '/storage/emulated/0/DCIM/Camera/VID_0002.mp4',
                'data-act': 'transfer'
            });
        },

        function () {
            var page = state.page;
            check('quick transfer without a NAS asks to connect',
                !get('serverModal').classList.contains('hide'));
            get('btnCloseServer').onclick();

            tap(get('fileContainer'), {
                'data-path': '/storage/emulated/0/DCIM/Camera/2026 제주 여행',
                'data-act': 'menu'
            });
            check('the row menu lists file actions',
                get('dlgBody').innerHTML.indexOf('이름 변경') >= 0
                && get('dlgBody').innerHTML.indexOf('삭제') >= 0);
            get('dlgCancel').onclick();

            var subBack = page.fmHandleBack();
            check('back inside a subfolder is consumed', subBack === true, String(subBack));

            get('tab-nas').onclick();
            var nasBack = page.fmHandleBack();
            check('back leaves a disconnected NAS tab for the phone tab',
                nasBack === true && get('tab-phone').classList.contains('on'), String(nasBack));
        },

        // ---- land back on the phone root through the 홈 chip ----
        function () {
            tap(get('favbar'), {'data-nav': '/storage/emulated/0'});
        },

        function () {
            var page = state.page;
            check('the home chip returns to the phone root',
                get('crumbs').innerHTML.indexOf('내부 저장소') >= 0,
                get('crumbs').innerHTML);
            var rootBack = page.fmHandleBack();
            check('back at the phone root is not consumed', rootBack === false, String(rootBack));
            check('the page still shows the folder after that back press',
                get('fileContainer').innerHTML.indexOf('IMG_0001.jpg') >= 0);
        }
    ];

    return {
        init: function (env) {
            state.files = env.files || {};
            state.exists = env.exists || {};
            state.passed = 0;
            state.failures = [];
            state.phase = 0;
            state.bootError = '';
            coreChecks();
            structureChecks();
            buildPage();
        },
        results: function () {
            if (state.bootError) {
                check('app.js loads without throwing', false, state.bootError);
                state.bootError = '';
                state.phase = PHASES.length;
            } else if (!state.booted) {
                check('the page was built', false, 'init() was not called');
                state.phase = PHASES.length;
            } else {
                var phase = PHASES[state.phase];
                if (phase) {
                    phase();
                    state.phase++;
                }
            }
            return {
                passed: state.passed,
                failures: state.failures,
                done: state.phase >= PHASES.length
            };
        }
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = SynoFileTestSuite;
}
