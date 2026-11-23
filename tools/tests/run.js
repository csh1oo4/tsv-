/*
 * tools/tests/run.js — JS unit + structure tests for 「NAS 파일매니저」.
 *
 *   node tools/tests/run.js
 *
 * Part 1 runs the pure logic in assets/core.js (paths, typing, sorting, filtering,
 * formatting, base64url, range-free stream URLs).
 * Part 2 is a structure test: it reads index.html, app.js, bridge.js, the manifest, the
 * Java bridges and the gradle config, and checks that every id, every bridge action and
 * every declared permission the app depends on is actually wired. That is what catches a
 * renamed bridge method or a missing manifest entry without a device.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const read = (relative) => fs.readFileSync(path.join(ROOT, relative), 'utf8');
const exists = (relative) => fs.existsSync(path.join(ROOT, relative));

const C = require(path.join(ROOT, 'app/src/main/assets/core.js'));

let passed = 0;
const failures = [];

function check(name, condition, detail) {
    if (condition) {
        passed++;
    } else {
        failures.push(name + (detail ? ' — ' + detail : ''));
    }
}

function eq(actual, expected, name) {
    check(name, Object.is(actual, expected) || JSON.stringify(actual) === JSON.stringify(expected),
        'expected ' + JSON.stringify(expected) + ', got ' + JSON.stringify(actual));
}

// ---------------------------------------------------------------- part 1: logic

eq(C.normalizePath('a/b/'), '/a/b', 'normalizePath adds root and drops trailing slash');
eq(C.normalizePath('/a//b'), '/a/b', 'normalizePath collapses double slashes');
eq(C.normalizePath(null), '/', 'normalizePath(null) is root');
eq(C.joinPath('/photo', 'a.jpg'), '/photo/a.jpg', 'joinPath');
eq(C.joinPath('/', 'a'), '/a', 'joinPath from root');
eq(C.joinPath('/a', '/b/c'), '/b/c', 'joinPath with absolute name');
eq(C.parentOf('/a/b'), '/a', 'parentOf');
eq(C.parentOf('/a'), '/', 'parentOf top level');
eq(C.nameOf('/a/b.jpg'), 'b.jpg', 'nameOf');
eq(C.extOf('A.JPG'), 'jpg', 'extOf lowercases');
eq(C.extOf('noext'), '', 'extOf without dot');
check('isImage heic', C.isImage('IMG_1.HEIC'));
check('isVideo mkv', C.isVideo('a.MKV'));
check('not image for txt', !C.isImage('a.txt'));
eq(C.kindOf('a.mp4'), 'video', 'kindOf video');
eq(C.kindOf('a.txt'), 'file', 'kindOf file');
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

const sample = [
    {name: 'b.jpg', isDir: false, size: 200, mtime: 2000},
    {name: 'a.mp4', isDir: false, size: 900, mtime: 3000},
    {name: 'Zeta', isDir: true, size: 0, mtime: 1000},
    {name: 'c.jpg', isDir: false, size: 100, mtime: 4000}
];
eq(C.sortEntries(sample, 'name', 'asc').map((e) => e.name),
    ['Zeta', 'a.mp4', 'b.jpg', 'c.jpg'], 'sort name keeps folders first');
eq(C.sortEntries(sample, 'date', 'asc').map((e) => e.name),
    ['Zeta', 'c.jpg', 'a.mp4', 'b.jpg'], 'sort date newest first');
eq(C.sortEntries(sample, 'size', 'asc').map((e) => e.name),
    ['Zeta', 'a.mp4', 'b.jpg', 'c.jpg'], 'sort size largest first');
eq(C.filterEntries(sample, 'image', '').map((e) => e.name), ['b.jpg', 'c.jpg'],
    'filter images hides folders');
eq(C.filterEntries(sample, 'video', '').map((e) => e.name), ['a.mp4'], 'filter videos');
eq(C.filterEntries(sample, 'all', 'c').map((e) => e.name), ['c.jpg'], 'search filter');
eq(C.countByKind(sample).image, 2, 'countByKind images');
eq(C.countByKind(sample).folder, 1, 'countByKind folders');
eq(C.summary(sample), '폴더 1 · 사진 2 · 동영상 1', 'summary line');

const url = C.streamUrl('http://127.0.0.1:8000', 'tok', 's', '/photo/a b.jpg');
check('streamUrl points at the loopback server',
    url.indexOf('http://127.0.0.1:8000/s?t=tok&p=') === 0, url);
check('streamUrl encodes spaces', url.indexOf(' ') < 0, url);
check('streamUrl adds size', C.streamUrl('http://x', 't', 'lt', '/a.jpg', 256).indexOf('&s=256') > 0,
    'size parameter');

// ------------------------------------------------------------ part 2: structure

const html = read('app/src/main/assets/index.html');
const appJs = read('app/src/main/assets/app.js');
const bridgeJs = read('app/src/main/assets/bridge.js');
const manifest = read('app/src/main/AndroidManifest.xml');
const activity = read('app/src/main/java/com/choi/filemanager/MainActivity.java');
const nasBridge = read('app/src/main/java/com/choi/filemanager/NasBridge.java');
const localBridge = read('app/src/main/java/com/choi/filemanager/LocalFileBridge.java');
const localServer = read('app/src/main/java/com/choi/filemanager/LocalServer.java');
const gradle = read('app/build.gradle');

// script order matters: core defines FMCore, bridge defines FM, app uses both.
const coreAt = html.indexOf('core.js');
const bridgeAt = html.indexOf('bridge.js');
const appAt = html.indexOf('app.js');
check('core.js loads before bridge.js and app.js', coreAt >= 0 && coreAt < bridgeAt && bridgeAt < appAt,
    coreAt + '/' + bridgeAt + '/' + appAt);

const requiredIds = [
    'topbar', 'tab-phone', 'tab-nas', 'tab-fav', 'tab-settings', 'nasDot', 'nasState',
    'view-browse', 'pathbar', 'btnUp', 'crumbs', 'btnFav', 'btnReload', 'connect', 'quick',
    'toolbar', 'filters', 'sort', 'searchwrap', 'search', 'btnSearch', 'btnView',
    'btnNewFolder', 'statusline', 'countText', 'list', 'empty', 'emptyTitle', 'emptyHint',
    'emptyAction', 'selbar', 'view-settings', 'settingsBody', 'viewer', 'vClose', 'vTitle',
    'vCount', 'vExternal', 'vStage', 'vImg', 'vPrev', 'vZoom', 'vSave', 'vNext', 'player',
    'pClose', 'pTitle', 'pExternal', 'pStage', 'playerVideo', 'pLoading', 'pSave',
    'modal', 'modalSheet', 'progress', 'progressSheet', 'toast'
];
requiredIds.forEach((id) => {
    check('index.html has #' + id, html.indexOf('id="' + id + '"') >= 0);
});

// Every $('x') in app.js must exist in the markup or be created by the app itself.
const dynamicIds = new Set([
    'dlgInput', 'dlgCancel', 'dlgOk', 'pgFill', 'pgMessage', 'pickPath', 'pickList',
    'pickCancel', 'pickChoose', 'cUrl', 'cId', 'cPw', 'cOtp', 'cSsl', 'cAuto', 'cLogin',
    'cSettings', 'setConnect', 'setReconnect', 'setLogout', 'setAutoLogin', 'setSsl',
    'setAllow', 'setMediaPerm', 'setHidden', 'setClearCache'
]);
const usedIds = new Set();
let match;
const idPattern = /\$\('([A-Za-z0-9_-]+)'\)/g;
while ((match = idPattern.exec(appJs)) !== null) {
    usedIds.add(match[1]);
}
usedIds.forEach((id) => {
    if (dynamicIds.has(id)) {
        return;
    }
    check('app.js uses existing #' + id, html.indexOf('id="' + id + '"') >= 0);
});

// Bridge actions used by the page must have a case in the matching Java dispatcher.
const nasActions = new Set();
const localActions = new Set();
const actionPattern = /FM\.(nas|local)\('([a-zA-Z]+)'/g;
while ((match = actionPattern.exec(appJs)) !== null) {
    (match[1] === 'nas' ? nasActions : localActions).add(match[2]);
}
check('page talks to the NAS bridge', nasActions.size >= 6, [...nasActions].join(','));
nasActions.forEach((action) => {
    check('NasBridge handles ' + action, nasBridge.indexOf('case "' + action + '"') >= 0);
});
localActions.forEach((action) => {
    check('LocalFileBridge handles ' + action, localBridge.indexOf('case "' + action + '"') >= 0);
});

// WebView interfaces and the native display path.
check('MainActivity registers window.NasBridge',
    activity.indexOf('addJavascriptInterface(new NasJs(), "NasBridge")') >= 0);
check('MainActivity registers window.LocalFileBridge',
    activity.indexOf('addJavascriptInterface(new LocalJs(), "LocalFileBridge")') >= 0);
check('MainActivity starts the loopback server', activity.indexOf('server.start()') >= 0);
check('FileProvider is used for external apps', activity.indexOf('FileProvider.getUriForFile') >= 0);
check('bridge.js exposes fmCallback/fmProgress',
    bridgeJs.indexOf('window.fmCallback') >= 0 && bridgeJs.indexOf('window.fmProgress') >= 0);
check('bridge.js exposes the loopback media URL helper', bridgeJs.indexOf('FM.mediaUrl') >= 0);

// DSM APIs the brief requires.
[
    'SYNO.API.Auth', 'SYNO.API.Info', 'SYNO.FileStation.List', 'SYNO.FileStation.Thumb',
    'SYNO.FileStation.Download', 'SYNO.FileStation.Upload', 'SYNO.FileStation.CreateFolder',
    'SYNO.FileStation.Rename', 'SYNO.FileStation.Delete', 'SYNO.FileStation.CopyMove'
].forEach((api) => {
    check('NasBridge calls ' + api, nasBridge.indexOf(api) >= 0);
});
check('NAS list_share is used for share roots', nasBridge.indexOf('"list_share"') >= 0);
check('NAS login sends the OTP code', nasBridge.indexOf('otp_code') >= 0);
check('NAS login supports a self-signed certificate',
    nasBridge.indexOf('setSSLSocketFactory') >= 0 && nasBridge.indexOf('X509TrustManager') >= 0);
check('NAS uploads as multipart', nasBridge.indexOf('multipart/form-data') >= 0);
check('LocalServer serves /f /lt /s /nt',
    ['"/f"', '"/lt"', '"/s"', '"/nt"'].every((route) => localServer.indexOf(route) >= 0));
check('LocalServer supports Range responses', localServer.indexOf('"Partial Content"') >= 0);

// Manifest permissions and attributes.
[
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.MANAGE_EXTERNAL_STORAGE',
    'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE'
].forEach((permission) => {
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

// Build identity and the fixed signing key.
check('applicationId is com.choi.filemanager', gradle.indexOf('applicationId "com.choi.filemanager"') >= 0);
check('versionCode is 1', /versionCode 1\b/.test(gradle));
check('versionName is 1.0', gradle.indexOf('versionName "1.0"') >= 0);
check('signing uses the fixed keystore', gradle.indexOf("storeFile file('filemanager-fixed.p12')") >= 0);
check('signing alias is filemanager', gradle.indexOf("keyAlias 'filemanager'") >= 0);
check('fixed keystore is committed', exists('app/filemanager-fixed.p12'));
check('launcher icon is an adaptive vector',
    exists('app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml')
    && exists('app/src/main/res/drawable/ic_launcher_foreground.xml'));
check('app label is NAS 파일매니저',
    read('app/src/main/res/values/strings.xml').indexOf('NAS 파일매니저') >= 0);

// The page must never reach the network itself; that is the bridges' job.
check('app.js does not use fetch()', !/\bfetch\s*\(/.test(appJs));
check('app.js does not use XMLHttpRequest', appJs.indexOf('XMLHttpRequest') < 0);

// ---------------------------------------------------------------- summary

function finish() {
    const total = passed + failures.length;
    if (failures.length === 0) {
        console.log('PASS ' + total + ' checks');
        process.exit(0);
    }
    console.log('FAIL ' + failures.length + ' of ' + total + ' checks');
    failures.forEach((failure) => console.log('  ✗ ' + failure));
    process.exit(1);
}

const {renderSmoke} = require('./dom.js');

renderSmoke().then((results) => {
    results.forEach((result) => check(result.name, result.ok, result.detail));
    finish();
}).catch((error) => {
    check('render smoke test ran', false, error && error.message ? error.message : String(error));
    finish();
});
