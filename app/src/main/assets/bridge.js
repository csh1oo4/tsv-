/*
 * bridge.js — the only place that talks to the Android shell.
 *
 * Native code answers a bridge call asynchronously through window.fmCallback(reqId, ok, json);
 * this file turns that into promises so app.js can simply `await FM.nas('list', {path})`.
 * Transfers report window.fmProgress(reqId, percent, message).
 *
 * When the page is opened in a plain browser (no bridge), FM.hasNative stays false and the
 * app shows the "앱에서 열어 주세요" notice instead of failing on every tap.
 */
(function (window) {
    'use strict';

    var pending = {};
    var progressHandlers = {};
    var seq = 0;

    window.fmCallback = function (reqId, ok, json) {
        var entry = pending[reqId];
        if (!entry) {
            return;
        }
        delete pending[reqId];
        delete progressHandlers[reqId];
        var data;
        try {
            data = typeof json === 'string' ? JSON.parse(json || '{}') : (json || {});
        } catch (e) {
            data = {};
        }
        if (ok && data.success !== false) {
            entry.resolve(data);
        } else {
            var message = data.message || data.error || '작업을 마치지 못했습니다.';
            if (typeof data.error === 'number') {
                message = data.message || ('오류 ' + data.error);
            }
            var error = new Error(message);
            error.code = data.error;
            error.data = data;
            entry.reject(error);
        }
    };

    window.fmProgress = function (reqId, percent, message) {
        var handler = progressHandlers[reqId];
        if (handler) {
            try {
                handler(percent, message || '');
            } catch (e) {
                // a broken progress handler must not break the transfer
            }
        }
    };

    window.fmOnNativeReady = function () {
        if (window.FM && typeof window.FM.onReady === 'function') {
            window.FM.onReady();
        }
    };

    window.fmOnResume = function () {
        if (window.FM && typeof window.FM.onResume === 'function') {
            window.FM.onResume();
        }
    };

    window.fmOnPermissionResult = function () {
        if (window.FM && typeof window.FM.onPermissionResult === 'function') {
            window.FM.onPermissionResult();
        }
    };

    var FM = window.FM || {};

    FM.hasNative = !!(window.NasBridge && window.LocalFileBridge);

    function request(iface, action, params, onProgress) {
        return new Promise(function (resolve, reject) {
            var bridge = window[iface];
            if (!bridge || typeof bridge.call !== 'function') {
                reject(new Error('앱에서 열어야 사용할 수 있는 기능입니다.'));
                return;
            }
            var id = 'r' + (++seq) + '_' + Date.now();
            pending[id] = {resolve: resolve, reject: reject};
            if (typeof onProgress === 'function') {
                progressHandlers[id] = onProgress;
            }
            try {
                bridge.call(id, action, JSON.stringify(params || {}));
            } catch (e) {
                delete pending[id];
                delete progressHandlers[id];
                reject(e);
            }
        });
    }

    FM.nas = function (action, params, onProgress) {
        return request('NasBridge', action, params, onProgress);
    };

    FM.local = function (action, params) {
        return request('LocalFileBridge', action, params);
    };

    /** Direct one-way helpers that do not need an answer. */
    FM.allowAllFiles = function () {
        if (window.LocalFileBridge && window.LocalFileBridge.requestAllFiles) {
            window.LocalFileBridge.requestAllFiles();
        }
    };

    FM.requestMedia = function () {
        if (window.LocalFileBridge && window.LocalFileBridge.requestMedia) {
            window.LocalFileBridge.requestMedia();
        }
    };

    FM.openAppSettings = function () {
        if (window.LocalFileBridge && window.LocalFileBridge.openAppSettings) {
            window.LocalFileBridge.openAppSettings();
        }
    };

    FM.phoneCacheSize = function () {
        try {
            return window.LocalFileBridge && window.LocalFileBridge.thumbCacheSize
                ? Number(window.LocalFileBridge.thumbCacheSize()) || 0 : 0;
        } catch (e) {
            return 0;
        }
    };

    FM.nasCacheSize = function () {
        try {
            return window.NasBridge && window.NasBridge.thumbCacheSize
                ? Number(window.NasBridge.thumbCacheSize()) || 0 : 0;
        } catch (e) {
            return 0;
        }
    };

    FM.clearCaches = function () {
        try {
            if (window.LocalFileBridge && window.LocalFileBridge.clearThumbCache) {
                window.LocalFileBridge.clearThumbCache();
            }
            if (window.NasBridge && window.NasBridge.clearThumbCache) {
                window.NasBridge.clearThumbCache();
            }
        } catch (e) {
            // cache clearing is best effort
        }
    };

    /** Loopback URL for <img>/<video>: route 'f'/'lt' for the phone, 's'/'nt' for the NAS. */
    FM.mediaUrl = function (path, isNas, thumbnail, size) {
        var base = window.fmServerBase || '';
        var token = window.fmServerToken || '';
        if (!base || !window.FMCore) {
            return '';
        }
        var route = isNas
            ? (thumbnail ? 'nt' : 's')
            : (thumbnail ? 'lt' : 'f');
        return window.FMCore.streamUrl(base, token, route, path, size);
    };

    window.FM = FM;
})(window);
