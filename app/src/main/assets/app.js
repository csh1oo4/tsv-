/*
 * app.js — the 「SynoFile Pro」 screen.
 *
 * One screen, two storage sides (시놀로지 NAS / 내 폰 저장소) drawn exactly the way the
 * design puts them: header with the connection badge and the transfer drawer, dual tabs,
 * favourite chips, search / sort / view / select row, breadcrumbs, folder cards, a 2-column
 * media grid (or list), the floating selection bar, the full-screen viewer and the four
 * sheets (server, transfers, menu, dialog).
 *
 * Every byte that moves comes from the two Java bridges (see bridge.js):
 *   FM.nas('list' | 'mkdir' | 'rename' | 'delete' | 'copyMove' | 'upload' | 'download' …)
 *   FM.local('list' | 'mkdir' | 'rename' | 'delete' | 'copyMove' | 'open' | 'share' …)
 * Photos and videos are shown from LocalServer (FM.mediaUrl: /nt /s for the NAS, /lt /f for
 * the phone), never by reading files into the page.
 */
(function () {
    'use strict';

    var C = window.FMCore;
    var FM = window.FM;
    var I = window.FMIcons;

    function $(id) {
        return document.getElementById(id);
    }

    function ic(name, cls) {
        return I ? I.svg(name, cls) : '';
    }

    function esc(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function each(nodes, fn) {
        if (!nodes) {
            return;
        }
        for (var i = 0; i < nodes.length; i++) {
            fn(nodes[i], i);
        }
    }

    /** Walks up from a tap target to the element carrying data-<attr>. */
    function closestAttr(node, attr) {
        var el = node;
        for (var guard = 0; el && guard < 12; guard++) {
            if (el.getAttribute) {
                var value = el.getAttribute(attr);
                if (value !== null && value !== undefined && value !== '') {
                    return el;
                }
            }
            el = el.parentNode;
        }
        return null;
    }

    var LS = {
        view: 'sf_view',
        sort: 'sf_sort',
        filter: 'sf_filter',
        favs: 'sf_favs',
        settings: 'sf_settings'
    };

    var VERSION = '1.2';
    var PACKAGE = 'com.choi.filemanager';

    function read(key, fallback) {
        try {
            var v = localStorage.getItem(key);
            return v === null ? fallback : v;
        } catch (e) {
            return fallback;
        }
    }

    function write(key, value) {
        try {
            localStorage.setItem(key, value);
        } catch (e) {
            // storing a preference is never worth failing a screen for
        }
    }

    function readFavs() {
        try {
            var raw = JSON.parse(read(LS.favs, '[]'));
            return Array.isArray(raw) ? raw : [];
        } catch (e) {
            return [];
        }
    }

    function readSettings() {
        var defaults = {ignoreSsl: true, autoLogin: true, showHidden: false, protocol: 'dsm'};
        try {
            var raw = JSON.parse(read(LS.settings, '{}'));
            if (!raw || typeof raw !== 'object') {
                return defaults;
            }
            return {
                ignoreSsl: raw.ignoreSsl !== false,
                autoLogin: raw.autoLogin !== false,
                showHidden: !!raw.showHidden,
                protocol: C.normalizeProtocol(raw.protocol) || 'dsm'
            };
        } catch (e) {
            return defaults;
        }
    }

    function saveSettings() {
        write(LS.settings, JSON.stringify(state.settings));
    }

    var state = {
        side: 'phone',
        view: read(LS.view, 'grid') === 'list' ? 'list' : 'grid',
        sort: read(LS.sort, 'date'),
        filter: read(LS.filter, 'all'),
        query: '',
        selecting: false,
        selected: {},
        phone: {path: '', root: '', rootLabel: '내부 저장소', entries: [], quick: [], parent: '', storage: null},
        nas: {path: '/', entries: [], parent: '/', total: 0},
        local: null,
        nasStatus: {},
        connected: false,
        booted: false,
        loading: false,
        nativeMissing: false,
        favs: readFavs(),
        settings: readSettings(),
        transfers: [],
        transferSeq: 0,
        viewer: {items: [], index: 0, scale: 1, tx: 0, ty: 0, side: 'phone'},
        picker: null,
        dialogCfg: null,
        toastTimer: null,
        pressTimer: null,
        pressEntry: null,
        suppressClick: false,
        listTouchBound: false
    };

    // ---------------------------------------------------------------- helpers

    function isNas() {
        return state.side === 'nas';
    }

    function sideState() {
        return isNas() ? state.nas : state.phone;
    }

    function entries() {
        return sideState().entries || [];
    }

    function currentPath() {
        return isNas() ? (state.nas.path || '/') : (state.phone.path || state.phone.root || '');
    }

    function findEntry(path) {
        var list = entries();
        for (var i = 0; i < list.length; i++) {
            if (list[i] && list[i].path === path) {
                return list[i];
            }
        }
        return null;
    }

    function displayOf(path, side) {
        if (side === 'nas') {
            return path || '/';
        }
        return C.displayPath(path, state.phone.root);
    }

    function toast(message) {
        var el = $('toast');
        $('toastMsg').textContent = message;
        el.classList.add('on');
        if (state.toastTimer) {
            clearTimeout(state.toastTimer);
        }
        state.toastTimer = setTimeout(function () {
            el.classList.remove('on');
        }, 2600);
    }

    function applyIcons(scope) {
        if (I) {
            I.apply(scope || document);
        }
    }

    // ------------------------------------------------------------------ boot

    function start() {
        if (I) {
            I.apply(document);
        }
        bindChrome();
        if (!FM.hasNative) {
            state.nativeMissing = true;
            state.booted = true;
            render();
            return;
        }
        boot();
    }

    function boot() {
        if (state.booted) {
            return;
        }
        state.booted = true;
        state.nativeMissing = false;
        if (I) {
            I.apply(document);
        }
        render();
        FM.local('status').then(function (res) {
            state.local = res;
            if (res.serverBase) {
                window.fmServerBase = res.serverBase;
                window.fmServerToken = res.serverToken || '';
            }
            var roots = res.roots || [];
            state.phone.root = roots.length ? roots[0].path : '/storage/emulated/0';
            state.phone.rootLabel = roots.length ? (roots[0].label || '내부 저장소') : '내부 저장소';
            return refreshNasStatus();
        }).then(function () {
            return maybeAutoLogin();
        }).then(function () {
            return loadPhone(state.phone.path || state.phone.root);
        }).catch(function (error) {
            state.loading = false;
            toast(error.message || '폰 저장소를 열지 못했습니다.');
            render();
        });
    }

    FM.onReady = function () {
        boot();
    };

    FM.onResume = function () {
        if (!state.booted) {
            boot();
            return;
        }
        refreshLocalStatus().then(function () {
            if (isNas()) {
                render();
            } else {
                loadPhone(state.phone.path || state.phone.root);
            }
        });
    };

    FM.onPermissionResult = function () {
        refreshLocalStatus().then(function () {
            render();
        });
    };

    function refreshLocalStatus() {
        return FM.local('status').then(function (res) {
            state.local = res;
            if (res.serverBase) {
                window.fmServerBase = res.serverBase;
                window.fmServerToken = res.serverToken || '';
            }
            if (!state.phone.root && res.roots && res.roots.length) {
                state.phone.root = res.roots[0].path;
                state.phone.rootLabel = res.roots[0].label || '내부 저장소';
            }
            return res;
        }).catch(function () {
            return state.local;
        });
    }

    function refreshNasStatus() {
        return FM.nas('status').then(function (res) {
            state.nasStatus = res || {};
            state.connected = !!res.connected;
            if (res.connected) {
                state.nas.path = state.nas.path || '/';
            }
            return res;
        }).catch(function () {
            state.connected = false;
            return null;
        });
    }

    /** Signs in again with the password in the keystore when 자동 로그인 was left on. */
    function maybeAutoLogin() {
        var info = state.nasStatus || {};
        if (state.connected || !info.hasSavedPassword || !state.settings.autoLogin) {
            return Promise.resolve();
        }
        return FM.nas('autoLogin').then(function (res) {
            if (res && res.connected) {
                state.connected = true;
                state.nasStatus = res;
                render();
            }
        }).catch(function () {
            // a wrong stored password stays quiet until the user opens the NAS tab
        });
    }

    // --------------------------------------------------------------- loading

    function loadPhone(path, opts) {
        var options = opts || {};
        var target = path || state.phone.path || state.phone.root;
        if (!target) {
            return Promise.resolve();
        }
        state.loading = true;
        renderStatus();
        return FM.local('list', {path: target, showHidden: !!state.settings.showHidden})
            .then(function (res) {
                state.loading = false;
                state.phone.path = res.path || target;
                state.phone.entries = res.entries || [];
                state.phone.parent = res.parent || state.phone.path;
                state.phone.quick = res.quick || state.phone.quick;
                state.phone.storage = res.storage || state.phone.storage;
                if (!state.phone.root) {
                    state.phone.root = state.phone.path;
                }
                if (!options.keepSelection) {
                    clearSelection(false);
                }
                render();
                return res;
            })
            .catch(function (error) {
                state.loading = false;
                toast(error.message || '폴더를 읽지 못했습니다.');
                render();
                return null;
            });
    }

    function loadNas(path, opts) {
        var options = opts || {};
        if (!state.connected) {
            render();
            return Promise.resolve(null);
        }
        var target = path || state.nas.path || '/';
        state.loading = true;
        renderStatus();
        return FM.nas('list', {path: target}).then(function (res) {
            state.loading = false;
            state.nas.path = res.path || target;
            state.nas.entries = res.entries || [];
            state.nas.parent = res.parent || '/';
            state.nas.total = res.total || state.nas.entries.length;
            if (!options.keepSelection) {
                clearSelection(false);
            }
            render();
            return res;
        }).catch(function (error) {
            state.loading = false;
            toast(error.message || '시놀로지 폴더를 읽지 못했습니다.');
            render();
            return null;
        });
    }

    function reload(keepSelection) {
        if (isNas()) {
            return loadNas(state.nas.path, {keepSelection: !!keepSelection});
        }
        return loadPhone(state.phone.path || state.phone.root, {keepSelection: !!keepSelection});
    }

    function navigate(path) {
        var target = C.normalizePath(path);
        if (isNas()) {
            if (!state.connected) {
                toast('시놀로지에 먼저 연결해 주세요.');
                render();
                return;
            }
            state.query = '';
            loadNas(target);
            return;
        }
        if (!target || !state.phone.root) {
            return;
        }
        if (target.length < C.normalizePath(state.phone.root).length) {
            target = state.phone.root;
        }
        state.query = '';
        loadPhone(target);
    }

    function goUp() {
        if (isNas()) {
            if (C.normalizePath(state.nas.path) === '/') {
                return;
            }
            navigate(C.parentOf(state.nas.path));
            return;
        }
        var path = C.normalizePath(state.phone.path);
        var root = C.normalizePath(state.phone.root || path);
        if (path === root) {
            return;
        }
        var parent = C.normalizePath(C.parentOf(path));
        if (parent.length < root.length) {
            parent = root;
        }
        navigate(parent);
    }

    function switchSide(side) {
        if (state.side === side) {
            return;
        }
        state.side = side;
        state.query = '';
        state.filter = 'all';
        write(LS.filter, state.filter);
        $('searchInput').value = '';
        clearSelection(false);
        if (side === 'nas') {
            if (state.connected && !state.nas.entries.length) {
                loadNas(state.nas.path || '/');
                return;
            }
            render();
            return;
        }
        if (!state.phone.entries.length && state.phone.root) {
            loadPhone(state.phone.path || state.phone.root);
            return;
        }
        render();
    }

    // ---------------------------------------------------------------- render

    function render() {
        renderHeader();
        renderTabs();
        renderToolbar();
        renderFavbar();
        renderList();
        renderSelection();
        renderMini();
        renderStatus();
    }

    function renderHeader() {
        var badge = $('connBadge');
        var label = $('connLabel');
        var subtitle = $('serverSubtitle');
        var info = state.nasStatus || {};

        if (state.connected) {
            badge.classList.add('on');
            var name = info.serverName || '';
            label.textContent = name ? (name + ' 연결됨') : '연결됨';
            subtitle.textContent = (isWebDav() ? 'WebDAV · ' : 'DSM · ')
                + (info.baseUrl || '') + (info.account ? ' · ' + info.account : '');
        } else {
            badge.classList.remove('on');
            label.textContent = '연결 안 됨';
            subtitle.textContent = info.lastUrl
                ? (info.lastUrl + ' · 로그인 필요')
                : '서버를 연결해 주세요';
        }

        var running = C.transferSummary(state.transfers).running;
        var transferBadge = $('transferBadge');
        if (running > 0) {
            transferBadge.textContent = String(running);
            transferBadge.classList.remove('hide');
        } else {
            transferBadge.classList.add('hide');
        }

        $('nasCountPill').textContent = String(C.fileCount(state.nas.entries));
        $('phoneCountPill').textContent = String(C.fileCount(state.phone.entries));
    }

    function renderTabs() {
        var nasTab = $('tab-nas');
        var phoneTab = $('tab-phone');
        nasTab.classList.toggle('on', isNas());
        phoneTab.classList.toggle('on', !isNas());
    }

    function renderToolbar() {
        $('sortLabel').textContent = C.sortLabel(state.sort);
        var viewBtn = $('viewToggle');
        viewBtn.innerHTML = ic(state.view === 'grid' ? 'list' : 'layout-grid');
        var selectBtn = $('selectModeBtn');
        selectBtn.classList.toggle('on', state.selecting);

        var favBtn = $('btnFav');
        favBtn.classList.toggle('on', isFavourite());
        favBtn.innerHTML = ic('star');

        var filterChip = $('filterChip');
        if (state.filter !== 'all') {
            filterChip.textContent = C.filterLabel(state.filter);
            filterChip.classList.remove('hide');
        } else {
            filterChip.classList.add('hide');
        }

        var up = $('btnUp');
        if (isNas()) {
            up.disabled = C.normalizePath(state.nas.path) === '/';
        } else {
            up.disabled = !state.phone.path
                || C.normalizePath(state.phone.path) === C.normalizePath(state.phone.root || state.phone.path);
        }

        renderCrumbs();
    }

    function renderCrumbs() {
        var container = $('crumbs');
        var crumbs;
        if (isNas()) {
            crumbs = C.breadcrumbs(state.nas.path || '/', 'NAS', '/');
        } else {
            crumbs = C.breadcrumbs(state.phone.path || state.phone.root,
                state.phone.rootLabel || '내부 저장소', state.phone.root);
        }
        var html = '';
        for (var i = 0; i < crumbs.length; i++) {
            var last = i === crumbs.length - 1;
            var crumb = crumbs[i];
            html += '<button class="crumb' + (last ? ' last' : '') + '" data-nav="'
                + esc(crumb.path) + '">' + (i > 0 ? '<span class="sep">/</span>' : '')
                + esc(crumb.label) + '</button>';
        }
        container.innerHTML = html;
        container.onclick = function (event) {
            var target = closestAttr(event.target, 'data-nav');
            if (target) {
                navigate(target.getAttribute('data-nav'));
            }
        };
    }

    function renderFavbar() {
        var bar = $('favbar');
        var chips = [];
        var rootPath = isNas() ? '/' : (state.phone.root || '');
        chips.push({label: '홈', path: rootPath, icon: 'home', root: true});

        if (!isNas()) {
            each(state.phone.quick, function (item) {
                chips.push({label: item.label, path: item.path, icon: 'folder'});
            });
        }
        each(state.favs, function (fav) {
            if (fav && fav.side === state.side) {
                chips.push({label: fav.label || C.nameOf(fav.path), path: fav.path, icon: 'star'});
            }
        });

        var here = C.normalizePath(currentPath());
        var html = '';
        each(chips, function (chip) {
            var on = C.normalizePath(chip.path) === here;
            html += '<button class="favchip' + (on ? ' on' : '') + '" data-nav="' + esc(chip.path) + '">'
                + ic(chip.icon) + '<span>' + esc(chip.label) + '</span></button>';
        });
        bar.innerHTML = html;
        bar.onclick = function (event) {
            var target = closestAttr(event.target, 'data-nav');
            if (target) {
                navigate(target.getAttribute('data-nav'));
            }
        };
    }

    function renderStatus() {
        var line = $('countText');
        if (!line) {
            return;
        }
        if (state.nativeMissing) {
            line.textContent = '앱에서 열어 주세요';
            return;
        }
        if (state.loading) {
            line.textContent = '불러오는 중...';
            return;
        }
        if (isNas() && !state.connected) {
            line.textContent = '시놀로지를 연결해 주세요';
            return;
        }
        var parts = [];
        var summary = C.summary(entries());
        parts.push(summary || '항목 없음');
        if (state.selecting) {
            parts.push(selectionCount() + '개 선택');
        }
        if (state.query) {
            parts.push('"' + state.query + '" 검색');
        }
        line.textContent = parts.join(' · ');
    }

    function renderList() {
        var container = $('fileContainer');
        container.className = 'files' + (state.selecting ? ' selecting' : '');
        var list = entries();
        var visible = C.visibleEntries(list, state.filter, state.query, state.sort);
        var html = '';

        if (state.nativeMissing) {
            html += noticeCard('smartphone', '앱에서 열어 주세요',
                '이 화면은 SynoFile Pro 앱 안에서만 폰 저장소와 시놀로지에 접근할 수 있습니다.');
            container.innerHTML = html;
            setEmpty(false);
            applyIcons(container);
            return;
        }

        if (isNas() && !state.connected) {
            html += connectCard();
            container.innerHTML = html;
            setEmpty(false);
            applyIcons(container);
            bindListEvents();
            return;
        }

        if (!isNas() && state.local && state.local.allFiles === false && state.phone.path) {
            html += '<div class="permcard"><div class="top">' + ic('alert-triangle')
                + '<p>Android 11부터는 ‘모든 파일 접근’을 허용해야 폰의 모든 폴더를 읽고 쓸 수 있습니다.<br>'
                + '허용하지 않으면 DCIM·Download 같은 일부 폴더만 보입니다.</p></div>'
                + '<button class="ghostbtn" data-act="permission">' + ic('lock') + '<span>모든 파일 접근 허용</span></button></div>';
        }

        var folders = [];
        var media = [];
        each(visible, function (entry) {
            if (entry.isDir) {
                folders.push(entry);
            } else {
                media.push(entry);
            }
        });

        if (folders.length) {
            html += '<div class="section-title">폴더 ' + folders.length + '</div><div class="folderlist">';
            each(folders, function (entry) {
                html += folderRowHtml(entry);
            });
            html += '</div>';
        }

        if (media.length) {
            html += '<div class="section-title">' + (isNas() ? '시놀로지 파일' : '사진 · 영상 · 파일')
                + ' ' + media.length + '</div>';
            if (state.view === 'grid') {
                html += '<div class="mediaGrid">';
                each(media, function (entry) {
                    html += tileHtml(entry);
                });
                html += '</div>';
            } else {
                html += '<div class="folderlist">';
                each(media, function (entry) {
                    html += rowItemHtml(entry);
                });
                html += '</div>';
            }
        }

        container.innerHTML = html;
        applyIcons(container);
        bindListEvents();

        var showEmpty = visible.length === 0;
        setEmpty(showEmpty);
        if (showEmpty) {
            var title = $('emptyTitle');
            var hint = $('emptyHint');
            var action = $('emptyAction');
            action.classList.add('hide');
            action.onclick = null;
            if (state.query) {
                title.textContent = '조건에 맞는 파일이 없습니다';
                hint.textContent = '검색어를 지우거나 필터를 ‘전체’로 바꿔 보세요.';
            } else if (state.filter !== 'all') {
                title.textContent = '이 종류의 파일이 없습니다';
                hint.textContent = '필터를 ‘전체’로 바꾸면 모든 항목이 보입니다.';
            } else if (isNas() && C.normalizePath(state.nas.path) === '/') {
                title.textContent = '공유 폴더가 없습니다';
                hint.textContent = 'DSM에서 공유 폴더를 만들면 여기에 나타납니다.';
            } else {
                title.textContent = '폴더가 비어 있습니다';
                hint.textContent = '사진이나 영상을 올리면 여기에 보입니다.';
            }
        }
    }

    function setEmpty(on) {
        var empty = $('emptyState');
        empty.classList.toggle('on', !!on);
        // An empty folder hands the whole body to the empty state instead of leaving a
        // half-height blank list above it.
        $('fileContainer').classList.toggle('blank', !!on);
    }

    function noticeCard(icon, title, text) {
        return '<div class="noticecard"><div class="big">' + ic(icon) + '</div>'
            + '<h3>' + esc(title) + '</h3><p>' + esc(text) + '</p></div>';
    }

    function connectCard() {
        var info = state.nasStatus || {};
        var servers = info.servers || [];
        var html = noticeCard('cloud', '서버를 연결해 주세요',
            'DSM File Station 또는 WebDAV(포트 5005·5006)로 로그인하면 NAS의 폴더를 그대로 '
            + '탐색하고, 폰 사진을 바로 올리거나 내려받을 수 있습니다.');
        html += '<div class="savedcards" style="align-items:center;">'
            + '<button class="primarybtn" data-act="openServer">' + ic('server')
            + '<span>서버 설정 열기</span></button></div>';

        if (servers.length) {
            html += '<div class="savedcards">';
            each(servers, function (server) {
                var proto = C.normalizeProtocol(server.protocol)
                    || C.splitServerUrl(server.url || '').protocol;
                html += '<button class="savedcard" data-act="useServer" data-url="' + esc(server.url)
                    + '" data-account="' + esc(server.account || '')
                    + '" data-protocol="' + esc(proto) + '">'
                    + '<span style="color:#60A5FA;">' + ic('server') + '</span>'
                    + '<span class="grow"><span class="u">' + esc(server.url) + '</span>'
                    + '<span class="a">' + esc(server.account || '계정 없음') + ' · '
                    + esc(C.protocolLabel(proto)) + '</span></span>'
                    + '<span class="go">로그인</span></button>';
            });
            html += '</div>';
        }
        return html;
    }

    function folderRowHtml(entry) {
        var selected = isSelected(entry.path);
        return '<div class="folderrow' + (selected ? ' sel' : '') + '" data-path="' + esc(entry.path)
            + '" data-kind="folder">'
            + '<span class="mark' + (selected ? ' on' : '') + '">' + (selected ? ic('check') : '') + '</span>'
            + '<div class="folderic">' + ic('folder') + '</div>'
            + '<div class="foldertext"><p class="name">' + esc(entry.name) + '</p>'
            + '<p class="meta">폴더' + (entry.mtime ? ' · ' + esc(C.formatDay(entry.mtime)) : '') + '</p></div>'
            + '<button class="rowbtn" data-act="menu" data-path="' + esc(entry.path) + '">'
            + ic('more-vertical') + '</button></div>';
    }

    function thumbHtml(entry, big) {
        var isVideo = C.isVideo(entry.name);
        var isImage = C.isImage(entry.name);
        // WebDAV has no thumbnail endpoint and a frame cannot be cut from a stream, so a
        // WebDAV video tile shows its kind icon instead of an empty frame.
        var canThumb = !(isNas() && isWebDav() && isVideo);
        var src = canThumb
            ? FM.mediaUrl(entry.path, isNas(), true, isNas() ? 'medium' : (big ? 320 : 160))
            : '';
        var html = '';
        if ((isImage || isVideo) && src) {
            html += '<img src="' + esc(src) + '" alt="" loading="lazy" '
                + 'onerror="this.style.display=\'none\'">';
        }
        if (!isImage && !isVideo) {
            html += '<span class="ph">' + ic(isVideo ? 'video' : (C.isAudio(entry.name) ? 'music' : 'file')) + '</span>';
        } else if (!src) {
            html += '<span class="ph">' + ic(isVideo ? 'video' : 'image') + '</span>';
        }
        return html;
    }

    function tileHtml(entry) {
        var selected = isSelected(entry.path);
        var isVideo = C.isVideo(entry.name);
        var meta = C.formatSize(entry.size) + ' · ' + C.kindLabel(entry.name);
        return '<div class="tile' + (selected ? ' sel' : '') + '" data-path="' + esc(entry.path)
            + '" data-kind="' + esc(C.kindOf(entry.name)) + '">'
            + '<div class="thumb">' + thumbHtml(entry, true)
            + '<div class="shade"></div>'
            + '<span class="badge">' + esc(C.badgeOf(entry.name)) + '</span>'
            + (isVideo ? '<span class="playbtn">' + ic('play') + '</span>' : '')
            + '<span class="mark' + (selected ? ' on' : '') + '">' + (selected ? ic('check') : '') + '</span>'
            + '</div>'
            + '<div class="tileinfo"><div class="text"><p class="name">' + esc(entry.name) + '</p>'
            + '<p class="meta">' + esc(meta) + '</p></div>'
            + '<button class="quicktrans" data-act="transfer" data-path="' + esc(entry.path) + '">'
            + ic(isNas() ? 'download' : 'upload') + '</button></div></div>';
    }

    function rowItemHtml(entry) {
        var selected = isSelected(entry.path);
        var isVideo = C.isVideo(entry.name);
        var meta = C.formatSize(entry.size) + ' · ' + esc(C.formatDay(entry.mtime));
        return '<div class="rowitem' + (selected ? ' sel' : '') + '" data-path="' + esc(entry.path)
            + '" data-kind="' + esc(C.kindOf(entry.name)) + '">'
            + '<span class="mark' + (selected ? ' on' : '') + '">' + (selected ? ic('check') : '') + '</span>'
            + '<div class="rowthumb">' + thumbHtml(entry, false)
            + (isVideo ? '<span class="vbadge">' + ic('play') + '</span>' : '') + '</div>'
            + '<div class="rowtext"><p class="name">' + esc(entry.name) + '</p>'
            + '<p class="meta">' + esc(meta) + '</p></div>'
            + '<button class="rowbtn" data-act="transfer" data-path="' + esc(entry.path) + '">'
            + ic(isNas() ? 'download' : 'upload') + '</button></div>';
    }

    function bindListEvents() {
        var container = $('fileContainer');
        container.onclick = function (event) {
            var actEl = closestAttr(event.target, 'data-act');
            if (actEl) {
                handleAction(actEl.getAttribute('data-act'), actEl);
                return;
            }
            var rowEl = closestAttr(event.target, 'data-path');
            if (!rowEl) {
                return;
            }
            if (state.suppressClick) {
                state.suppressClick = false;
                return;
            }
            handleOpen(rowEl.getAttribute('data-path'));
        };

        // Long press enters multi-select, the way a gallery does.
        if (state.listTouchBound) {
            return;
        }
        state.listTouchBound = true;
        container.addEventListener('touchstart', function (event) {
            var rowEl = closestAttr(event.target, 'data-path');
            if (!rowEl || event.touches.length !== 1) {
                return;
            }
            var path = rowEl.getAttribute('data-path');
            if (state.pressTimer) {
                clearTimeout(state.pressTimer);
            }
            state.pressTimer = setTimeout(function () {
                state.pressTimer = null;
                state.suppressClick = true;
                var entry = findEntry(path);
                if (entry && !state.selecting) {
                    state.selecting = true;
                    state.selected[entry.path] = entry;
                    renderSelection();
                    renderList();
                }
            }, 450);
        }, {passive: true});

        var cancelPress = function () {
            if (state.pressTimer) {
                clearTimeout(state.pressTimer);
                state.pressTimer = null;
            }
        };
        container.addEventListener('touchend', cancelPress, {passive: true});
        container.addEventListener('touchcancel', cancelPress, {passive: true});
        container.addEventListener('touchmove', cancelPress, {passive: true});
    }

    function handleAction(act, node) {
        var path = node.getAttribute('data-path') || '';
        switch (act) {
            case 'permission':
                FM.allowAllFiles();
                break;
            case 'openServer':
                openServerModal();
                break;
            case 'useServer':
                openServerModal({
                    url: node.getAttribute('data-url') || '',
                    account: node.getAttribute('data-account') || '',
                    protocol: node.getAttribute('data-protocol') || ''
                });
                break;
            case 'menu': {
                var entry = findEntry(path);
                if (entry) {
                    openItemSheet(entry);
                }
                break;
            }
            case 'transfer': {
                var item = findEntry(path);
                if (item) {
                    quickTransfer(item);
                }
                break;
            }
            default:
                break;
        }
    }

    function handleOpen(path) {
        var entry = findEntry(path);
        if (!entry) {
            return;
        }
        if (state.selecting) {
            toggleSelect(entry);
            return;
        }
        if (entry.isDir) {
            navigate(entry.path);
            return;
        }
        if (C.isImage(entry.name) || C.isVideo(entry.name)) {
            openViewer(entry);
        } else {
            openExternal(entry);
        }
    }

    // ------------------------------------------------------------- selection

    function selectionList() {
        var out = [];
        for (var key in state.selected) {
            if (Object.prototype.hasOwnProperty.call(state.selected, key)) {
                out.push(state.selected[key]);
            }
        }
        return out;
    }

    function selectionCount() {
        return selectionList().length;
    }

    function isSelected(path) {
        return !!state.selected[path];
    }

    function toggleSelect(entry) {
        if (!entry) {
            return;
        }
        if (state.selected[entry.path]) {
            delete state.selected[entry.path];
        } else {
            state.selected[entry.path] = entry;
            state.selecting = true;
        }
        renderSelection();
        renderList();
    }

    function clearSelection(rerender) {
        state.selected = {};
        state.selecting = false;
        if (rerender !== false) {
            renderSelection();
            renderList();
        }
    }

    function selectAll() {
        var visible = C.visibleEntries(entries(), state.filter, state.query, state.sort);
        each(visible, function (entry) {
            state.selected[entry.path] = entry;
        });
        state.selecting = selectionCount() > 0;
        renderSelection();
        renderList();
    }

    function renderSelection() {
        var bar = $('selectionBar');
        var count = selectionCount();
        if (!state.selecting) {
            bar.classList.add('hide');
            $('selectModeBtn').classList.remove('on');
            return;
        }
        bar.classList.remove('hide');
        $('selectModeBtn').classList.add('on');
        $('selCount').textContent = count + '개 선택됨';

        var primaryLabel = $('primaryTransferLabel');
        var primaryIcon = $('primaryTransferIcon');
        if (isNas()) {
            primaryLabel.textContent = '내 폰으로 다운로드';
            primaryIcon.innerHTML = ic('download');
        } else {
            primaryLabel.textContent = '시놀로지로 업로드';
            primaryIcon.innerHTML = ic('upload');
        }

        // With nothing picked the bar stays up (that is where 전체선택 lives) but the
        // actions that need a selection are disabled.
        var single = count === 1;
        $('btnPrimaryTransfer').disabled = count === 0;
        $('btnMove').disabled = count === 0;
        $('btnCopy').disabled = count === 0;
        $('btnDelete').disabled = count === 0;
        $('btnRename').disabled = !single;
        $('btnInfo').disabled = !single;
        var shareBtn = $('btnShare');
        shareBtn.disabled = isNas() ? !single : C.fileCount(selectionList()) === 0;
        $('btnShareLabel').textContent = isNas() ? '열기' : '공유';
    }

    // --------------------------------------------------------------- device

    function nearestDir(side) {
        if (side === 'nas') {
            return state.nas.path && state.nas.path !== '/' ? state.nas.path : '';
        }
        return state.phone.path || state.phone.root || '';
    }

    function quickTransfer(entry) {
        if (entry.isDir) {
            toast('폴더 전송은 지원하지 않습니다. 파일을 선택해 주세요.');
            return;
        }
        if (isNas()) {
            downloadEntries([entry], nearestDir('phone'));
            return;
        }
        uploadEntries([entry], nearestDir('nas'));
    }

    function applyPrimaryTransfer() {
        var picked = selectionList();
        if (!picked.length) {
            return;
        }
        if (isNas()) {
            downloadEntries(picked, nearestDir('phone'));
        } else {
            uploadEntries(picked, nearestDir('nas'));
        }
    }

    // ------------------------------------------------------------- transfers

    function startTransfer(name, direction, size) {
        var t = {
            id: 'tr' + (++state.transferSeq),
            name: name,
            direction: direction,
            size: Number(size) || 0,
            percent: 0,
            status: 'running',
            note: ''
        };
        state.transfers.unshift(t);
        if (state.transfers.length > 40) {
            state.transfers.length = 40;
        }
        renderTransfers();
        renderHeader();
        renderMini();
        return t;
    }

    function updateTransfer(t, percent, note) {
        if (!t || t.status !== 'running') {
            return;
        }
        var pct = Number(percent);
        if (isFinite(pct) && pct >= 0) {
            t.percent = C.clampPercent(pct);
        }
        if (note) {
            t.note = note;
        }
        renderTransfers();
        renderMini();
    }

    function endTransfer(t, ok, note) {
        if (!t) {
            return;
        }
        t.status = ok ? 'done' : 'failed';
        if (ok) {
            t.percent = 100;
        }
        if (note) {
            t.note = note;
        }
        renderTransfers();
        renderHeader();
        renderMini();
    }

    function renderTransfers() {
        var list = $('transferList');
        if (!list) {
            return;
        }
        var summary = C.transferLine(state.transfers);
        $('transferSummary').textContent = summary;
        if (!state.transfers.length) {
            list.innerHTML = '<p style="font-size:11.5px;color:#64748B;text-align:center;padding:26px 0;">'
                + '진행 중이거나 완료된 전송 내역이 없습니다.</p>';
            return;
        }
        var html = '';
        each(state.transfers, function (t) {
            var cls = t.status === 'done' ? 'done' : (t.status === 'failed' ? 'fail' : 'run');
            var label = t.status === 'done' ? '완료' : (t.status === 'failed' ? '실패' : t.percent + '%');
            var detail = t.direction + (t.size ? ' · ' + C.formatSize(t.size) : '')
                + (t.note ? ' · ' + esc(t.note) : '');
            html += '<div class="transferitem"><div class="top">'
                + '<div class="grow"><p class="n">' + esc(t.name) + '</p>'
                + '<p class="d">' + detail + '</p></div>'
                + '<span class="st ' + cls + '">' + esc(label) + '</span></div>'
                + '<div class="track"><div class="bar ' + (t.status === 'done' ? 'done' : (t.status === 'failed' ? 'fail' : ''))
                + '" style="width:' + C.clampPercent(t.percent) + '%"></div></div></div>';
        });
        list.innerHTML = html;
    }

    function renderMini() {
        var box = $('miniToast');
        if (!box) {
            return;
        }
        var running = null;
        each(state.transfers, function (t) {
            if (!running && t.status === 'running') {
                running = t;
            }
        });
        if (!running) {
            box.classList.add('hide');
            return;
        }
        box.classList.remove('hide');
        $('miniTitle').textContent = running.name;
        $('miniSub').textContent = running.direction + (running.note ? ' · ' + running.note : '');
        $('miniPercent').textContent = running.percent + '%';
    }

    function uploadEntries(picked, destDir) {
        var files = [];
        each(picked, function (entry) {
            if (entry && !entry.isDir) {
                files.push(entry);
            }
        });
        if (!files.length) {
            toast('업로드할 파일을 선택해 주세요. 폴더는 아직 올릴 수 없습니다.');
            return;
        }
        if (!state.connected) {
            toast('시놀로지에 먼저 연결해 주세요.');
            openServerModal();
            return;
        }
        var go = function (dest) {
            if (!dest) {
                return;
            }
            var total = 0;
            each(files, function (entry) {
                total += Number(entry.size) || 0;
            });
            var name = files.length === 1 ? files[0].name : files.length + '개 파일';
            var t = startTransfer(name, '내 폰 → 시놀로지 NAS', total);
            var paths = [];
            each(files, function (entry) {
                paths.push(entry.path);
            });
            FM.nas('upload', {
                localPaths: paths,
                destDir: dest,
                overwrite: false
            }, function (percent, message) {
                updateTransfer(t, percent, message);
            }).then(function (res) {
                var done = res && res.count ? res.count : files.length;
                endTransfer(t, true, done + '개 완료');
                toast(done + '개를 ' + dest + '에 올렸습니다.');
                clearSelection(false);
                if (isNas() && C.normalizePath(state.nas.path) === C.normalizePath(dest)) {
                    loadNas(dest);
                } else {
                    render();
                }
            }).catch(function (error) {
                var data = error.data || {};
                var failedCount = (data.failed || []).length;
                var note = '';
                if (data.count !== undefined) {
                    note = data.count + '개 완료' + (failedCount ? ', ' + failedCount + '개 실패' : '');
                }
                endTransfer(t, false, note || error.message);
                toast(note || error.message || '업로드에 실패했습니다.');
                render();
            });
        };

        if (!destDir) {
            pickFolder('nas', state.nas.path || '/', '올릴 시놀로지 폴더 선택').then(go);
            return;
        }
        go(destDir);
    }

    function downloadEntries(picked, destDir) {
        var files = [];
        each(picked, function (entry) {
            if (entry && !entry.isDir) {
                files.push(entry);
            }
        });
        if (!files.length) {
            toast('다운로드할 파일을 선택해 주세요.');
            return;
        }
        if (!state.connected) {
            toast('시놀로지에 먼저 연결해 주세요.');
            openServerModal();
            return;
        }
        var go = function (dest) {
            if (!dest) {
                return;
            }
            var index = 0;
            var saved = 0;
            var failed = 0;

            function next() {
                if (index >= files.length) {
                    toast(saved + '개를 ' + dest + '에 저장했습니다.' + (failed ? ' (' + failed + '개 실패)' : ''));
                    clearSelection(false);
                    if (!isNas() && C.normalizePath(state.phone.path) === C.normalizePath(dest)) {
                        loadPhone(dest);
                    } else {
                        render();
                    }
                    return;
                }
                var entry = files[index++];
                var t = startTransfer(entry.name, '시놀로지 NAS → 내 폰', entry.size);
                FM.nas('download', {path: entry.path, destDir: dest}, function (percent, message) {
                    updateTransfer(t, percent, message);
                }).then(function (res) {
                    saved++;
                    endTransfer(t, true, res && res.name ? res.name : '');
                    next();
                }).catch(function (error) {
                    failed++;
                    endTransfer(t, false, error.message);
                    next();
                });
            }

            next();
        };

        if (!destDir) {
            pickFolder('phone', state.phone.path || state.phone.root
                || '/storage/emulated/0', '저장할 폰 폴더 선택').then(go);
            return;
        }
        go(destDir);
    }

    // ------------------------------------------------------------- file ops

    function newFolder() {
        if (isNas() && !state.connected) {
            toast('시놀로지에 먼저 연결해 주세요.');
            openServerModal();
            return;
        }
        var base = currentPath();
        promptDialog('새 폴더', displayOf(base, state.side) + ' 안에 만들 폴더 이름', '새 폴더', '만들기')
            .then(function (value) {
                if (value === null) {
                    return;
                }
                var name = C.sanitizeName(value);
                if (!name) {
                    return;
                }
                var target = C.joinPath(base, name);
                var request = isNas() ? FM.nas('mkdir', {path: target}) : FM.local('mkdir', {path: target});
                request.then(function () {
                    toast('폴더를 만들었습니다.');
                    reload(false);
                }).catch(function (error) {
                    toast(error.message || '폴더를 만들지 못했습니다.');
                });
            });
    }

    function renameEntry(entry) {
        if (!entry) {
            return;
        }
        promptDialog('이름 변경', '새 이름을 입력하세요.', entry.name, '변경').then(function (value) {
            if (value === null) {
                return;
            }
            var name = C.sanitizeName(value);
            if (!name || name === entry.name) {
                return;
            }
            var request = isNas()
                ? FM.nas('rename', {path: entry.path, newName: name})
                : FM.local('rename', {path: entry.path, newName: name});
            request.then(function () {
                toast('이름을 바꿨습니다.');
                clearSelection(false);
                reload(false);
            }).catch(function (error) {
                toast(error.message || '이름을 바꾸지 못했습니다.');
            });
        });
    }

    function deleteEntries(picked) {
        var list = [];
        each(picked, function (entry) {
            if (entry) {
                list.push(entry);
            }
        });
        if (!list.length) {
            return;
        }
        var names = [];
        each(list.slice(0, 3), function (entry) {
            names.push(entry.name);
        });
        var label = names.join(', ') + (list.length > 3 ? ' 외 ' + (list.length - 3) + '개' : '');
        confirmDialog('삭제할까요?', label + '\n\n삭제한 파일은 휴지통 없이 바로 지워집니다.',
            '삭제', true).then(function (yes) {
            if (!yes) {
                return;
            }
            var paths = [];
            each(list, function (entry) {
                paths.push(entry.path);
            });
            var request = isNas() ? FM.nas('delete', {paths: paths}) : FM.local('delete', {paths: paths});
            request.then(function () {
                toast(list.length + '개를 삭제했습니다.');
                each(list, function (entry) {
                    delete state.selected[entry.path];
                    removeFavourite(entry.path, state.side);
                });
                clearSelection(false);
                reload(false);
            }).catch(function (error) {
                toast(error.message || '삭제하지 못했습니다.');
            });
        });
    }

    function copyMoveEntries(picked, move) {
        var list = [];
        each(picked, function (entry) {
            if (entry) {
                list.push(entry);
            }
        });
        if (!list.length) {
            return;
        }
        if (isNas() && !state.connected) {
            toast('시놀로지에 먼저 연결해 주세요.');
            return;
        }
        var label = move ? '이동' : '복사';
        pickFolder(state.side, currentPath(), label + '할 위치 선택').then(function (dest) {
            if (!dest) {
                return;
            }
            var paths = [];
            each(list, function (entry) {
                paths.push(entry.path);
            });
            if (isNas()) {
                FM.nas('copyMove', {paths: paths, destDir: dest, move: move, overwrite: false},
                    function (percent, message) {
                        if (message) {
                            toast(message);
                        }
                    }).then(function () {
                    toast(label + ' 완료: ' + dest);
                    clearSelection(false);
                    loadNas(dest);
                }).catch(function (error) {
                    toast(error.message || label + '하지 못했습니다.');
                });
                return;
            }
            FM.local('copyMove', {paths: paths, destDir: dest, move: move, overwrite: false})
                .then(function (res) {
                    var failed = (res && res.failed) ? res.failed.length : 0;
                    var count = (res && res.count) || list.length;
                    toast(failed ? (count + '개 완료, ' + failed + '개 실패') : (count + '개 ' + label + ' 완료'));
                    clearSelection(false);
                    loadPhone(dest);
                }).catch(function (error) {
                    toast(error.message || label + '하지 못했습니다.');
                });
        });
    }

    function shareOrOpen(picked) {
        var list = [];
        each(picked, function (entry) {
            if (entry) {
                list.push(entry);
            }
        });
        if (!list.length) {
            return;
        }
        if (isNas()) {
            if (list.length !== 1) {
                toast('시놀로지 파일은 한 번에 하나씩 열 수 있습니다.');
                return;
            }
            openExternal(list[0]);
            return;
        }
        var paths = [];
        each(list, function (entry) {
            if (!entry.isDir) {
                paths.push(entry.path);
            }
        });
        if (!paths.length) {
            toast('공유할 파일을 선택해 주세요.');
            return;
        }
        FM.local('share', {paths: paths}).catch(function (error) {
            toast(error.message || '공유하지 못했습니다.');
        });
    }

    function openExternal(entry) {
        if (isNas()) {
            if (!state.connected) {
                toast('시놀로지에 먼저 연결해 주세요.');
                return;
            }
            toast('파일을 내려받아 외부 앱으로 엽니다...');
            FM.nas('openExternal', {path: entry.path}).catch(function (error) {
                toast(error.message || '파일을 열지 못했습니다.');
            });
            return;
        }
        FM.local('open', {path: entry.path}).catch(function (error) {
            toast(error.message || '파일을 열지 못했습니다.');
        });
    }

    function infoDialog(entry) {
        if (!entry) {
            return;
        }
        var rows = [
            ['이름', entry.name],
            ['위치', C.parentOf(entry.path)],
            ['종류', entry.isDir ? '폴더' : C.kindLabel(entry.name)],
            ['크기', entry.isDir ? '-' : C.formatSize(entry.size)],
            ['수정한 날짜', C.formatDay(entry.mtime)],
            ['저장 위치', isNas() ? '시놀로지 NAS' : '내 폰']
        ];
        var html = '';
        each(rows, function (row) {
            html += '<div class="inforow"><span>' + esc(row[0]) + '</span><b>'
                + esc(row[1] || '-') + '</b></div>';
        });
        showDialog({
            title: entry.name,
            mode: 'info',
            html: html,
            okText: '닫기',
            hideCancel: true
        });
    }

    function openItemSheet(entry) {
        var actions = [];
        if (entry.isDir) {
            actions.push({act: 'open', icon: 'folder-open', label: '열기'});
        } else {
            actions.push({act: 'open', icon: 'eye', label: '보기'});
        }
        if (isNas()) {
            actions.push({act: 'download', icon: 'download', label: '내 폰으로 다운로드'});
        } else {
            actions.push({act: 'upload', icon: 'upload', label: '시놀로지로 업로드'});
        }
        actions.push({act: 'rename', icon: 'edit-3', label: '이름 변경'});
        actions.push({act: 'copy', icon: 'copy', label: '복사'});
        actions.push({act: 'move', icon: 'folder-input', label: '이동'});
        if (!isNas() && !entry.isDir) {
            actions.push({act: 'share', icon: 'share-2', label: '공유'});
        }
        if (isNas() && !entry.isDir) {
            actions.push({act: 'external', icon: 'external-link', label: '외부 앱으로 열기'});
        }
        actions.push({act: 'info', icon: 'info', label: '정보'});
        actions.push({act: 'delete', icon: 'trash-2', label: '삭제', danger: true});

        var html = '';
        each(actions, function (item) {
            html += '<button class="menuitem" data-act="' + item.act + '">'
                + ic(item.icon) + '<span class="grow"' + (item.danger ? ' style="color:#FB7185;"' : '')
                + '>' + esc(item.label) + '</span></button>';
        });

        showDialog({
            title: entry.name,
            desc: (entry.isDir ? '폴더' : C.kindLabel(entry.name))
                + (entry.isDir ? '' : ' · ' + C.formatSize(entry.size)),
            mode: 'actions',
            html: html,
            hideOk: true,
            cancelText: '닫기',
            onAct: function (act) {
                switch (act) {
                    case 'open':
                        if (entry.isDir) {
                            navigate(entry.path);
                        } else {
                            openViewer(entry);
                        }
                        break;
                    case 'download':
                    case 'upload':
                        quickTransfer(entry);
                        break;
                    case 'rename':
                        renameEntry(entry);
                        break;
                    case 'copy':
                        copyMoveEntries([entry], false);
                        break;
                    case 'move':
                        copyMoveEntries([entry], true);
                        break;
                    case 'share':
                        shareOrOpen([entry]);
                        break;
                    case 'external':
                        openExternal(entry);
                        break;
                    case 'info':
                        infoDialog(entry);
                        break;
                    case 'delete':
                        deleteEntries([entry]);
                        break;
                    default:
                        break;
                }
            }
        });
    }

    // ---------------------------------------------------------------- dialog

    function showDialog(cfg) {
        state.dialogCfg = cfg;
        $('dlgTitle').textContent = cfg.title || '';
        var desc = $('dlgDesc');
        if (cfg.desc) {
            desc.textContent = cfg.desc;
            desc.classList.remove('hide');
        } else {
            desc.textContent = '';
            desc.classList.add('hide');
        }

        $('dlgInputWrap').classList.add('hide');
        $('dlgSelectWrap').classList.add('hide');
        $('dlgBody').classList.add('hide');
        $('dlgBody').innerHTML = '';

        if (cfg.mode === 'input') {
            $('dlgInputWrap').classList.remove('hide');
            $('dlgInput').value = cfg.value || '';
        } else if (cfg.mode === 'select') {
            $('dlgSelectWrap').classList.remove('hide');
            var options = '';
            each(cfg.options || [], function (option) {
                options += '<option value="' + esc(option.value) + '">' + esc(option.label) + '</option>';
            });
            $('dlgSelect').innerHTML = options;
        } else if (cfg.mode === 'info' || cfg.mode === 'actions') {
            $('dlgBody').classList.remove('hide');
            $('dlgBody').innerHTML = cfg.html || '';
            applyIcons($('dlgBody'));
        }

        var ok = $('dlgOk');
        ok.textContent = cfg.okText || '확인';
        ok.classList.toggle('danger', !!cfg.danger);
        ok.classList.toggle('hide', !!cfg.hideOk);
        ok.onclick = function () {
            var value = null;
            if (cfg.mode === 'input') {
                value = $('dlgInput').value;
            } else if (cfg.mode === 'select') {
                value = $('dlgSelect').value;
            }
            closeDialog();
            if (cfg.onOk) {
                cfg.onOk(value);
            }
        };

        var cancel = $('dlgCancel');
        cancel.textContent = cfg.cancelText || '취소';
        cancel.classList.toggle('hide', !!cfg.hideCancel);
        cancel.onclick = function () {
            closeDialog();
            if (cfg.onCancel) {
                cfg.onCancel();
            }
        };

        $('dlgBody').onclick = function (event) {
            var target = closestAttr(event.target, 'data-act');
            if (!target) {
                return;
            }
            var act = target.getAttribute('data-act');
            closeDialog();
            if (cfg.onAct) {
                cfg.onAct(act);
            }
        };

        $('dialog').classList.remove('hide');
        if (cfg.mode === 'input') {
            setTimeout(function () {
                var input = $('dlgInput');
                input.focus();
                try {
                    input.setSelectionRange(0, String(input.value || '').length);
                } catch (e) {
                    // selection is cosmetic
                }
            }, 60);
        }
    }

    function closeDialog() {
        $('dialog').classList.add('hide');
        state.dialogCfg = null;
    }

    function promptDialog(title, desc, value, okText) {
        return new Promise(function (resolve) {
            showDialog({
                title: title,
                desc: desc,
                mode: 'input',
                value: value || '',
                okText: okText || '확인',
                onOk: function (v) {
                    resolve(v === null ? null : String(v));
                },
                onCancel: function () {
                    resolve(null);
                }
            });
        });
    }

    function confirmDialog(title, desc, okText, danger) {
        return new Promise(function (resolve) {
            showDialog({
                title: title,
                desc: desc,
                mode: 'confirm',
                okText: okText || '확인',
                danger: !!danger,
                onOk: function () {
                    resolve(true);
                },
                onCancel: function () {
                    resolve(false);
                }
            });
        });
    }

    // ---------------------------------------------------------------- picker

    function pickFolder(side, startPath, title) {
        return new Promise(function (resolve) {
            state.picker = {
                side: side,
                path: C.normalizePath(startPath || (side === 'nas' ? '/'
                    : (state.phone.root || '/storage/emulated/0'))),
                resolve: resolve,
                done: false
            };
            $('pickerTitle').textContent = title || '폴더 선택';
            $('pickerModal').classList.remove('hide');
            loadPicker(state.picker.path);
        });
    }

    function finishPicker(value) {
        var picker = state.picker;
        state.picker = null;
        $('pickerModal').classList.add('hide');
        if (picker && !picker.done) {
            picker.done = true;
            picker.resolve(value);
        }
    }

    function loadPicker(path) {
        var picker = state.picker;
        if (!picker) {
            return;
        }
        $('pickerPath').textContent = displayOf(path, picker.side);
        $('pickerList').innerHTML = '<div class="pickeritem"><span class="grow" style="color:#64748B;">'
            + '불러오는 중...</span></div>';
        var request = picker.side === 'nas'
            ? FM.nas('list', {path: path})
            : FM.local('list', {path: path});
        request.then(function (res) {
            if (!state.picker) {
                return;
            }
            picker.path = res.path || path;
            $('pickerPath').textContent = displayOf(picker.path, picker.side);
            var dirs = [];
            each(res.entries || [], function (entry) {
                if (entry && entry.isDir) {
                    dirs.push(entry);
                }
            });
            dirs.sort(function (a, b) {
                return String(a.name).localeCompare(String(b.name), 'ko', {numeric: true});
            });
            var html = '';
            var parent = C.parentOf(picker.path);
            var atRoot = C.normalizePath(picker.path) === C.normalizePath(picker.side === 'nas'
                ? '/' : (state.phone.root || picker.path));
            if (!atRoot && parent !== picker.path) {
                html += '<div class="pickeritem up" data-nav="' + esc(parent) + '">'
                    + ic('chevron-left') + '<span class="grow">상위 폴더</span></div>';
            }
            each(dirs, function (dir) {
                html += '<div class="pickeritem" data-nav="' + esc(dir.path) + '">'
                    + ic('folder') + '<span class="grow">' + esc(dir.name) + '</span>'
                    + '<span style="color:#64748B;">›</span></div>';
            });
            if (!html) {
                html = '<div class="pickeritem"><span class="grow" style="color:#64748B;">'
                    + '하위 폴더가 없습니다</span></div>';
            }
            var list = $('pickerList');
            list.innerHTML = html;
            applyIcons(list);
            list.onclick = function (event) {
                var target = closestAttr(event.target, 'data-nav');
                if (target) {
                    loadPicker(target.getAttribute('data-nav'));
                }
            };
        }).catch(function (error) {
            if (!state.picker) {
                return;
            }
            $('pickerList').innerHTML = '<div class="pickeritem"><span class="grow" '
                + 'style="color:#FB7185;">' + esc(error.message || '폴더를 열지 못했습니다.') + '</span></div>';
        });
    }

    // ---------------------------------------------------------------- viewer

    function viewerItems() {
        var filter = state.filter === 'folder' ? 'all' : state.filter;
        var list = C.visibleEntries(entries(), filter, state.query, state.sort);
        var out = [];
        each(list, function (entry) {
            if (entry && !entry.isDir
                && (C.isImage(entry.name) || C.isVideo(entry.name))) {
                out.push(entry);
            }
        });
        return out;
    }

    function openViewer(entry) {
        var list = viewerItems();
        var index = -1;
        each(list, function (item, i) {
            if (item.path === entry.path) {
                index = i;
            }
        });
        if (index < 0) {
            list = [entry];
            index = 0;
        }
        state.viewer.items = list;
        state.viewer.side = state.side;
        state.viewer.index = index;
        $('viewerModal').classList.remove('hide');
        showViewer(index);
    }

    function showViewer(index) {
        var items = state.viewer.items;
        if (!items.length) {
            return;
        }
        var count = items.length;
        if (index < 0) {
            index = count - 1;
        }
        if (index >= count) {
            index = 0;
        }
        state.viewer.index = index;
        var entry = items[index];
        var nas = state.viewer.side === 'nas';
        var img = $('viewerImg');
        var video = $('viewerVideo');

        resetZoom();
        if (C.isVideo(entry.name)) {
            img.classList.add('hide');
            img.removeAttribute('src');
            video.classList.remove('hide');
            $('viewerZoom').classList.add('hide');
            playInViewer(entry, nas);
        } else {
            try {
                video.pause();
            } catch (e) {
                // nothing playing
            }
            video.removeAttribute('src');
            video.classList.add('hide');
            img.classList.remove('hide');
            $('viewerZoom').classList.remove('hide');
            img.src = FM.mediaUrl(entry.path, nas, false);
        }

        $('viewerTitle').textContent = entry.name;
        $('viewerMeta').textContent = C.formatSize(entry.size) + ' · ' + C.kindLabel(entry.name)
            + ' · ' + (nas ? '시놀로지 NAS' : '내 폰');
        $('viewerCount').textContent = (index + 1) + ' / ' + count;
        $('viewerPrev').disabled = count < 2;
        $('viewerNext').disabled = count < 2;
        $('viewerSaveLabel').textContent = nas ? '폰에 저장' : 'NAS로 올리기';
    }

    function playInViewer(entry, nas) {
        var video = $('viewerVideo');
        video.oncanplay = function () {
            video.play().catch(function () {
                // the user can press play
            });
        };
        video.onerror = function () {
            toast('재생할 수 없는 형식입니다. 외부 플레이어로 열어 보세요.');
        };

        if (!nas) {
            video.src = FM.mediaUrl(entry.path, false, false);
            video.load();
            return;
        }
        if (isWebDav()) {
            // WebDAV answers Range requests, so the video streams straight from the NAS
            // through the loopback server; only when that fails does it fall back to a copy.
            video.onerror = function () {
                playFromCache(entry);
            };
            video.src = FM.mediaUrl(entry.path, true, false);
            video.load();
            return;
        }
        playFromCache(entry);
    }

    /** DSM's Download API has no Range support, so a NAS video is copied once and played locally. */
    function playFromCache(entry) {
        var video = $('viewerVideo');
        video.oncanplay = function () {
            video.play().catch(function () {
                // the user can press play
            });
        };
        video.onerror = function () {
            toast('재생할 수 없는 형식입니다. 외부 플레이어로 열어 보세요.');
        };
        var cacheDir = (state.local && state.local.videoCacheDir) || '';
        findCached(cacheDir, entry).then(function (cached) {
            if (cached) {
                video.src = FM.mediaUrl(cached, false, false);
                video.load();
                return;
            }
            var t = startTransfer(entry.name, '시놀로지 NAS → 재생 캐시', entry.size);
            FM.nas('download', {path: entry.path, destDir: cacheDir}, function (percent, message) {
                updateTransfer(t, percent, message);
            }).then(function (res) {
                endTransfer(t, true, '재생 준비 완료');
                video.src = FM.mediaUrl(res.savedPath, false, false);
                video.load();
            }).catch(function (error) {
                endTransfer(t, false, error.message);
                toast(error.message || '동영상을 열지 못했습니다.');
            });
        });
    }

    function findCached(dir, entry) {
        if (!dir) {
            return Promise.resolve('');
        }
        return FM.local('list', {path: dir}).then(function (res) {
            var hit = '';
            each(res.entries || [], function (item) {
                if (!hit && item.name === entry.name && Math.abs(item.size - entry.size) < 1024) {
                    hit = item.path;
                }
            });
            return hit;
        }).catch(function () {
            return '';
        });
    }

    function navigateViewer(step) {
        var items = state.viewer.items;
        if (!items.length) {
            return;
        }
        showViewer(state.viewer.index + step);
    }

    function resetZoom() {
        state.viewer.scale = 1;
        state.viewer.tx = 0;
        state.viewer.ty = 0;
        applyZoom();
    }

    function applyZoom() {
        var img = $('viewerImg');
        if (!img) {
            return;
        }
        img.style.transform = 'translate(' + state.viewer.tx + 'px,' + state.viewer.ty
            + 'px) scale(' + state.viewer.scale + ')';
    }

    function toggleZoom() {
        if (state.viewer.scale > 1) {
            resetZoom();
        } else {
            state.viewer.scale = 2.5;
            state.viewer.tx = 0;
            state.viewer.ty = 0;
            applyZoom();
        }
    }

    function closeViewer() {
        var video = $('viewerVideo');
        try {
            video.pause();
        } catch (e) {
            // nothing playing
        }
        video.removeAttribute('src');
        $('viewerImg').removeAttribute('src');
        $('viewerModal').classList.add('hide');
        resetZoom();
    }

    function viewerEntry() {
        var items = state.viewer.items;
        if (!items.length) {
            return null;
        }
        return items[state.viewer.index] || null;
    }

    // ----------------------------------------------------------------- login

    function sameServer(url) {
        var saved = C.splitServerUrl((state.nasStatus || {}).lastUrl || '');
        var typed = C.splitServerUrl(url);
        return !!typed.host && typed.host === saved.host && String(typed.port) === String(saved.port)
            && typed.protocol === saved.protocol;
    }

    /** The NAS tab is talking WebDAV rather than the DSM File Station API. */
    function isWebDav() {
        var info = state.nasStatus || {};
        return info.protocol === 'webdav';
    }

    // ------------------------------------------------------------- protocol UI

    function paintProtocol(proto) {
        var p = C.normalizeProtocol(proto) || 'dsm';
        state.settings.protocol = p;
        var dsmBtn = $('cfgProtoDsm');
        var davBtn = $('cfgProtoDav');
        if (dsmBtn) {
            dsmBtn.classList.toggle('on', p === 'dsm');
        }
        if (davBtn) {
            davBtn.classList.toggle('on', p === 'webdav');
        }
        var hint = $('cfgProtoHint');
        if (hint) {
            hint.textContent = p === 'webdav'
                ? 'WebDAV로 접속합니다. 포트 5005는 http, 5006은 https입니다.'
                : 'DSM 계정으로 로그인해 File Station의 공유 폴더를 다룹니다.';
        }
        var portBox = $('cfgPort');
        if (portBox) {
            portBox.placeholder = p === 'webdav' ? '5005' : '5001';
        }
        var portLabel = $('cfgPortLabel');
        if (portLabel) {
            portLabel.textContent = p === 'webdav' ? '포트 (5005 HTTP / 5006 HTTPS)' : '포트 (DSM 5001 HTTPS)';
        }
    }

    /** A protocol choice from the segmented control, keeping the port consistent. */
    function chooseProtocol(proto) {
        var p = C.normalizeProtocol(proto) || 'dsm';
        var portBox = $('cfgPort');
        var port = portBox ? String(portBox.value || '').trim().replace(/[^0-9]/g, '') : '';
        paintProtocol(p);
        if (portBox) {
            if (p === 'webdav' && (!port || port === '5000' || port === '5001')) {
                portBox.value = '5005';
            } else if (p === 'dsm' && (port === '5005' || port === '5006')) {
                portBox.value = '5001';
            }
        }
        saveSettings();
    }

    /** Typing :5005/:5006 (in the address or the port box) selects WebDAV by itself. */
    function followAddressPort() {
        var host = String($('cfgHost').value || '').trim();
        var port = String($('cfgPort').value || '').trim().replace(/[^0-9]/g, '');
        var match = /:(5005|5006)(?:$|[\/?#])/.exec(host);
        if (match || C.isWebDavPort(port)) {
            if (state.settings.protocol !== 'webdav') {
                paintProtocol('webdav');
            }
            if (match && !$('cfgPort').value.trim()) {
                $('cfgPort').value = match[1];
            }
        }
    }

    function openServerModal(prefill) {
        var info = state.nasStatus || {};
        var url = (prefill && prefill.url) || info.lastUrl || '';
        var parts = C.splitServerUrl(url);
        $('cfgHost').value = parts.host || '';
        $('cfgPort').value = parts.port || '5001';
        $('cfgUser').value = (prefill && prefill.account) || info.lastAccount || '';
        $('cfgPass').value = '';
        $('cfgOtp').value = '';
        $('cfgSsl').classList.toggle('on', state.settings.ignoreSsl);
        $('cfgAuto').classList.toggle('on', state.settings.autoLogin);
        $('cfgState').value = state.connected ? '연결됨' : (info.hasSavedPassword
            ? '저장된 비밀번호 있음' : '연결 안 됨');
        $('cfgPass').placeholder = info.hasSavedPassword && sameServer(url)
            ? '저장된 비밀번호 사용' : '비밀번호';
        paintProtocol(C.normalizeProtocol(prefill && prefill.protocol) || parts.protocol
            || state.settings.protocol || 'dsm');

        var servers = info.servers || [];
        var savedBox = $('savedServers');
        if (servers.length) {
            var html = '<label style="font-size:10.5px;font-weight:700;color:#94A3B8;">저장된 서버</label>';
            each(servers, function (server) {
                var proto = C.normalizeProtocol(server.protocol)
                    || C.splitServerUrl(server.url || '').protocol;
                html += '<button class="savedcard" data-act="useServer" data-url="' + esc(server.url)
                    + '" data-account="' + esc(server.account || '')
                    + '" data-protocol="' + esc(proto) + '">'
                    + '<span style="color:#60A5FA;">' + ic('server') + '</span>'
                    + '<span class="grow"><span class="u">' + esc(server.url) + '</span>'
                    + '<span class="a">' + esc(server.account || '') + ' · '
                    + esc(C.protocolLabel(proto)) + '</span></span>'
                    + '<span class="go">불러오기</span></button>';
            });
            savedBox.innerHTML = html;
            applyIcons(savedBox);
            savedBox.onclick = function (event) {
                var target = closestAttr(event.target, 'data-act');
                if (target && target.getAttribute('data-act') === 'useServer') {
                    openServerModal({
                        url: target.getAttribute('data-url') || '',
                        account: target.getAttribute('data-account') || '',
                        protocol: target.getAttribute('data-protocol') || ''
                    });
                }
            };
        } else {
            savedBox.innerHTML = '<p style="font-size:10.5px;color:#64748B;">'
                + '저장된 서버가 없습니다. 주소와 계정을 입력해 주세요.</p>';
        }

        updatePermissionLine();
        $('serverModal').classList.remove('hide');
    }

    function closeServerModal() {
        $('serverModal').classList.add('hide');
        var btn = $('btnConnect');
        btn.disabled = false;
        btn.textContent = '저장 및 서버 접속';
    }

    function updatePermissionLine() {
        var local = state.local || {};
        var allFiles = local.allFiles !== false;
        var line = $('permLine');
        line.classList.toggle('warn', !allFiles);
        $('permTitle').textContent = allFiles
            ? '폰 저장소 모든 파일 접근 허용됨'
            : '폰 저장소 권한이 필요합니다';
        $('permText').textContent = allFiles
            ? 'DCIM · Download · Movies 폴더를 그대로 읽고 쓸 수 있습니다.'
            : 'Android 11부터는 설정에서 ‘모든 파일 접근’을 허용해야 전체 폴더를 다룰 수 있습니다.';
        $('permText').textContent += ' (Android ' + (local.sdk || '-') + ')';
    }

    function connectServer() {
        var host = $('cfgHost').value.trim();
        var port = $('cfgPort').value.trim();
        var user = $('cfgUser').value.trim();
        var pass = $('cfgPass').value;
        var otp = ($('cfgOtp').value || '').trim();
        var protocol = C.normalizeProtocol(state.settings.protocol)
            || C.protocolFor(host, port, '');
        var url = C.serverUrl(host, port, protocol);
        protocol = C.normalizeProtocol(protocol) || C.splitServerUrl(url).protocol;

        if (!url || !user) {
            toast('서버 주소와 아이디를 입력해 주세요.');
            return;
        }
        var info = state.nasStatus || {};
        var useSaved = !pass && info.hasSavedPassword && sameServer(url);
        if (!pass && !useSaved) {
            toast('비밀번호를 입력해 주세요.');
            $('cfgPass').focus();
            return;
        }

        state.settings.ignoreSsl = $('cfgSsl').classList.contains('on');
        state.settings.autoLogin = $('cfgAuto').classList.contains('on');
        state.settings.protocol = protocol;
        saveSettings();

        var btn = $('btnConnect');
        btn.disabled = true;
        btn.textContent = '연결 중...';
        $('cfgState').value = '연결 중...';

        var request = useSaved
            ? FM.nas('autoLogin')
            : FM.nas('login', {
                url: url,
                protocol: protocol,
                username: user,
                password: pass,
                otp: otp,
                ignoreSsl: state.settings.ignoreSsl,
                save: true,
                autoLogin: state.settings.autoLogin
            });

        request.then(function (res) {
            state.connected = true;
            state.nasStatus = res || {};
            state.settings.protocol = C.normalizeProtocol((res || {}).protocol) || protocol;
            saveSettings();
            $('cfgState').value = '연결됨';
            closeServerModal();
            toast(C.protocolLabel(state.settings.protocol) + '로 연결되었습니다.');
            state.side = 'nas';
            state.query = '';
            clearSelection(false);
            loadNas('/');
        }).catch(function (error) {
            btn.disabled = false;
            btn.textContent = '저장 및 서버 접속';
            var message = error.message || '로그인하지 못했습니다.';
            if (protocol === 'dsm' && (error.code === 403 || error.code === 404 || error.code === 406)) {
                message = '2단계 인증 번호가 필요합니다. OTP 6자리를 입력해 주세요.';
            }
            $('cfgState').value = '연결 실패';
            toast(message);
        });
    }

    // --------------------------------------------------------------- favs

    function favouriteIndex(path, side) {
        var target = C.normalizePath(path);
        for (var i = 0; i < state.favs.length; i++) {
            var fav = state.favs[i];
            if (fav && fav.side === side && C.normalizePath(fav.path) === target) {
                return i;
            }
        }
        return -1;
    }

    function isFavourite() {
        return favouriteIndex(currentPath(), state.side) >= 0;
    }

    function toggleFavourite() {
        var path = currentPath();
        if (!path) {
            return;
        }
        var index = favouriteIndex(path, state.side);
        if (index >= 0) {
            state.favs.splice(index, 1);
            toast('즐겨찾기에서 뺐습니다.');
        } else {
            var label = C.nameOf(path);
            if (!label || (isNas() ? C.normalizePath(path) === '/'
                : C.normalizePath(path) === C.normalizePath(state.phone.root))) {
                label = isNas() ? 'NAS' : (state.phone.rootLabel || '내부 저장소');
            }
            state.favs.push({side: state.side, path: C.normalizePath(path), label: label});
            toast('즐겨찾기에 넣었습니다.');
        }
        write(LS.favs, JSON.stringify(state.favs));
        renderFavbar();
        renderToolbar();
    }

    function removeFavourite(path, side) {
        var index = favouriteIndex(path, side);
        if (index >= 0) {
            state.favs.splice(index, 1);
            write(LS.favs, JSON.stringify(state.favs));
        }
    }

    // ----------------------------------------------------------------- menu

    function openMenu() {
        $('menuFilterValue').textContent = C.filterLabel(state.filter);
        $('menuHiddenValue').textContent = state.settings.showHidden ? '켜짐' : '꺼짐';
        $('menuServerValue').textContent = state.connected ? '연결됨' : '연결 안 됨';
        $('menuCacheValue').textContent = C.formatSize(FM.phoneCacheSize() + FM.nasCacheSize());
        $('menuSheet').classList.remove('hide');
    }

    function closeMenu() {
        $('menuSheet').classList.add('hide');
    }

    // ---------------------------------------------------------------- chrome

    function bindChrome() {
        $('tab-nas').onclick = function () {
            switchSide('nas');
        };
        $('tab-phone').onclick = function () {
            switchSide('phone');
        };

        $('btnUp').onclick = goUp;
        $('btnReload').onclick = function () {
            reload(false);
        };
        $('btnFav').onclick = toggleFavourite;
        $('btnNewFolder').onclick = newFolder;
        $('btnUpload').onclick = function () {
            if (isNas()) {
                switchSide('phone');
                toast('폰 저장소에서 파일을 고른 뒤 시놀로지로 올릴 수 있습니다.');
                return;
            }
            var picked = selectionList();
            if (!picked.length) {
                toast('폰 저장소에서 파일을 선택한 뒤 올릴 수 있습니다.');
                switchSide('phone');
                return;
            }
            if (!state.connected) {
                openServerModal();
                return;
            }
            uploadEntries(picked, nearestDir('nas'));
        };

        $('sortBtn').onclick = function () {
            state.sort = C.nextSort(state.sort);
            write(LS.sort, state.sort);
            $('sortLabel').textContent = C.sortLabel(state.sort);
            renderList();
            renderStatus();
            toast('정렬: ' + C.sortLabel(state.sort));
        };

        $('viewToggle').onclick = function () {
            state.view = state.view === 'grid' ? 'list' : 'grid';
            write(LS.view, state.view);
            renderToolbar();
            renderList();
        };

        $('selectModeBtn').onclick = function () {
            if (state.selecting) {
                clearSelection();
            } else {
                state.selecting = true;
                renderSelection();
                renderList();
            }
        };

        $('searchInput').addEventListener('input', function () {
            state.query = $('searchInput').value || '';
            var clear = $('clearSearch');
            clear.classList.toggle('hide', !state.query);
            renderList();
            renderStatus();
        });

        $('clearSearch').onclick = function () {
            state.query = '';
            $('searchInput').value = '';
            $('clearSearch').classList.add('hide');
            renderList();
            renderStatus();
        };

        $('btnSelectAll').onclick = selectAll;
        $('btnExitSelect').onclick = function () {
            clearSelection();
        };
        $('btnPrimaryTransfer').onclick = applyPrimaryTransfer;
        $('btnMove').onclick = function () {
            copyMoveEntries(selectionList(), true);
        };
        $('btnCopy').onclick = function () {
            copyMoveEntries(selectionList(), false);
        };
        $('btnRename').onclick = function () {
            var picked = selectionList();
            if (picked.length === 1) {
                renameEntry(picked[0]);
            }
        };
        $('btnShare').onclick = function () {
            shareOrOpen(selectionList());
        };
        $('btnInfo').onclick = function () {
            var picked = selectionList();
            if (picked.length === 1) {
                infoDialog(picked[0]);
            }
        };
        $('btnDelete').onclick = function () {
            deleteEntries(selectionList());
        };

        $('btnTransfers').onclick = function () {
            renderTransfers();
            $('transferDrawer').classList.remove('hide');
        };
        $('btnCloseTransfers').onclick = function () {
            $('transferDrawer').classList.add('hide');
        };
        $('btnClearTransfers').onclick = function () {
            var kept = [];
            each(state.transfers, function (t) {
                if (t.status === 'running') {
                    kept.push(t);
                }
            });
            state.transfers = kept;
            renderTransfers();
            renderHeader();
        };
        $('miniToast').onclick = function () {
            renderTransfers();
            $('transferDrawer').classList.remove('hide');
        };

        $('btnServerSettings').onclick = function () {
            openServerModal();
        };
        $('btnCloseServer').onclick = closeServerModal;
        $('btnConnect').onclick = connectServer;
        $('cfgProto').onclick = function (event) {
            var target = closestAttr(event.target, 'data-proto');
            if (target) {
                chooseProtocol(target.getAttribute('data-proto'));
            }
        };
        $('cfgPort').addEventListener('input', followAddressPort);
        $('cfgHost').addEventListener('input', followAddressPort);
        $('btnPermAction').onclick = function () {
            FM.allowAllFiles();
        };
        $('cfgSsl').onclick = function () {
            var on = !$('cfgSsl').classList.contains('on');
            $('cfgSsl').classList.toggle('on', on);
            state.settings.ignoreSsl = on;
            saveSettings();
        };
        $('cfgAuto').onclick = function () {
            var on = !$('cfgAuto').classList.contains('on');
            $('cfgAuto').classList.toggle('on', on);
            state.settings.autoLogin = on;
            saveSettings();
        };

        $('btnCloseMenu').onclick = closeMenu;
        $('menuFilter').onclick = function () {
            state.filter = C.nextFilter(state.filter);
            write(LS.filter, state.filter);
            $('menuFilterValue').textContent = C.filterLabel(state.filter);
            clearSelection(false);
            renderList();
            renderToolbar();
            renderStatus();
        };
        $('menuHidden').onclick = function () {
            state.settings.showHidden = !state.settings.showHidden;
            saveSettings();
            $('menuHiddenValue').textContent = state.settings.showHidden ? '켜짐' : '꺼짐';
            reload(false);
        };
        $('menuServer').onclick = function () {
            closeMenu();
            openServerModal();
        };
        $('menuCache').onclick = function () {
            FM.clearCaches();
            toast('썸네일 캐시를 비웠습니다.');
            $('menuCacheValue').textContent = '0 B';
            setTimeout(reload, 400);
        };
        $('menuLogout').onclick = function () {
            closeMenu();
            confirmDialog('로그아웃할까요?', '저장된 세션을 지웁니다. 비밀번호는 폰에 남아 있지 않습니다.',
                '로그아웃', true).then(function (yes) {
                if (!yes) {
                    return;
                }
                FM.nas('logout').then(function (res) {
                    state.connected = false;
                    state.nasStatus = res || {};
                    state.nas.entries = [];
                    state.nas.path = '/';
                    toast('로그아웃했습니다.');
                    render();
                }).catch(function () {
                    toast('로그아웃하지 못했습니다.');
                });
            });
        };
        $('menuAbout').onclick = function () {
            closeMenu();
            var rows = [
                ['앱', 'SynoFile Pro'],
                ['버전', VERSION],
                ['패키지', PACKAGE],
                ['시놀로지', 'DSM File Station API · WebDAV (HTTP 5005 / HTTPS 5006)'],
                ['폰 저장소', 'Android ' + ((state.local && state.local.sdk) || '-')]
            ];
            var html = '';
            each(rows, function (row) {
                html += '<div class="inforow"><span>' + esc(row[0]) + '</span><b>'
                    + esc(row[1]) + '</b></div>';
            });
            showDialog({title: '앱 정보', mode: 'info', html: html, okText: '닫기', hideCancel: true});
        };

        $('btnClosePicker').onclick = function () {
            finishPicker(null);
        };
        $('btnPickerCancel').onclick = function () {
            finishPicker(null);
        };
        $('btnPickerChoose').onclick = function () {
            finishPicker(state.picker ? state.picker.path : null);
        };

        bindViewer();

        $('serverModal').onclick = function (event) {
            if (event.target === $('serverModal')) {
                closeServerModal();
            }
        };
        $('transferDrawer').onclick = function (event) {
            if (event.target === $('transferDrawer')) {
                $('transferDrawer').classList.add('hide');
            }
        };
        $('menuSheet').onclick = function (event) {
            if (event.target === $('menuSheet')) {
                closeMenu();
            }
        };
        $('pickerModal').onclick = function (event) {
            if (event.target === $('pickerModal')) {
                finishPicker(null);
            }
        };
        $('dialog').onclick = function (event) {
            var cfg = state.dialogCfg;
            if (event.target === $('dialog') && cfg) {
                closeDialog();
                if (cfg.onCancel) {
                    cfg.onCancel();
                }
            }
        };
        $('emptyAction').onclick = function () {
            FM.allowAllFiles();
        };
    }

    function bindViewer() {
        $('viewerClose').onclick = closeViewer;
        $('viewerPrev').onclick = function () {
            navigateViewer(-1);
        };
        $('viewerNext').onclick = function () {
            navigateViewer(1);
        };
        $('viewerZoom').onclick = toggleZoom;

        $('viewerSave').onclick = function () {
            var entry = viewerEntry();
            if (!entry) {
                return;
            }
            if (state.viewer.side === 'nas') {
                downloadEntries([entry], nearestDir('phone'));
            } else {
                uploadEntries([entry], nearestDir('nas'));
            }
        };

        $('viewerDownload').onclick = function () {
            $('viewerSave').onclick();
        };

        $('viewerRename').onclick = function () {
            var entry = viewerEntry();
            if (!entry) {
                return;
            }
            closeViewer();
            renameEntry(entry);
        };

        $('viewerDelete').onclick = function () {
            var entry = viewerEntry();
            if (!entry) {
                return;
            }
            confirmDialog('삭제할까요?', entry.name + '\n\n삭제한 파일은 휴지통 없이 바로 지워집니다.',
                '삭제', true).then(function (yes) {
                if (!yes) {
                    return;
                }
                var request = state.viewer.side === 'nas'
                    ? FM.nas('delete', {paths: [entry.path]})
                    : FM.local('delete', {paths: [entry.path]});
                request.then(function () {
                    toast('삭제했습니다.');
                    state.viewer.items = state.viewer.items.filter(function (item) {
                        return item.path !== entry.path;
                    });
                    if (!state.viewer.items.length) {
                        closeViewer();
                    } else {
                        showViewer(Math.min(state.viewer.index, state.viewer.items.length - 1));
                    }
                    reload(false);
                }).catch(function (error) {
                    toast(error.message || '삭제하지 못했습니다.');
                });
            });
        };

        var stage = $('viewerStage');
        var gesture = {
            startX: 0,
            startY: 0,
            startTime: 0,
            startDistance: 0,
            startScale: 1,
            startTx: 0,
            startTy: 0,
            lastTap: 0,
            pinching: false
        };

        function distance(touches) {
            var dx = touches[0].clientX - touches[1].clientX;
            var dy = touches[0].clientY - touches[1].clientY;
            return Math.sqrt(dx * dx + dy * dy) || 1;
        }

        stage.addEventListener('touchstart', function (event) {
            if (event.touches.length === 2) {
                gesture.pinching = true;
                gesture.startDistance = distance(event.touches);
                gesture.startScale = state.viewer.scale;
                return;
            }
            if (event.touches.length !== 1) {
                return;
            }
            gesture.pinching = false;
            gesture.startX = event.touches[0].clientX;
            gesture.startY = event.touches[0].clientY;
            gesture.startTime = Date.now();
            gesture.startTx = state.viewer.tx;
            gesture.startTy = state.viewer.ty;
            var now = Date.now();
            if (now - gesture.lastTap < 280) {
                toggleZoom();
                gesture.lastTap = 0;
            } else {
                gesture.lastTap = now;
            }
        }, {passive: true});

        stage.addEventListener('touchmove', function (event) {
            if (event.touches.length === 2) {
                if (event.preventDefault) {
                    event.preventDefault();
                }
                var ratio = distance(event.touches) / gesture.startDistance;
                state.viewer.scale = Math.max(1, Math.min(5, gesture.startScale * ratio));
                applyZoom();
                return;
            }
            if (event.touches.length === 1 && state.viewer.scale > 1) {
                if (event.preventDefault) {
                    event.preventDefault();
                }
                var dx = event.touches[0].clientX - gesture.startX;
                var dy = event.touches[0].clientY - gesture.startY;
                state.viewer.tx = gesture.startTx + dx;
                state.viewer.ty = gesture.startTy + dy;
                applyZoom();
            }
        }, {passive: false});

        stage.addEventListener('touchend', function (event) {
            if (event.touches && event.touches.length > 0) {
                return;
            }
            if (gesture.pinching || state.viewer.scale > 1) {
                gesture.pinching = false;
                return;
            }
            var touch = event.changedTouches && event.changedTouches[0];
            if (!touch) {
                return;
            }
            var dx = touch.clientX - gesture.startX;
            var dy = touch.clientY - gesture.startY;
            var elapsed = Date.now() - gesture.startTime;
            if (Math.abs(dx) > 60 && Math.abs(dy) < 90 && elapsed < 700) {
                navigateViewer(dx < 0 ? 1 : -1);
            }
        }, {passive: true});
    }

    // ------------------------------------------------------------- back key

    window.fmHandleBack = function () {
        if (!$('viewerModal').classList.contains('hide')) {
            closeViewer();
            return true;
        }
        if (!$('dialog').classList.contains('hide')) {
            var cfg = state.dialogCfg;
            closeDialog();
            if (cfg && cfg.onCancel) {
                cfg.onCancel();
            }
            return true;
        }
        if (!$('pickerModal').classList.contains('hide')) {
            finishPicker(null);
            return true;
        }
        if (!$('serverModal').classList.contains('hide')) {
            closeServerModal();
            return true;
        }
        if (!$('transferDrawer').classList.contains('hide')) {
            $('transferDrawer').classList.add('hide');
            return true;
        }
        if (!$('menuSheet').classList.contains('hide')) {
            closeMenu();
            return true;
        }
        if (state.selecting) {
            clearSelection();
            return true;
        }
        var root = isNas() ? '/' : C.normalizePath(state.phone.root || state.phone.path);
        if (C.normalizePath(currentPath()) !== root) {
            goUp();
            return true;
        }
        if (isNas()) {
            switchSide('phone');
            return true;
        }
        return false;
    };

    // ------------------------------------------------------------ start up

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
