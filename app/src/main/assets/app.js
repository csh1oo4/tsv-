/*
 * app.js — the 「NAS 파일매니저」 screen.
 *
 * One screen, four tabs: 내 폰 / 시놀로지 / 즐겨찾기 / 설정. Everything file-shaped goes
 * through two bridges (see bridge.js): FM.local for the phone, FM.nas for DSM. Photos and
 * videos are displayed from the loopback server (FM.mediaUrl), never by fetching bytes
 * into the page.
 */
(function () {
    'use strict';

    var C = window.FMCore;
    var FM = window.FM;
    var $ = function (id) {
        return document.getElementById(id);
    };

    var LS = {
        view: 'fm_view',
        sort: 'fm_sort',
        favs: 'fm_favs',
        settings: 'fm_settings'
    };

    var state = {
        tab: 'phone',
        view: localStorage.getItem(LS.view) || 'grid',
        sort: localStorage.getItem(LS.sort) || 'name',
        filter: 'all',
        query: '',
        searching: false,
        selecting: false,
        selection: [],
        connected: false,
        booted: false,
        local: null,
        phoneRoot: '',
        phone: {path: '', entries: [], quick: [], parent: '', storage: null},
        nas: {path: '/', entries: [], parent: '/', total: 0},
        favs: readFavs(),
        settings: readSettings(),
        busy: false,
        viewer: {items: [], index: 0, scale: 1, tx: 0, ty: 0},
        player: {entry: null, url: ''}
    };

    // ------------------------------------------------------------------ utils

    function esc(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    var toastTimer = null;

    function toast(message) {
        var el = $('toast');
        el.textContent = message;
        el.classList.add('on');
        if (toastTimer) {
            clearTimeout(toastTimer);
        }
        toastTimer = setTimeout(function () {
            el.classList.remove('on');
        }, 2600);
    }

    function readFavs() {
        try {
            var raw = JSON.parse(localStorage.getItem(LS.favs) || '[]');
            return Array.isArray(raw) ? raw : [];
        } catch (e) {
            return [];
        }
    }

    function writeFavs() {
        try {
            localStorage.setItem(LS.favs, JSON.stringify(state.favs));
        } catch (e) {
            // favourites are a convenience
        }
    }

    function readSettings() {
        var defaults = {ignoreSsl: true, autoLogin: true, showHidden: false};
        try {
            var raw = JSON.parse(localStorage.getItem(LS.settings) || '{}');
            return Object.assign(defaults, raw || {});
        } catch (e) {
            return defaults;
        }
    }

    function writeSettings() {
        try {
            localStorage.setItem(LS.settings, JSON.stringify(state.settings));
        } catch (e) {
            // ignore
        }
    }

    function sideOf(tab) {
        return tab === 'nas' ? 'nas' : 'phone';
    }

    function isNasSide() {
        return state.tab === 'nas';
    }

    function currentEntries() {
        if (state.tab === 'phone') {
            return state.phone.entries;
        }
        if (state.tab === 'nas') {
            return state.nas.entries;
        }
        return [];
    }

    function currentPath() {
        if (state.tab === 'nas') {
            return state.nas.path;
        }
        return state.phone.path;
    }

    // ----------------------------------------------------------------- dialogs

    function openModal(html) {
        $('modalSheet').innerHTML = html;
        $('modal').classList.add('on');
    }

    function closeModal() {
        $('modal').classList.remove('on');
        $('modalSheet').innerHTML = '';
    }

    function promptDialog(opts) {
        var options = opts || {};
        return new Promise(function (resolve) {
            openModal(
                '<h3>' + esc(options.title || '이름 입력') + '</h3>'
                + (options.desc ? '<div class="body">' + esc(options.desc) + '</div>' : '')
                + '<div class="field"><input id="dlgInput" type="text" autocomplete="off" '
                + 'value="' + esc(options.value || '') + '" placeholder="'
                + esc(options.placeholder || '') + '"></div>'
                + '<div class="foot"><button class="btn ghost" id="dlgCancel">취소</button>'
                + '<button class="btn" id="dlgOk">' + esc(options.okText || '확인') + '</button></div>'
            );
            var input = $('dlgInput');
            var done = function (value) {
                closeModal();
                resolve(value);
            };
            $('dlgCancel').onclick = function () {
                done(null);
            };
            $('dlgOk').onclick = function () {
                done(input.value);
            };
            input.addEventListener('keydown', function (event) {
                if (event.key === 'Enter') {
                    done(input.value);
                }
            });
            setTimeout(function () {
                input.focus();
                try {
                    input.setSelectionRange(0, input.value.length);
                } catch (e) {
                    // selection is cosmetic
                }
            }, 60);
        });
    }

    function confirmDialog(title, message, okText, danger) {
        return new Promise(function (resolve) {
            openModal(
                '<h3>' + esc(title) + '</h3>'
                + '<div class="body">' + esc(message || '') + '</div>'
                + '<div class="foot"><button class="btn ghost" id="dlgCancel">취소</button>'
                + '<button class="btn ' + (danger ? 'danger' : '') + '" id="dlgOk">'
                + esc(okText || '확인') + '</button></div>'
            );
            $('dlgCancel').onclick = function () {
                closeModal();
                resolve(false);
            };
            $('dlgOk').onclick = function () {
                closeModal();
                resolve(true);
            };
        });
    }

    function progressDialog(title) {
        $('progressSheet').innerHTML =
            '<h3>' + esc(title) + '</h3>'
            + '<div class="body" id="pgMessage">준비하는 중...</div>'
            + '<div class="progress-track"><div class="progress-fill" id="pgFill"></div></div>';
        $('progress').classList.add('on');
        var fill = $('pgFill');
        var message = $('pgMessage');
        return {
            update: function (percent, text) {
                if (text) {
                    message.textContent = text;
                }
                if (percent === null || percent === undefined || percent < 0) {
                    fill.classList.add('indeterminate');
                } else {
                    fill.classList.remove('indeterminate');
                    fill.style.width = Math.max(3, Math.min(100, percent)) + '%';
                }
            },
            close: function () {
                $('progress').classList.remove('on');
                $('progressSheet').innerHTML = '';
            }
        };
    }

    // -------------------------------------------------------------------- boot

    function boot() {
        if (state.booted) {
            return;
        }
        state.booted = true;
        bindChrome();
        applyViewChrome();
        if (!FM.hasNative) {
            renderBadBrowser();
            return;
        }
        FM.local('status').then(function (res) {
            state.local = res;
            if (res.serverBase) {
                window.fmServerBase = res.serverBase;
                window.fmServerToken = res.serverToken || '';
            }
            var roots = res.roots || [];
            state.phoneRoot = roots.length ? roots[0].path : '/storage/emulated/0';
            return refreshNasStatus().then(function () {
                return maybeAutoLogin();
            }).then(function () {
                return loadPhone(state.phoneRoot);
            });
        }).catch(function (error) {
            toast(error.message || '폰 저장소를 열지 못했습니다.');
            render();
        });
    }

    FM.onReady = boot;
    FM.onResume = function () {
        if (!state.booted) {
            boot();
            return;
        }
        // Coming back from the permission screen: refresh what we can see.
        refreshLocalStatus().then(function () {
            if (state.tab === 'phone') {
                loadPhone(state.phone.path || state.phoneRoot);
            } else {
                render();
            }
        });
    };
    FM.onPermissionResult = function () {
        refreshLocalStatus();
    };

    function refreshLocalStatus() {
        return FM.local('status').then(function (res) {
            state.local = res;
            if (res.roots && res.roots.length && !state.phoneRoot) {
                state.phoneRoot = res.roots[0].path;
            }
            return res;
        }).catch(function () {
            return state.local;
        });
    }

    function refreshNasStatus() {
        return FM.nas('status').then(function (res) {
            state.connected = !!res.connected;
            state.nasStatus = res;
            renderHeader();
            return res;
        }).catch(function () {
            state.connected = false;
            return null;
        });
    }

    /** App start: sign in again without asking, when the user left 자동 로그인 on. */
    function maybeAutoLogin() {
        var info = state.nasStatus || {};
        if (state.connected || !info.hasSavedPassword || !state.settings.autoLogin) {
            return Promise.resolve();
        }
        return FM.nas('autoLogin').then(function (res) {
            if (res && res.connected) {
                state.connected = true;
                state.nasStatus = res;
                renderHeader();
            }
        }).catch(function () {
            // a failed automatic sign-in stays quiet; the NAS tab shows the login form
        });
    }

    // ----------------------------------------------------------------- loading

    function loadPhone(path, opts) {
        var target = path || state.phoneRoot;
        var options = opts || {};
        setLoading(true);
        return FM.local('list', {path: target, showHidden: !!state.settings.showHidden})
            .then(function (res) {
                state.phone.path = res.path;
                state.phone.entries = res.entries || [];
                state.phone.parent = res.parent || state.phone.path;
                state.phone.quick = res.quick || [];
                state.phone.storage = res.storage || null;
                if (!state.phoneRoot && state.local && state.local.roots) {
                    state.phoneRoot = state.local.roots[0].path;
                }
                if (!options.keepSelection) {
                    clearSelection(false);
                }
                render();
            })
            .catch(function (error) {
                setLoading(false);
                toast(error.message || '폴더를 읽지 못했습니다.');
                render();
            });
    }

    function loadNas(path, opts) {
        var options = opts || {};
        if (!state.connected) {
            render();
            return Promise.resolve();
        }
        setLoading(true);
        return FM.nas('list', {
            path: path || state.nas.path || '/',
            sortBy: state.sort,
            sortDirection: 'asc'
        }).then(function (res) {
            state.nas.path = res.path || path || '/';
            state.nas.entries = res.entries || [];
            state.nas.parent = res.parent || '/';
            state.nas.total = res.total || state.nas.entries.length;
            if (!options.keepSelection) {
                clearSelection(false);
            }
            render();
        }).catch(function (error) {
            setLoading(false);
            toast(error.message || '시놀로지 폴더를 읽지 못했습니다.');
            render();
        });
    }

    function setLoading(value) {
        state.busy = value;
        var line = $('countText');
        if (value && line) {
            line.textContent = '불러오는 중...';
        }
    }

    function reload(keepSelection) {
        if (state.tab === 'phone') {
            return loadPhone(state.phone.path, {keepSelection: keepSelection});
        }
        if (state.tab === 'nas') {
            return loadNas(state.nas.path, {keepSelection: keepSelection});
        }
        render();
        return Promise.resolve();
    }

    // ------------------------------------------------------------------ render

    function render() {
        renderHeader();
        renderChrome();
        if (state.tab === 'settings') {
            renderSettings();
            return;
        }
        if (state.tab === 'nas' && !state.connected) {
            renderConnect();
            return;
        }
        $('connect').classList.add('hidden');
        $('connect').innerHTML = '';
        $('pathbar').classList.remove('hidden');
        $('quick').classList.remove('hidden');
        $('toolbar').classList.remove('hidden');
        $('list').classList.remove('hidden');

        renderCrumbs();
        renderQuick();
        renderList();
        renderSelbar();
    }

    function renderHeader() {
        var dot = $('nasDot');
        var text = $('nasState');
        if (!dot || !text) {
            return;
        }
        if (state.connected) {
            dot.classList.add('on');
            var name = (state.nasStatus && state.nasStatus.serverName) || '';
            text.textContent = name ? name + ' 연결됨' : '시놀로지 연결됨';
        } else {
            dot.classList.remove('on');
            text.textContent = '시놀로지 연결 안 됨';
        }
        var tab = $('tab-nas');
        if (tab) {
            tab.classList.toggle('active', state.tab === 'nas');
        }
    }

    function applyViewChrome() {
        var list = $('list');
        list.className = state.view === 'list' ? 'list' : 'grid';
        $('btnView').textContent = state.view === 'list' ? '☰' : '▦';
        $('sort').value = state.sort;
        $('filters').querySelectorAll('button').forEach(function (button) {
            button.classList.toggle('on', button.dataset.filter === state.filter);
        });
        $('searchwrap').classList.toggle('on', state.searching);
        if (state.searching) {
            $('search').value = state.query;
        }
        var favTab = state.tab === 'fav';
        $('btnNewFolder').classList.toggle('hidden', favTab);
        $('filters').style.display = favTab ? 'none' : 'flex';
        $('sort').style.display = favTab ? 'none' : '';
    }

    function renderChrome() {
        var tabs = document.querySelectorAll('.tab');
        tabs.forEach(function (button) {
            button.classList.toggle('active', button.dataset.tab === state.tab);
        });
        var browsable = state.tab === 'phone' || state.tab === 'nas' || state.tab === 'fav';
        $('view-browse').classList.toggle('active', browsable);
        $('view-settings').classList.toggle('active', state.tab === 'settings');
    }

    function renderCrumbs() {
        var container = $('crumbs');
        var crumbs;
        if (state.tab === 'fav') {
            crumbs = [{label: '즐겨찾기', path: ''}];
        } else if (state.tab === 'nas') {
            crumbs = C.breadcrumbs(state.nas.path, '공유 폴더', '/');
        } else {
            var label = (state.local && state.local.roots && state.local.roots[0]
                && state.local.roots[0].label) || '내부 저장소';
            crumbs = C.breadcrumbs(state.phone.path, label, state.phoneRoot);
        }
        container.innerHTML = crumbs.map(function (crumb, index) {
            var last = index === crumbs.length - 1;
            return '<button class="crumb' + (last ? ' last' : '') + '" data-path="'
                + esc(crumb.path) + '">' + esc(crumb.label) + '</button>';
        }).join('');
        container.querySelectorAll('.crumb').forEach(function (button) {
            button.onclick = function () {
                var path = button.dataset.path;
                if (!path) {
                    return;
                }
                navigate(path);
            };
        });
        var disabled = state.tab === 'fav' || isAtRoot();
        $('btnUp').disabled = disabled;
        $('btnReload').onclick = function () {
            reload(false);
        };
        $('btnFav').textContent = isCurrentFavourite() ? '★' : '☆';
    }

    function isAtRoot() {
        if (state.tab === 'phone') {
            return C.normalizePath(state.phone.path) === C.normalizePath(state.phoneRoot);
        }
        if (state.tab === 'nas') {
            return C.normalizePath(state.nas.path) === '/';
        }
        return true;
    }

    function renderQuick() {
        var container = $('quick');
        if (state.tab !== 'phone' || !state.phone.quick || !state.phone.quick.length) {
            container.innerHTML = '';
            container.classList.add('hidden');
            return;
        }
        container.classList.remove('hidden');
        container.innerHTML = state.phone.quick.map(function (item) {
            return '<button class="chip" data-path="' + esc(item.path) + '">'
                + esc(item.label) + '</button>';
        }).join('');
        container.querySelectorAll('.chip').forEach(function (button) {
            button.onclick = function () {
                navigate(button.dataset.path);
            };
        });
    }

    function renderList() {
        var container = $('list');
        var entries;
        if (state.tab === 'fav') {
            entries = state.favs.map(function (fav) {
                return {
                    name: fav.label,
                    path: fav.path,
                    isDir: true,
                    kind: 'folder',
                    isFav: true,
                    side: fav.side
                };
            });
        } else {
            entries = currentEntries();
        }
        var filtered = C.filterEntries(entries, state.filter, state.query);
        var sorted = C.sortEntries(filtered, state.sort, 'asc');

        container.className = state.view === 'list' ? 'list' : 'grid';
        container.classList.toggle('selecting', state.selecting);
        container.innerHTML = sorted.map(function (entry, index) {
            return cellHtml(entry, index);
        }).join('');

        var countLine = $('countText');
        if (state.selecting && state.selection.length) {
            countLine.innerHTML = '<span class="sel">' + state.selection.length + '개 선택</span> · '
                + esc(C.summary(entries) || '항목 없음');
        } else if (state.tab === 'fav') {
            countLine.textContent = state.favs.length + '개의 즐겨찾기';
        } else {
            var summary = C.summary(entries);
            countLine.textContent = (summary || '항목 없음')
                + (state.query ? ' · "' + state.query + '" 검색' : '');
        }

        renderEmpty(sorted.length, entries);
        attachCellHandlers(sorted);
    }

    function cellHtml(entry, index) {
        var isNas = entry.side ? entry.side === 'nas' : isNasSide();
        var selected = state.selection.some(function (item) {
            return item.path === entry.path && item.name === entry.name;
        });
        var glyph = '📄';
        var thumb = '';
        if (entry.isFav) {
            glyph = entry.side === 'nas' ? '🖥️' : '📱';
        } else if (entry.isDir) {
            glyph = '📁';
        } else if (C.isImage(entry.name)) {
            glyph = '🖼️';
            var src = FM.mediaUrl(entry.path, isNas, true, isNas ? 'medium' : 320);
            if (src) {
                thumb = '<img loading="lazy" src="' + esc(src) + '" alt="" '
                    + 'onerror="this.remove()">';
            }
        } else if (C.isVideo(entry.name)) {
            glyph = '🎬';
            var vsrc = FM.mediaUrl(entry.path, isNas, true, isNas ? 'medium' : 320);
            if (vsrc) {
                thumb = '<img loading="lazy" src="' + esc(vsrc) + '" alt="" '
                    + 'onerror="this.remove()">';
            }
        } else if (C.isAudio(entry.name)) {
            glyph = '🎵';
        } else {
            var ext = C.extOf(entry.name);
            glyph = ext === 'pdf' ? '📕' : (ext === 'zip' || ext === 'tar' || ext === 'gz' ? '🗜️' : '📄');
        }

        var meta = entry.isFav
            ? '<span>' + esc(pathLabel(entry)) + '</span>'
            : '<span>' + (entry.isDir ? '폴더' : C.formatSize(entry.size)) + '</span>'
            + '<span>·</span><span>' + esc(C.formatDate(entry.mtime)) + '</span>';

        var tag = '';
        if (!entry.isDir && C.isVideo(entry.name)) {
            tag = '<div class="tag">▶ 영상</div>';
        } else if (!entry.isDir && C.isImage(entry.name)) {
            tag = '<div class="tag">사진</div>';
        }

        return '<div class="cell' + (selected ? ' selected' : '') + '" data-index="' + index + '">'
            + '<div class="thumb"><span class="glyph">' + glyph + '</span>' + thumb + tag + '</div>'
            + '<div class="check">✓</div>'
            + '<div class="info"><div class="name">' + esc(entry.name) + '</div>'
            + '<div class="meta">' + meta + '</div></div>'
            + '</div>';
    }

    function pathLabel(entry) {
        if (entry.side === 'nas') {
            return entry.path;
        }
        return C.displayPath(entry.path, state.phoneRoot);
    }

    function attachCellHandlers(sorted) {
        var container = $('list');
        var pressTimer = null;
        var longPressed = false;

        container.querySelectorAll('.cell').forEach(function (node) {
            var entry = sorted[Number(node.dataset.index)];
            if (!entry) {
                return;
            }
            node.addEventListener('pointerdown', function () {
                longPressed = false;
                pressTimer = setTimeout(function () {
                    longPressed = true;
                    if (!state.selecting) {
                        state.selecting = true;
                        node.classList.add('selected');
                        state.selection = [entry];
                        renderList();
                        renderSelbar();
                    }
                }, 430);
            });
            var cancel = function () {
                if (pressTimer) {
                    clearTimeout(pressTimer);
                    pressTimer = null;
                }
            };
            node.addEventListener('pointerup', cancel);
            node.addEventListener('pointercancel', cancel);
            node.addEventListener('pointermove', cancel);
            node.addEventListener('click', function () {
                if (longPressed) {
                    longPressed = false;
                    return;
                }
                handleCellTap(entry);
            });
        });
    }

    function handleCellTap(entry) {
        if (state.selecting) {
            toggleSelect(entry);
            return;
        }
        if (state.tab === 'fav' || entry.isFav) {
            openFavourite(entry);
            return;
        }
        if (entry.isDir) {
            navigate(entry.path);
            return;
        }
        openItem(entry);
    }

    function openFavourite(entry) {
        var side = entry.side || 'phone';
        state.tab = side;
        state.filter = 'all';
        state.query = '';
        if (side === 'nas') {
            if (!state.connected) {
                render();
                toast('시놀로지에 먼저 연결해 주세요.');
                return;
            }
            loadNas(entry.path);
        } else {
            loadPhone(entry.path);
        }
    }

    function toggleSelect(entry) {
        var index = -1;
        state.selection.forEach(function (item, i) {
            if (item.path === entry.path && item.name === entry.name) {
                index = i;
            }
        });
        if (index >= 0) {
            state.selection.splice(index, 1);
        } else {
            state.selection.push(entry);
        }
        if (!state.selection.length) {
            state.selecting = false;
        }
        state.selecting = state.selecting || state.selection.length > 0;
        renderList();
        renderSelbar();
    }

    function clearSelection(rerender) {
        state.selection = [];
        state.selecting = false;
        if (rerender !== false) {
            renderList();
            renderSelbar();
        }
    }

    function renderEmpty(visibleCount, allEntries) {
        var empty = $('empty');
        var list = $('list');
        if (state.tab === 'settings') {
            empty.classList.remove('on');
            return;
        }
        if (visibleCount > 0) {
            empty.classList.remove('on');
            list.classList.remove('hidden');
            return;
        }
        empty.classList.add('on');
        list.classList.add('hidden');
        var title = $('emptyTitle');
        var hint = $('emptyHint');
        var action = $('emptyAction');
        action.style.display = 'none';
        action.onclick = null;

        if (state.tab === 'fav') {
            title.textContent = '즐겨찾기가 없습니다';
            hint.textContent = '폴더를 열고 상단의 ☆를 누르면 이곳에 바로가기가 생깁니다.';
            return;
        }
        if (state.query || state.filter !== 'all') {
            title.textContent = '조건에 맞는 파일이 없습니다';
            hint.textContent = '검색어를 지우거나 필터를 ‘전체’로 바꿔 보세요.';
            return;
        }
        if (state.tab === 'phone' && state.local && state.local.allFiles === false) {
            title.textContent = '폰 파일에 접근할 수 없습니다';
            hint.textContent = 'Android 11부터는 ‘모든 파일 접근’을 허용해야 폴더를 읽을 수 있습니다.';
            action.style.display = '';
            action.textContent = '모든 파일 접근 허용';
            action.onclick = function () {
                FM.allowAllFiles();
            };
            return;
        }
        title.textContent = '폴더가 비어 있습니다';
        hint.textContent = (allEntries && allEntries.length)
            ? '필터를 바꾸면 다른 파일이 보입니다.'
            : (state.tab === 'nas'
                ? '시놀로지 폴더에 파일을 올리면 여기에 보입니다.'
                : '사진이나 영상을 옮기면 여기에 보입니다.');
    }

    function renderSelbar() {
        var bar = $('selbar');
        if (!state.selecting || !state.selection.length) {
            bar.classList.remove('on');
            bar.innerHTML = '';
            return;
        }
        var hasFile = state.selection.some(function (entry) {
            return !entry.isDir;
        });
        var single = state.selection.length === 1;
        var actions = [];
        if (isNasSide()) {
            actions.push({act: 'download', icon: '⬇️', label: '내 폰에 저장', primary: true});
            actions.push({act: 'copy', icon: '📋', label: '복사'});
            actions.push({act: 'move', icon: '📦', label: '이동'});
            actions.push({act: 'rename', icon: '✏️', label: '이름변경', disabled: !single});
            actions.push({act: 'open', icon: '↗️', label: '열기', disabled: !single});
            actions.push({act: 'delete', icon: '🗑️', label: '삭제', danger: true});
        } else {
            actions.push({act: 'upload', icon: '☁️', label: '시놀로지로', primary: true, disabled: !hasFile});
            actions.push({act: 'copy', icon: '📋', label: '복사'});
            actions.push({act: 'move', icon: '📦', label: '이동'});
            actions.push({act: 'rename', icon: '✏️', label: '이름변경', disabled: !single});
            actions.push({act: 'share', icon: '📤', label: '공유', disabled: !hasFile});
            actions.push({act: 'details', icon: 'ℹ️', label: '정보', disabled: !single});
            actions.push({act: 'delete', icon: '🗑️', label: '삭제', danger: true});
        }
        actions.push({act: 'close', icon: '✕', label: '선택 해제'});

        bar.innerHTML = actions.map(function (item) {
            return '<button class="act' + (item.primary ? ' primary' : '')
                + (item.danger ? ' danger' : '') + '" data-act="' + item.act + '"'
                + (item.disabled ? ' disabled' : '') + '>'
                + '<span class="ic">' + item.icon + '</span><span>' + item.label + '</span></button>';
        }).join('');
        bar.classList.add('on');
        bar.querySelectorAll('.act').forEach(function (button) {
            button.onclick = function () {
                runAction(button.dataset.act);
            };
        });
    }

    // -------------------------------------------------------------- navigation

    function navigate(path) {
        if (state.tab === 'fav') {
            return;
        }
        if (state.tab === 'nas') {
            loadNas(path);
        } else {
            loadPhone(path);
        }
    }

    function switchTab(tab) {
        if (state.tab === tab) {
            return;
        }
        state.tab = tab;
        state.filter = 'all';
        state.query = '';
        state.searching = false;
        clearSelection(false);
        applyViewChrome();
        renderChrome();
        renderHeader();
        if (tab === 'settings') {
            renderSettings();
            return;
        }
        if (tab === 'fav') {
            render();
            return;
        }
        if (tab === 'nas' && !state.connected) {
            renderConnect();
            return;
        }
        if (tab === 'phone' && !state.phone.path) {
            loadPhone(state.phoneRoot);
            return;
        }
        if (tab === 'nas' && !state.nas.entries.length) {
            loadNas(state.nas.path);
            return;
        }
        render();
    }

    // -------------------------------------------------------------- item action

    function openItem(entry) {
        if (C.isImage(entry.name)) {
            openViewer(entry);
        } else if (C.isVideo(entry.name)) {
            openPlayer(entry);
        } else if (C.isAudio(entry.name)) {
            openExternal(entry);
        } else {
            detailsDialog(entry);
        }
    }

    function openExternal(entry) {
        if (isNasSide()) {
            if (!state.connected) {
                toast('시놀로지에 먼저 연결해 주세요.');
                return;
            }
            var progress = progressDialog('외부 앱으로 여는 중');
            progress.update(-1, '파일을 내려받는 중...');
            FM.nas('openExternal', {path: entry.path}).then(function () {
                progress.close();
            }).catch(function (error) {
                progress.close();
                toast(error.message || '파일을 열지 못했습니다.');
            });
        } else {
            FM.local('open', {path: entry.path}).catch(function (error) {
                toast(error.message || '파일을 열지 못했습니다.');
            });
        }
    }

    function detailsDialog(entry) {
        var rows = [
            ['이름', entry.name],
            ['위치', isNasSide() ? C.parentOf(entry.path) : C.parentOf(entry.path)],
            ['종류', entry.isDir ? '폴더' : (C.isImage(entry.name) ? '사진'
                : C.isVideo(entry.name) ? '동영상' : (C.extOf(entry.name).toUpperCase() || '파일'))],
            ['크기', entry.isDir ? '-' : C.formatSize(entry.size)],
            ['수정한 날짜', C.formatDate(entry.mtime)]
        ];
        openModal(
            '<h3>' + esc(entry.name) + '</h3>'
            + '<div class="body">' + rows.map(function (row) {
                return '<div class="stat-row"><span>' + esc(row[0]) + '</span><b>'
                    + esc(row[1] || '-') + '</b></div>';
            }).join('') + '</div>'
            + '<div class="foot"><button class="btn ghost" id="dlgCancel">닫기</button></div>'
        );
        $('dlgCancel').onclick = closeModal;
    }

    // ------------------------------------------------------------ action bar

    function runAction(action) {
        switch (action) {
            case 'upload':
                uploadToNas();
                break;
            case 'download':
                downloadToPhone();
                break;
            case 'copy':
                copyOrMove(false);
                break;
            case 'move':
                copyOrMove(true);
                break;
            case 'rename':
                renameSelection();
                break;
            case 'delete':
                deleteSelection();
                break;
            case 'share':
                shareSelection();
                break;
            case 'open':
                if (state.selection.length === 1) {
                    openItem(state.selection[0]);
                }
                break;
            case 'details':
                if (state.selection.length === 1) {
                    detailsDialog(state.selection[0]);
                }
                break;
            case 'close':
                clearSelection();
                break;
            default:
                break;
        }
    }

    function uploadToNas() {
        if (!state.connected) {
            toast('시놀로지에 먼저 연결해 주세요.');
            switchTab('nas');
            return;
        }
        var files = state.selection.filter(function (entry) {
            return !entry.isDir;
        });
        if (!files.length) {
            toast('업로드할 파일을 선택해 주세요. (폴더는 아직 지원하지 않습니다)');
            return;
        }
        pickFolder('nas', state.nas.path || '/', '올릴 시놀로지 폴더 선택').then(function (dest) {
            if (!dest) {
                return;
            }
            var progress = progressDialog('시놀로지로 올리는 중');
            progress.update(0, '시작...');
            FM.nas('upload', {
                localPaths: files.map(function (entry) {
                    return entry.path;
                }),
                destDir: dest,
                overwrite: false
            }, function (percent, message) {
                progress.update(percent, message);
            }).then(function (res) {
                progress.close();
                var failed = (res.failed || []).length;
                if (failed) {
                    toast(res.count + '개 완료, ' + failed + '개 실패');
                } else {
                    toast(res.count + '개를 ' + dest + '에 올렸습니다.');
                }
                clearSelection(false);
                if (state.tab === 'nas') {
                    loadNas(dest);
                } else {
                    reload(false);
                }
                if (res.count && !failed) {
                    // 업로드는 복사다. 이동으로 마무리할지는 사용자가 정한다.
                    confirmDialog('폰 원본도 지울까요?',
                        res.count + '개를 시놀로지에 올렸습니다.\n'
                        + '폰의 원본을 지우면 ‘이동’과 같습니다.', '원본도 지우기', true)
                        .then(function (yes) {
                            if (!yes) {
                                return;
                            }
                            FM.local('delete', {
                                paths: files.map(function (entry) {
                                    return entry.path;
                                })
                            }).then(function () {
                                toast('폰 원본을 지웠습니다.');
                                if (state.tab === 'phone') {
                                    loadPhone(state.phone.path);
                                }
                            }).catch(function (error) {
                                toast(error.message || '원본을 지우지 못했습니다.');
                            });
                        });
                }
            }).catch(function (error) {
                progress.close();
                toast(error.message || '업로드에 실패했습니다.');
            });
        });
    }

    function downloadToPhone() {
        if (!state.selection.length) {
            return;
        }
        var start = state.phone.path || state.phoneRoot;
        pickFolder('phone', start, '저장할 폰 폴더 선택').then(function (dest) {
            if (!dest) {
                return;
            }
            var items = state.selection.slice();
            var progress = progressDialog('내 폰으로 내려받는 중');
            progress.update(0, '시작...');
            var index = 0;
            var saved = 0;
            var failed = 0;

            function next() {
                if (index >= items.length) {
                    progress.close();
                    toast(saved + '개를 ' + dest + '에 저장했습니다.'
                        + (failed ? ' (' + failed + '개 실패)' : ''));
                    clearSelection(false);
                    if (state.tab === 'phone') {
                        loadPhone(dest);
                    } else {
                        render();
                    }
                    return;
                }
                var entry = items[index++];
                progress.update(0, entry.name + ' (' + index + '/' + items.length + ')');
                FM.nas('download', {path: entry.path, destDir: dest}, function (percent, message) {
                    progress.update(percent, entry.name + ' · ' + (message || ''));
                }).then(function () {
                    saved++;
                    next();
                }).catch(function () {
                    failed++;
                    next();
                });
            }

            next();
        });
    }

    function copyOrMove(move) {
        if (!state.selection.length) {
            return;
        }
        var label = move ? '이동' : '복사';
        pickFolder(state.tab, currentPath(), label + '할 위치 선택').then(function (dest) {
            if (!dest) {
                return;
            }
            var paths = state.selection.map(function (entry) {
                return entry.path;
            });
            if (state.tab === 'nas') {
                var progress = progressDialog(label + '하는 중');
                progress.update(-1, '시놀로지에서 처리 중...');
                FM.nas('copyMove', {
                    paths: paths,
                    destDir: dest,
                    move: move,
                    overwrite: false
                }, function (percent, message) {
                    progress.update(percent, message);
                }).then(function () {
                    progress.close();
                    toast(label + ' 완료: ' + dest);
                    clearSelection(false);
                    loadNas(dest);
                }).catch(function (error) {
                    progress.close();
                    toast(error.message || label + '하지 못했습니다.');
                });
            } else {
                FM.local('copyMove', {
                    paths: paths,
                    destDir: dest,
                    move: move,
                    overwrite: false
                }).then(function (res) {
                    var failed = (res.failed || []).length;
                    toast(failed ? (res.count + '개 완료, ' + failed + '개 실패')
                        : (res.count + '개 ' + label + ' 완료'));
                    clearSelection(false);
                    loadPhone(dest);
                }).catch(function (error) {
                    toast(error.message || label + '하지 못했습니다.');
                });
            }
        });
    }

    function renameSelection() {
        if (state.selection.length !== 1) {
            return;
        }
        var entry = state.selection[0];
        promptDialog({
            title: '이름 변경',
            value: entry.name,
            okText: '변경'
        }).then(function (value) {
            if (value === null) {
                return;
            }
            var name = C.sanitizeName(value);
            if (!name || name === entry.name) {
                return;
            }
            var target = C.joinPath(C.parentOf(entry.path), name);
            if (state.tab === 'nas') {
                FM.nas('rename', {path: entry.path, newName: name}).then(function () {
                    toast('이름을 바꿨습니다.');
                    clearSelection(false);
                    loadNas(state.nas.path);
                }).catch(function (error) {
                    toast(error.message || '이름을 바꾸지 못했습니다.');
                });
            } else {
                FM.local('rename', {path: entry.path, newName: name}).then(function () {
                    toast('이름을 바꿨습니다.');
                    clearSelection(false);
                    loadPhone(state.phone.path);
                }).catch(function (error) {
                    toast(error.message || '이름을 바꾸지 못했습니다.');
                });
            }
            void target;
        });
    }

    function deleteSelection() {
        if (!state.selection.length) {
            return;
        }
        var count = state.selection.length;
        var names = state.selection.slice(0, 3).map(function (entry) {
            return entry.name;
        }).join(', ') + (count > 3 ? ' 외 ' + (count - 3) + '개' : '');
        confirmDialog('삭제할까요?', names + '\n\n삭제한 파일은 휴지통 없이 바로 지워집니다.', '삭제', true)
            .then(function (yes) {
                if (!yes) {
                    return;
                }
                var paths = state.selection.map(function (entry) {
                    return entry.path;
                });
                var request = state.tab === 'nas'
                    ? FM.nas('delete', {paths: paths})
                    : FM.local('delete', {paths: paths});
                request.then(function (res) {
                    var failed = (res.failed || []).length;
                    toast(failed ? (count - failed) + '개 삭제, ' + failed + '개 실패'
                        : count + '개를 삭제했습니다.');
                    clearSelection(false);
                    reload(false);
                }).catch(function (error) {
                    toast(error.message || '삭제하지 못했습니다.');
                });
            });
    }

    function shareSelection() {
        var paths = state.selection.filter(function (entry) {
            return !entry.isDir;
        }).map(function (entry) {
            return entry.path;
        });
        if (!paths.length) {
            toast('공유할 파일을 선택해 주세요.');
            return;
        }
        FM.local('share', {paths: paths}).catch(function (error) {
            toast(error.message || '공유하지 못했습니다.');
        });
    }

    function newFolder() {
        promptDialog({
            title: '새 폴더',
            value: '새 폴더',
            okText: '만들기'
        }).then(function (value) {
            if (value === null) {
                return;
            }
            var name = C.sanitizeName(value);
            if (!name) {
                return;
            }
            var target = C.joinPath(currentPath(), name);
            var request = state.tab === 'nas'
                ? FM.nas('mkdir', {path: target})
                : FM.local('mkdir', {path: target});
            request.then(function () {
                toast('폴더를 만들었습니다.');
                reload(false);
            }).catch(function (error) {
                toast(error.message || '폴더를 만들지 못했습니다.');
            });
        });
    }

    // ------------------------------------------------------------ folder picker

    function pickFolder(side, startPath, title) {
        return new Promise(function (resolve) {
            var current = C.normalizePath(startPath || (side === 'nas' ? '/'
                : (state.phoneRoot || '/')));
            var listing = [];

            function draw() {
                openModal(
                    '<h3>' + esc(title || '폴더 선택') + '</h3>'
                    + '<div class="body" id="pickPath" style="font-size:12.5px">'
                    + esc(displayOf(current, side)) + '</div>'
                    + '<div class="pick-list" id="pickList"><div class="pick-item">불러오는 중...</div></div>'
                    + '<div class="foot"><button class="btn ghost" id="pickCancel">취소</button>'
                    + '<button class="btn" id="pickChoose">이 폴더 선택</button></div>'
                );
                $('pickCancel').onclick = function () {
                    closeModal();
                    resolve(null);
                };
                $('pickChoose').onclick = function () {
                    closeModal();
                    resolve(current);
                };
                list(current);
            }

            function displayOf(path, sideName) {
                if (sideName === 'nas') {
                    return path;
                }
                return C.displayPath(path, state.phoneRoot);
            }

            function list(path) {
                var request = side === 'nas'
                    ? FM.nas('list', {path: path})
                    : FM.local('list', {path: path});
                request.then(function (res) {
                    current = res.path || path;
                    $('pickPath').textContent = displayOf(current, side);
                    var dirs = (res.entries || []).filter(function (entry) {
                        return entry.isDir;
                    }).sort(function (a, b) {
                        return a.name.localeCompare(b.name, 'ko', {numeric: true});
                    });
                    var parent = C.parentOf(current);
                    var html = '';
                    if (parent !== current && (side === 'nas' || !isPhoneRoot(current))) {
                        html += '<div class="pick-item" data-path="' + esc(parent)
                            + '"><span class="glyph">↩️</span><span class="grow">상위 폴더</span></div>';
                    }
                    html += dirs.map(function (dir) {
                        return '<div class="pick-item" data-path="' + esc(dir.path)
                            + '"><span class="glyph">📁</span><span class="grow">'
                            + esc(dir.name) + '</span><span style="color:#9AA6B8">›</span></div>';
                    }).join('');
                    if (!html) {
                        html = '<div class="pick-item"><span class="grow" style="color:#9AA6B8">'
                            + '하위 폴더가 없습니다</span></div>';
                    }
                    $('pickList').innerHTML = html;
                    $('pickList').querySelectorAll('.pick-item[data-path]').forEach(function (node) {
                        node.onclick = function () {
                            list(node.dataset.path);
                        };
                    });
                }).catch(function (error) {
                    $('pickList').innerHTML = '<div class="pick-item"><span class="grow" style="color:#E5484D">'
                        + esc(error.message || '폴더를 열지 못했습니다.') + '</span></div>';
                });
            }

            function isPhoneRoot(path) {
                return C.normalizePath(path) === C.normalizePath(state.phoneRoot);
            }

            draw();
        });
    }

    // ----------------------------------------------------------------- viewers

    function openViewer(entry) {
        var side = isNasSide();
        var images = C.filterEntries(currentEntries(), 'image', '');
        var items = C.sortEntries(images, state.sort, 'asc');
        var index = items.findIndex(function (item) {
            return item.path === entry.path;
        });
        if (index < 0) {
            items = [entry];
            index = 0;
        }
        state.viewer.items = items;
        state.viewer.side = side;
        state.viewer.index = index;
        $('viewer').classList.add('on');
        $('vSave').style.display = side ? '' : 'none';
        showImage(index);
    }

    function showImage(index) {
        var items = state.viewer.items;
        if (!items.length) {
            return;
        }
        if (index < 0) {
            index = 0;
        }
        if (index >= items.length) {
            index = items.length - 1;
        }
        state.viewer.index = index;
        var entry = items[index];
        var url = FM.mediaUrl(entry.path, state.viewer.side, false);
        var img = $('vImg');
        resetZoom();
        img.src = url;
        $('vTitle').textContent = entry.name;
        $('vCount').textContent = (index + 1) + ' / ' + items.length;
        $('vPrev').disabled = index <= 0;
        $('vNext').disabled = index >= items.length - 1;
    }

    function resetZoom() {
        state.viewer.scale = 1;
        state.viewer.tx = 0;
        state.viewer.ty = 0;
        applyTransform();
    }

    function applyTransform() {
        var img = $('vImg');
        img.style.transform = 'translate(' + state.viewer.tx + 'px,' + state.viewer.ty
            + 'px) scale(' + state.viewer.scale + ')';
        $('vZoom').textContent = state.viewer.scale.toFixed(1) + 'x';
    }

    function closeViewer() {
        $('viewer').classList.remove('on');
        $('vImg').removeAttribute('src');
        resetZoom();
    }

    function openPlayer(entry) {
        state.player.entry = entry;
        state.player.side = isNasSide();
        $('pTitle').textContent = entry.name;
        $('pSave').style.display = state.player.side ? '' : 'none';
        $('player').classList.add('on');
        $('pLoading').textContent = '불러오는 중...';
        $('pLoading').style.display = '';
        var video = $('playerVideo');
        video.pause();
        video.removeAttribute('src');
        video.load();

        if (!state.player.side) {
            playUrl(FM.mediaUrl(entry.path, false, false));
            return;
        }
        // DSM's Download API has no Range support, so a NAS video is cached once and then
        // played from the phone: seeking works, and re-opening is instant.
        var cacheDir = (state.local && state.local.videoCacheDir) || '';
        findCached(cacheDir, entry).then(function (cached) {
            if (cached) {
                playUrl(FM.mediaUrl(cached, false, false));
                return;
            }
            var progress = progressDialog('동영상 준비 중');
            progress.update(0, '시놀로지에서 내려받는 중...');
            FM.nas('download', {path: entry.path, destDir: cacheDir}, function (percent, message) {
                progress.update(percent, message);
            }).then(function (res) {
                progress.close();
                if ($('player').classList.contains('on')) {
                    playUrl(FM.mediaUrl(res.savedPath, false, false));
                }
            }).catch(function (error) {
                progress.close();
                $('pLoading').textContent = error.message || '동영상을 열지 못했습니다.';
            });
        });
    }

    function findCached(dir, entry) {
        if (!dir) {
            return Promise.resolve('');
        }
        return FM.local('list', {path: dir}).then(function (res) {
            var hit = (res.entries || []).filter(function (item) {
                return item.name === entry.name && Math.abs(item.size - entry.size) < 1024;
            })[0];
            return hit ? hit.path : '';
        }).catch(function () {
            return '';
        });
    }

    function playUrl(url) {
        var video = $('playerVideo');
        $('pLoading').style.display = '';
        video.oncanplay = function () {
            $('pLoading').style.display = 'none';
            video.play().catch(function () {
                // user can press play
            });
        };
        video.onerror = function () {
            $('pLoading').textContent = '재생할 수 없는 형식입니다. ↗ 버튼으로 외부 플레이어에서 열어 보세요.';
        };
        video.src = url;
        video.load();
    }

    function closePlayer() {
        var video = $('playerVideo');
        try {
            video.pause();
            video.removeAttribute('src');
            video.load();
        } catch (e) {
            // already detached
        }
        $('player').classList.remove('on');
    }

    // --------------------------------------------------------------- favourites

    function isCurrentFavourite() {
        if (state.tab === 'fav') {
            return false;
        }
        var path = C.normalizePath(currentPath());
        return state.favs.some(function (fav) {
            return fav.side === state.tab && C.normalizePath(fav.path) === path;
        });
    }

    function toggleFavourite() {
        if (state.tab === 'fav') {
            return;
        }
        var path = C.normalizePath(currentPath());
        var side = state.tab;
        var existing = state.favs.findIndex(function (fav) {
            return fav.side === side && C.normalizePath(fav.path) === path;
        });
        if (existing >= 0) {
            state.favs.splice(existing, 1);
            toast('즐겨찾기에서 뺐습니다.');
        } else {
            var label = C.nameOf(path);
            if (!label || (state.tab === 'phone'
                && C.normalizePath(path) === C.normalizePath(state.phoneRoot))) {
                label = (state.tab === 'phone' && state.local && state.local.roots
                    && state.local.roots[0] && state.local.roots[0].label)
                    || (state.tab === 'nas' ? '공유 폴더' : '내부 저장소');
            }
            state.favs.push({side: side, path: path, label: label});
            toast('즐겨찾기에 넣었습니다.');
        }
        writeFavs();
        renderCrumbs();
        if (state.tab === 'fav') {
            render();
        }
    }

    function removeFavourite(entry) {
        state.favs = state.favs.filter(function (fav) {
            return !(fav.path === entry.path && fav.side === entry.side);
        });
        writeFavs();
        render();
    }

    // ---------------------------------------------------------------- settings

    function renderConnect() {
        $('connect').classList.remove('hidden');
        $('pathbar').classList.add('hidden');
        $('quick').classList.add('hidden');
        $('toolbar').classList.add('hidden');
        $('list').classList.add('hidden');
        $('empty').classList.remove('on');
        $('selbar').classList.remove('on');
        var last = state.nasStatus || {};
        $('connect').innerHTML =
            '<div class="card">'
            + '<h2>🖥️ 시놀로지 NAS 연결</h2>'
            + '<div class="desc">DSM 계정으로 로그인하면 File Station의 공유 폴더를 그대로 탐색하고, '
            + '폰 사진을 바로 올릴 수 있습니다.</div>'
            + '<div class="field"><label>서버 주소</label><input id="cUrl" inputmode="url" '
            + 'placeholder="https://mynas.synology.me:5001" value="' + esc(last.lastUrl || '') + '"></div>'
            + '<div class="field"><label>아이디</label><input id="cId" autocomplete="username" '
            + 'value="' + esc(last.lastAccount || '') + '"></div>'
            + '<div class="field"><label>비밀번호</label>'
            + '<input id="cPw" type="password" autocomplete="current-password"></div>'
            + '<div class="field"><label>OTP 6자리 (2단계 인증을 쓰는 경우)</label>'
            + '<input id="cOtp" inputmode="numeric" maxlength="6" placeholder="123456"></div>'
            + '<div class="switch"><div>자체 서명 인증서 허용'
            + '<small>시놀로지 기본 인증서(https)일 때 켜 주세요.</small></div>'
            + '<div class="toggle ' + (state.settings.ignoreSsl ? 'on' : '') + '" id="cSsl"></div></div>'
            + '<div class="switch"><div>다음에 자동 로그인'
            + '<small>비밀번호는 폰 안의 보안 키로만 풀립니다.</small></div>'
            + '<div class="toggle ' + (state.settings.autoLogin ? 'on' : '') + '" id="cAuto"></div></div>'
            + '<div class="row" style="margin-top:14px">'
            + '<button class="btn" id="cLogin">로그인</button>'
            + '<button class="btn ghost" id="cSettings">설정 열기</button></div>'
            + '</div>';
        var sslOn = state.settings.ignoreSsl;
        var autoOn = state.settings.autoLogin;
        $('cSsl').onclick = function () {
            sslOn = !sslOn;
            $('cSsl').classList.toggle('on', sslOn);
            state.settings.ignoreSsl = sslOn;
            writeSettings();
        };
        $('cAuto').onclick = function () {
            autoOn = !autoOn;
            $('cAuto').classList.toggle('on', autoOn);
            state.settings.autoLogin = autoOn;
            writeSettings();
        };
        $('cSettings').onclick = function () {
            switchTab('settings');
        };
        $('cLogin').onclick = function () {
            var url = $('cUrl').value.trim();
            var id = $('cId').value.trim();
            var pw = $('cPw').value;
            var otp = ($('cOtp').value || '').trim();
            if (!url || !id) {
                toast('서버 주소와 아이디를 입력해 주세요.');
                return;
            }
            doLogin(url, id, pw, otp, sslOn, autoOn).catch(function () {
                // doLogin already showed the reason
            });
        };
    }

    function doLogin(url, id, pw, otp, ssl, auto) {
        var progress = progressDialog('시놀로지에 로그인하는 중');
        progress.update(-1, url);
        return FM.nas('login', {
            url: url,
            username: id,
            password: pw,
            otp: otp,
            ignoreSsl: ssl,
            save: true,
            autoLogin: auto
        }).then(function (res) {
            progress.close();
            state.connected = true;
            state.nasStatus = res;
            state.nas.path = '/';
            toast('연결되었습니다.');
            renderHeader();
            loadNas('/');
            return res;
        }).catch(function (error) {
            progress.close();
            if (error.code === 403 || error.code === 404 || error.code === 406) {
                toast('2단계 인증 번호가 필요합니다. OTP 6자리를 입력해 주세요.');
            } else {
                toast(error.message || '로그인하지 못했습니다.');
            }
            throw error;
        });
    }

    function renderSettings() {
        var local = state.local || {};
        var connected = state.connected;
        var sdk = local.sdk || 0;
        var s = state.settings;
        var phoneCache = FM.phoneCacheSize();
        var nasCache = FM.nasCacheSize();
        var servers = (state.nasStatus && state.nasStatus.servers) || [];

        var html = '';
        html += '<div class="card">'
            + '<h2>🖥️ 시놀로지 NAS</h2>'
            + '<div class="desc">' + (connected
                ? '지금 연결됨 · ' + esc((state.nasStatus && state.nasStatus.baseUrl) || '')
                + (state.nasStatus && state.nasStatus.account ? ' · ' + esc(state.nasStatus.account) : '')
                : 'DSM File Station 계정으로 로그인하세요.') + '</div>'
            + (servers.length ? servers.map(function (server) {
                return '<div class="server-chip"><span>🖥️</span><div class="grow">'
                    + esc(server.url) + '<small>' + esc(server.account || '') + '</small></div>'
                    + '<button class="btn ghost" style="height:32px;font-size:12px" data-use="'
                    + esc(server.url) + '" data-account="' + esc(server.account || '') + '">사용</button>'
                    + '</div>';
            }).join('') : '')
            + (connected
                ? '<div class="row"><button class="btn ghost" id="setReconnect">다시 연결</button>'
                + '<button class="btn danger" id="setLogout">로그아웃</button></div>'
                : '<div class="row"><button class="btn" id="setConnect">연결 화면 열기</button></div>')
            + '<div class="row" style="margin-top:8px">'
            + '<button class="btn line" id="setAutoLogin">자동 로그인 ' + (s.autoLogin ? '켜짐' : '꺼짐') + '</button>'
            + '<button class="btn line" id="setSsl">인증서 무시 ' + (s.ignoreSsl ? '켜짐' : '꺼짐') + '</button>'
            + '</div></div>';

        html += '<div class="card">'
            + '<h2>📱 폰 저장소</h2>'
            + '<div class="desc">Android ' + sdk + ' · '
            + (local.allFiles ? '모든 파일 접근 허용됨' : '모든 파일 접근이 필요합니다') + '</div>'
            + (local.storage
                ? '<div class="stat-row"><span>내부 저장소</span><b>'
                + esc(C.formatSize(local.storage.free)) + ' 남음 / '
                + esc(C.formatSize(local.storage.total)) + '</b></div>'
                : '')
            + '<div class="row" style="margin-top:12px">'
            + '<button class="btn ' + (local.allFiles ? 'ghost' : '') + '" id="setAllow">모든 파일 접근 허용</button>'
            + '<button class="btn line" id="setMediaPerm">사진/영상 권한</button>'
            + '</div></div>';

        html += '<div class="card">'
            + '<h2>👀 보기 설정</h2>'
            + '<div class="switch"><div>기본 보기<small>그리드(갤러리) 또는 목록</small></div>'
            + '<div class="seg"><button data-view="grid" class="' + (state.view === 'grid' ? 'on' : '')
            + '">▦ 그리드</button><button data-view="list" class="'
            + (state.view === 'list' ? 'on' : '') + '">☰ 목록</button></div></div>'
            + '<div class="switch"><div>숨김 파일 보기<small>이름이 . 으로 시작하는 파일</small></div>'
            + '<div class="toggle ' + (s.showHidden ? 'on' : '') + '" id="setHidden"></div></div>'
            + '</div>';

        html += '<div class="card">'
            + '<h2>🗄️ 캐시</h2>'
            + '<div class="desc">썸네일은 폰 안에만 저장됩니다. 지워도 사진은 그대로입니다.</div>'
            + '<div class="stat-row"><span>폰 썸네일</span><b>' + esc(C.formatSize(phoneCache)) + '</b></div>'
            + '<div class="stat-row"><span>시놀로지 썸네일</span><b>' + esc(C.formatSize(nasCache)) + '</b></div>'
            + '<button class="btn ghost" id="setClearCache" style="margin-top:12px;width:100%">썸네일 캐시 비우기</button>'
            + '</div>';

        html += '<div class="card">'
            + '<h2>ℹ️ 앱 정보</h2>'
            + '<div class="stat-row"><span>버전</span><b>1.0</b></div>'
            + '<div class="stat-row"><span>패키지</span><b>com.choi.filemanager</b></div>'
            + '<div class="desc" style="margin-top:10px">시놀로지 NAS와 내 폰의 사진·영상을 한 화면에서 '
            + '보고 정리하는 앱입니다. 폰 ↔ 시놀로지 양방향 복사/이동을 지원합니다.</div>'
            + '</div>';

        $('settingsBody').innerHTML = html;

        var useButtons = $('settingsBody').querySelectorAll('[data-use]');
        useButtons.forEach(function (button) {
            button.onclick = function () {
                switchTab('nas');
                renderConnect();
                $('cUrl').value = button.dataset.use;
                $('cId').value = button.dataset.account || '';
                $('cPw').focus();
            };
        });
        var viewButtons = $('settingsBody').querySelectorAll('[data-view]');
        viewButtons.forEach(function (button) {
            button.onclick = function () {
                state.view = button.dataset.view;
                localStorage.setItem(LS.view, state.view);
                applyViewChrome();
                renderSettings();
            };
        });
        $('setConnect') && ($('setConnect').onclick = function () {
            switchTab('nas');
        });
        $('setReconnect') && ($('setReconnect').onclick = function () {
            switchTab('nas');
            renderConnect();
        });
        $('setLogout') && ($('setLogout').onclick = function () {
            FM.nas('logout').then(function () {
                state.connected = false;
                state.nas.entries = [];
                renderHeader();
                renderSettings();
                toast('로그아웃했습니다.');
            }).catch(function () {
                toast('로그아웃하지 못했습니다.');
            });
        });
        $('setAutoLogin').onclick = function () {
            state.settings.autoLogin = !state.settings.autoLogin;
            writeSettings();
            renderSettings();
        };
        $('setSsl').onclick = function () {
            state.settings.ignoreSsl = !state.settings.ignoreSsl;
            writeSettings();
            renderSettings();
        };
        $('setAllow').onclick = function () {
            FM.allowAllFiles();
        };
        $('setMediaPerm').onclick = function () {
            FM.requestMedia();
        };
        $('setHidden').onclick = function () {
            state.settings.showHidden = !state.settings.showHidden;
            writeSettings();
            renderSettings();
            if (state.tab === 'phone' && state.phone.path) {
                loadPhone(state.phone.path);
            }
        };
        $('setClearCache').onclick = function () {
            FM.clearCaches();
            toast('썸네일 캐시를 비웠습니다.');
            setTimeout(renderSettings, 400);
        };
    }

    // -------------------------------------------------------------- chrome bind

    function bindChrome() {
        document.querySelectorAll('.tab').forEach(function (button) {
            button.onclick = function () {
                switchTab(button.dataset.tab);
            };
        });
        $('btnUp').onclick = function () {
            if (state.tab === 'fav') {
                return;
            }
            var parent = C.parentOf(currentPath());
            if (state.tab === 'phone' && C.normalizePath(parent).length
                < C.normalizePath(state.phoneRoot).length) {
                parent = state.phoneRoot;
            }
            navigate(parent);
        };
        $('btnFav').onclick = toggleFavourite;
        $('btnView').onclick = function () {
            state.view = state.view === 'grid' ? 'list' : 'grid';
            localStorage.setItem(LS.view, state.view);
            applyViewChrome();
            renderList();
        };
        $('btnNewFolder').onclick = newFolder;
        $('btnSearch').onclick = function () {
            state.searching = !state.searching;
            if (!state.searching) {
                state.query = '';
            }
            applyViewChrome();
            if (state.searching) {
                setTimeout(function () {
                    $('search').focus();
                }, 50);
            }
            renderList();
        };
        $('search').addEventListener('input', function () {
            state.query = $('search').value;
            renderList();
        });
        $('sort').onchange = function () {
            state.sort = $('sort').value;
            localStorage.setItem(LS.sort, state.sort);
            renderList();
        };
        $('filters').querySelectorAll('button').forEach(function (button) {
            button.onclick = function () {
                state.filter = button.dataset.filter;
                $('filters').querySelectorAll('button').forEach(function (other) {
                    other.classList.toggle('on', other === button);
                });
                renderList();
            };
        });
        bindViewer();
        bindPlayer();
        $('modal').onclick = function (event) {
            if (event.target === $('modal')) {
                closeModal();
            }
        };
    }

    function bindViewer() {
        $('vClose').onclick = closeViewer;
        $('vPrev').onclick = function () {
            showImage(state.viewer.index - 1);
        };
        $('vNext').onclick = function () {
            showImage(state.viewer.index + 1);
        };
        $('vZoom').onclick = function () {
            state.viewer.scale = state.viewer.scale > 1 ? 1 : 2.5;
            state.viewer.tx = 0;
            state.viewer.ty = 0;
            applyTransform();
        };
        $('vExternal').onclick = function () {
            var entry = state.viewer.items[state.viewer.index];
            if (!entry) {
                return;
            }
            if (state.viewer.side) {
                openExternal(entry);
            } else {
                FM.local('open', {path: entry.path}).catch(function (error) {
                    toast(error.message || '열지 못했습니다.');
                });
            }
        };
        $('vSave').onclick = function () {
            var entry = state.viewer.items[state.viewer.index];
            if (!entry || !state.viewer.side) {
                return;
            }
            pickFolder('phone', state.phone.path || state.phoneRoot, '저장할 폰 폴더').then(function (dest) {
                if (!dest) {
                    return;
                }
                var progress = progressDialog('사진 저장 중');
                FM.nas('download', {path: entry.path, destDir: dest}, function (percent, message) {
                    progress.update(percent, message);
                }).then(function (res) {
                    progress.close();
                    toast(res.name + ' 저장 완료');
                }).catch(function (error) {
                    progress.close();
                    toast(error.message || '저장하지 못했습니다.');
                });
            });
        };

        var stage = $('vStage');
        var startX = 0;
        var startY = 0;
        var startTime = 0;
        var startDistance = 0;
        var startScale = 1;
        var startTx = 0;
        var startTy = 0;
        var lastTap = 0;

        stage.addEventListener('touchstart', function (event) {
            if (event.touches.length === 2) {
                startDistance = distance(event.touches);
                startScale = state.viewer.scale;
                startTx = state.viewer.tx;
                startTy = state.viewer.ty;
            } else if (event.touches.length === 1) {
                startX = event.touches[0].clientX;
                startY = event.touches[0].clientY;
                startTime = Date.now();
                startTx = state.viewer.tx;
                startTy = state.viewer.ty;
                var now = Date.now();
                if (now - lastTap < 280) {
                    state.viewer.scale = state.viewer.scale > 1 ? 1 : 2.5;
                    state.viewer.tx = 0;
                    state.viewer.ty = 0;
                    applyTransform();
                    lastTap = 0;
                } else {
                    lastTap = now;
                }
            }
        }, {passive: true});

        stage.addEventListener('touchmove', function (event) {
            if (event.touches.length === 2) {
                event.preventDefault();
                var ratio = distance(event.touches) / (startDistance || 1);
                state.viewer.scale = Math.max(1, Math.min(5, startScale * ratio));
                applyTransform();
            } else if (event.touches.length === 1 && state.viewer.scale > 1) {
                event.preventDefault();
                var dx = event.touches[0].clientX - startX;
                var dy = event.touches[0].clientY - startY;
                state.viewer.tx = startTx + dx;
                state.viewer.ty = startTy + dy;
                applyTransform();
            }
        }, {passive: false});

        stage.addEventListener('touchend', function (event) {
            if (event.touches.length > 0) {
                return;
            }
            if (state.viewer.scale > 1) {
                return;
            }
            var dx = (event.changedTouches[0].clientX - startX);
            var dy = (event.changedTouches[0].clientY - startY);
            var elapsed = Date.now() - startTime;
            if (Math.abs(dx) > 60 && Math.abs(dy) < 90 && elapsed < 700) {
                if (dx < 0) {
                    showImage(state.viewer.index + 1);
                } else {
                    showImage(state.viewer.index - 1);
                }
            }
        }, {passive: true});

        function distance(touches) {
            var dx = touches[0].clientX - touches[1].clientX;
            var dy = touches[0].clientY - touches[1].clientY;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    function bindPlayer() {
        $('pClose').onclick = closePlayer;
        $('pExternal').onclick = function () {
            var entry = state.player.entry;
            if (!entry) {
                return;
            }
            openExternal(entry);
        };
        $('pSave').onclick = function () {
            var entry = state.player.entry;
            if (!entry || !state.player.side) {
                return;
            }
            pickFolder('phone', state.phone.path || state.phoneRoot, '저장할 폰 폴더').then(function (dest) {
                if (!dest) {
                    return;
                }
                var progress = progressDialog('동영상 저장 중');
                FM.nas('download', {path: entry.path, destDir: dest}, function (percent, message) {
                    progress.update(percent, message);
                }).then(function (res) {
                    progress.close();
                    toast(res.name + ' 저장 완료');
                }).catch(function (error) {
                    progress.close();
                    toast(error.message || '저장하지 못했습니다.');
                });
            });
        };
    }

    // -------------------------------------------------------------- back button

    window.fmHandleBack = function () {
        if ($('player').classList.contains('on')) {
            closePlayer();
            return true;
        }
        if ($('viewer').classList.contains('on')) {
            closeViewer();
            return true;
        }
        if ($('progress').classList.contains('on')) {
            return true;
        }
        if ($('modal').classList.contains('on')) {
            closeModal();
            return true;
        }
        if (state.selecting) {
            clearSelection();
            return true;
        }
        if (state.tab === 'settings') {
            switchTab('phone');
            return true;
        }
        if (state.tab === 'fav') {
            switchTab('phone');
            return true;
        }
        if (state.tab === 'nas' && !state.connected) {
            switchTab('phone');
            return true;
        }
        if (!isAtRoot()) {
            var parent = C.parentOf(currentPath());
            if (state.tab === 'phone' && C.normalizePath(parent).length
                < C.normalizePath(state.phoneRoot).length) {
                parent = state.phoneRoot;
            }
            navigate(parent);
            return true;
        }
        return false;
    };

    function renderBadBrowser() {
        $('view-browse').classList.add('active');
        $('pathbar').classList.add('hidden');
        $('quick').classList.add('hidden');
        $('toolbar').classList.add('hidden');
        $('list').classList.add('hidden');
        $('empty').classList.add('on');
        $('emptyTitle').textContent = '앱에서 열어 주세요';
        $('emptyHint').textContent = '이 화면은 NAS 파일매니저 앱 안에서만 폰 저장소와 시놀로지에 접근할 수 있습니다.';
    }

    // A page opened before the native layer reported itself still boots on DOM ready.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            if (FM.hasNative) {
                boot();
            } else {
                bindChrome();
                applyViewChrome();
                renderBadBrowser();
                state.booted = true;
            }
        });
    } else if (FM.hasNative) {
        boot();
    } else {
        bindChrome();
        applyViewChrome();
        renderBadBrowser();
        state.booted = true;
    }
})();
