/*
 * tools/tests/dom.js — headless render smoke test for assets/app.js.
 *
 * There is no browser on the build machine, so this module fakes just enough DOM for the
 * real app code to run: elements for every id in index.html, an innerHTML setter that also
 * registers the ids of the generated markup (that is how the settings and login forms are
 * built), and two mock bridges. Then it boots the page, switches all four tabs and checks
 * that the screen actually filled in — which catches a typo or a null lookup that the
 * structure test cannot see.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..', '..');
const read = (relative) => fs.readFileSync(path.join(ROOT, relative), 'utf8');

function createDom(html) {
    const registry = new Map();

    function makeElement(tag) {
        const listeners = {};
        const element = {
            tagName: tag,
            _html: '',
            textContent: '',
            value: '',
            disabled: false,
            onclick: null,
            onchange: null,
            style: {display: '', width: ''},
            dataset: {},
            classList: {
                _set: new Set(),
                add(name) {
                    this._set.add(name);
                },
                remove(name) {
                    this._set.delete(name);
                },
                contains(name) {
                    return this._set.has(name);
                },
                toggle(name, force) {
                    const on = force === undefined ? !this._set.has(name) : !!force;
                    if (on) {
                        this._set.add(name);
                    } else {
                        this._set.delete(name);
                    }
                    return on;
                }
            },
            get innerHTML() {
                return this._html;
            },
            set innerHTML(value) {
                this._html = String(value);
                const pattern = /id="([^"]+)"/g;
                let found;
                while ((found = pattern.exec(this._html)) !== null) {
                    if (!registry.has(found[1])) {
                        registry.set(found[1], makeElement('div'));
                    }
                }
            },
            appendChild(child) {
                return child;
            },
            addEventListener(type, handler) {
                listeners[type] = handler;
            },
            removeEventListener() {
                // nothing to detach in the stub
            },
            dispatch(type, event) {
                if (listeners[type]) {
                    listeners[type](event || {});
                }
            },
            querySelectorAll() {
                return [];
            },
            querySelector() {
                return null;
            },
            focus() {
                // no focus in the stub
            },
            blur() {
                // no focus in the stub
            },
            setSelectionRange() {
                // not needed
            },
            removeAttribute(name) {
                if (name === 'src') {
                    this.src = '';
                }
            },
            getBoundingClientRect() {
                return {left: 0, top: 0, width: 400, height: 800};
            },
            play() {
                return Promise.resolve();
            },
            pause() {
                // nothing playing
            },
            load() {
                // no source to load
            }
        };
        return element;
    }

    const ids = new Set();
    const idPattern = /id="([^"]+)"/g;
    let match;
    while ((match = idPattern.exec(html)) !== null) {
        ids.add(match[1]);
    }
    ids.forEach((id) => registry.set(id, makeElement('div')));

    // data-* attributes the app reads (the tab buttons carry data-tab).
    const dataPattern = /data-([a-z-]+)="([^"]*)"[^>]*?\bid="([^"]+)"/g;
    let dataMatch;
    while ((dataMatch = dataPattern.exec(html)) !== null) {
        const element = registry.get(dataMatch[3]);
        if (element) {
            const key = dataMatch[1].replace(/-([a-z])/g, (m, c) => c.toUpperCase());
            element.dataset[key] = dataMatch[2];
        }
    }

    const document = {
        readyState: 'complete',
        getElementById(id) {
            return registry.get(id) || null;
        },
        querySelectorAll(selector) {
            // The chrome binds the four tabs through .tab, exactly once at startup.
            if (selector === '.tab') {
                return ['tab-phone', 'tab-nas', 'tab-fav', 'tab-settings']
                    .map((id) => registry.get(id))
                    .filter(Boolean);
            }
            return [];
        },
        querySelector() {
            return null;
        },
        addEventListener() {
            // DOMContentLoaded is never used: readyState is already complete
        },
        createElement(tag) {
            return makeElement(tag);
        }
    };

    return {document, registry};
}

function createStorage() {
    const data = new Map();
    return {
        getItem(key) {
            return data.has(key) ? data.get(key) : null;
        },
        setItem(key, value) {
            data.set(key, String(value));
        },
        removeItem(key) {
            data.delete(key);
        }
    };
}

const FILES = [
    {name: '2026 제주 여행', path: '/storage/emulated/0/DCIM/Camera/2026 제주 여행', isDir: true,
        size: 0, mtime: Date.now() - 3600000, kind: 'folder', mime: ''},
    {name: 'IMG_0001.jpg', path: '/storage/emulated/0/DCIM/Camera/IMG_0001.jpg', isDir: false,
        size: 4200000, mtime: Date.now() - 600000, kind: 'image', mime: 'image/jpeg'},
    {name: 'VID_0002.mp4', path: '/storage/emulated/0/DCIM/Camera/VID_0002.mp4', isDir: false,
        size: 92000000, mtime: Date.now() - 90000000, kind: 'video', mime: 'video/mp4'},
    {name: '메모.txt', path: '/storage/emulated/0/DCIM/Camera/메모.txt', isDir: false,
        size: 1200, mtime: Date.now() - 1600000000, kind: 'file', mime: 'application/octet-stream'}
];

const SHARES = [
    {name: 'photo', path: '/photo', isDir: true, size: 0, mtime: Date.now(), kind: 'folder'},
    {name: 'video', path: '/video', isDir: true, size: 0, mtime: Date.now(), kind: 'folder'}
];

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

async function renderSmoke() {
    const html = read('app/src/main/assets/index.html');
    const {document} = createDom(html);
    const errors = [];

    const sandbox = {
        console,
        setTimeout,
        clearTimeout,
        setInterval,
        clearInterval,
        Promise,
        JSON,
        Math,
        Date,
        Object,
        Array,
        String,
        Number,
        RegExp,
        Error,
        isFinite,
        encodeURIComponent,
        decodeURIComponent,
        TextEncoder,
        TextDecoder: TextDecoder,
        btoa,
        atob,
        localStorage: createStorage(),
        document,
        navigator: {userAgent: 'jest'}
    };
    sandbox.window = sandbox;
    sandbox.globalThis = sandbox;
    sandbox.self = sandbox;

    // Two bridges that answer like the Java side does, but with fixed data.
    function answer(reqId, ok, payload) {
        setTimeout(() => {
            sandbox.fmCallback(reqId, ok, JSON.stringify(payload));
        }, 5);
    }

    sandbox.LocalFileBridge = {
        call(reqId, action, raw) {
            const params = JSON.parse(raw || '{}');
            if (action === 'status') {
                return answer(reqId, true, {
                    success: true, sdk: 34, allFiles: true,
                    serverBase: 'http://127.0.0.1:1', serverToken: 'token',
                    videoCacheDir: '/cache/video',
                    roots: [{label: '내부 저장소', path: '/storage/emulated/0', kind: 'phone'}],
                    storage: {total: 1, free: 1}
                });
            }
            if (action === 'list') {
                return answer(reqId, true, {
                    success: true, path: params.path || '/storage/emulated/0',
                    parent: '/storage/emulated/0', entries: FILES,
                    quick: [{path: '/storage/emulated/0/DCIM/Camera', label: '카메라'}],
                    storage: {total: 1, free: 1}
                });
            }
            return answer(reqId, true, {success: true});
        }
    };

    sandbox.NasBridge = {
        call(reqId, action) {
            if (action === 'status') {
                return answer(reqId, true, {
                    connected: false, lastUrl: 'https://nas.example:5001', lastAccount: 'choi',
                    hasSavedPassword: false, servers: []
                });
            }
            if (action === 'list') {
                return answer(reqId, true, {
                    success: true, path: '/', parent: '/', entries: SHARES, total: 2
                });
            }
            return answer(reqId, true, {success: true});
        }
    };

    const context = vm.createContext(sandbox);
    try {
        vm.runInContext(read('app/src/main/assets/core.js'), context, {filename: 'core.js'});
        vm.runInContext(read('app/src/main/assets/bridge.js'), context, {filename: 'bridge.js'});
        vm.runInContext(read('app/src/main/assets/app.js'), context, {filename: 'app.js'});
    } catch (error) {
        throw new Error('app.js threw on load: ' + error.message);
    }

    await delay(120);

    const get = (id) => document.getElementById(id);
    const results = [];
    const expect = (name, condition, detail) => results.push({name, ok: !!condition, detail});

    expect('boot renders the phone folder', get('countText').textContent.indexOf('사진') >= 0,
        get('countText').textContent);
    expect('grid shows a file name', get('list').innerHTML.indexOf('IMG_0001.jpg') >= 0);
    expect('grid shows the folder first', get('list').innerHTML.indexOf('2026 제주 여행') >= 0);
    expect('crumb bar was built', get('crumbs').innerHTML.indexOf('내부 저장소') >= 0);
    expect('quick chips are rendered', get('quick').innerHTML.indexOf('카메라') >= 0);
    expect('empty state is hidden with files', !get('empty').classList.contains('on'));

    // 설정 tab
    if (typeof get('tab-settings').onclick === 'function') {
        get('tab-settings').onclick();
        expect('settings screen is built', get('settingsBody').innerHTML.indexOf('시놀로지 NAS') >= 0);
        expect('settings shows storage permission',
            get('settingsBody').innerHTML.indexOf('모든 파일 접근') >= 0);
        expect('settings shows the version', get('settingsBody').innerHTML.indexOf('com.choi.filemanager') >= 0);
    } else {
        expect('settings tab has a handler', false);
    }

    // 시놀로지 tab without a connection
    if (typeof get('tab-nas').onclick === 'function') {
        get('tab-nas').onclick();
        expect('nas tab shows the login form', get('connect').innerHTML.indexOf('서버 주소') >= 0);
        expect('login form has the saved server',
            get('connect').innerHTML.indexOf('https://nas.example:5001') >= 0);
        expect('path bar is hidden on the login screen', get('pathbar').classList.contains('hidden'));
    } else {
        expect('nas tab has a handler', false);
    }

    // 즐겨찾기 tab
    if (typeof get('tab-fav').onclick === 'function') {
        get('tab-fav').onclick();
        expect('favourites tab renders its empty state',
            get('emptyTitle').textContent.indexOf('즐겨찾기') >= 0, get('emptyTitle').textContent);
        expect('favourites hide the new-folder button', get('btnNewFolder').classList.contains('hidden'));
    } else {
        expect('fav tab has a handler', false);
    }

    // back to 내 폰
    get('tab-phone').onclick();
    await delay(80);
    expect('phone tab renders again', get('list').innerHTML.indexOf('IMG_0001.jpg') >= 0);

    // back button on a root folder returns false so Android closes the app
    const handled = sandbox.fmHandleBack ? sandbox.fmHandleBack() : null;
    expect('back at root is not consumed', handled === false, String(handled));

    return results;
}

module.exports = {renderSmoke};
