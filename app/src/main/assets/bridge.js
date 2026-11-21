/*
 * Native bridge shims for the 「TSV secret 근무표」 Android shell.
 * Injected by MainActivity into file:///android_asset/index.html after load.
 * The web app itself is untouched — everything here only adds native behaviour
 * where the WebView alone would silently do nothing.
 */
(function () {
  if (window.__tsvBridgeReady) return;
  window.__tsvBridgeReady = true;

  var B = window.AndroidBridge || null;

  function safe(fn) {
    try { fn(); } catch (e) { /* never break the app */ }
  }

  /* ---------------------------------------------------------------- files */

  // Keep blobs alive so the native side can read them even after the page calls
  // URL.revokeObjectURL() immediately after the synthetic click.
  var blobCache = Object.create(null);
  safe(function () {
    var origCreate = URL.createObjectURL;
    URL.createObjectURL = function (obj) {
      var url = origCreate.call(URL, obj);
      try { blobCache[url] = obj; } catch (e) {}
      return url;
    };
    var origRevoke = URL.revokeObjectURL;
    URL.revokeObjectURL = function (url) {
      setTimeout(function () {
        safe(function () {
          delete blobCache[url];
          origRevoke.call(URL, url);
        });
      }, 60000);
    };
  });

  function sendDataUrl(dataUrl, name) {
    if (!B || !B.saveDataUrl || !dataUrl) return;
    safe(function () { B.saveDataUrl(dataUrl, name || 'TSV-secret-근무표'); });
  }

  function blobToDataUrl(blob, cb) {
    safe(function () {
      var reader = new FileReader();
      reader.onload = function () { cb(reader.result); };
      reader.onerror = function () { cb(null); };
      reader.readAsDataURL(blob);
    });
  }

  function handleAnchor(a) {
    if (!a || !a.getAttribute) return false;
    var name = a.getAttribute('download');
    if (!name) return false;
    var href = a.getAttribute('href') || a.href || '';

    if (href.indexOf('data:') === 0) {
      sendDataUrl(href, name);
      return true;
    }
    if (href.indexOf('blob:') === 0) {
      var cached = blobCache[href];
      if (cached) {
        blobToDataUrl(cached, function (d) { if (d) sendDataUrl(d, name); });
        return true;
      }
      fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
        blobToDataUrl(b, function (d) { if (d) sendDataUrl(d, name); });
      }).catch(function () {
        if (B && B.toast) B.toast('파일을 저장하지 못했어요');
      });
      return true;
    }
    return false;
  }

  safe(function () {
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (handleAnchor(this)) return;
      return origClick.apply(this, arguments);
    };
    document.addEventListener('click', function (ev) {
      var node = ev.target;
      var a = node && node.closest ? node.closest('a[download]') : null;
      if (a && handleAnchor(a)) ev.preventDefault();
    }, true);
  });

  /* ------------------------------------------------------------- clipboard */

  safe(function () {
    if (!B || !B.copyToClipboard) return;
    if (!navigator.clipboard) {
      try {
        Object.defineProperty(navigator, 'clipboard', {
          configurable: true,
          value: {
            writeText: function (text) {
              return new Promise(function (resolve, reject) {
                var ok = false;
                try { ok = B.copyToClipboard(String(text)); } catch (e) { ok = false; }
                if (ok) resolve(); else reject(new Error('clipboard unavailable'));
              });
            }
          }
        });
      } catch (e) {}
    }

    // execCommand('copy') is the page's fallback path; route it to the native clipboard.
    var origExec = Document.prototype.execCommand;
    Document.prototype.execCommand = function (cmd) {
      if (String(cmd).toLowerCase() === 'copy') {
        var text = '';
        var sel = window.getSelection ? String(window.getSelection()) : '';
        var active = document.activeElement;
        if (sel) {
          text = sel;
        } else if (active && typeof active.value === 'string') {
          var start = typeof active.selectionStart === 'number' ? active.selectionStart : 0;
          var end = typeof active.selectionEnd === 'number' ? active.selectionEnd : active.value.length;
          text = active.value.substring(start, end);
        }
        if (text) {
          var copied = false;
          try { copied = B.copyToClipboard(text); } catch (e) { copied = false; }
          if (copied) return true;
        }
      }
      return origExec.apply(this, arguments);
    };
  });

  /* --------------------------------------------------------------- storage */

  // Mirror localStorage into SharedPreferences so schedules survive even if the
  // WebView storage for the file:// origin is cleared.
  safe(function () {
    var proto = window.Storage && Storage.prototype;
    if (!proto || !B) return;
    var origSet = proto.setItem;
    proto.setItem = function (k, v) {
      var result = origSet.call(this, k, v);
      if (B.setItem) {
        try { B.setItem(String(k), String(v)); } catch (e) {}
      }
      return result;
    };
    var origRemove = proto.removeItem;
    proto.removeItem = function (k) {
      var result = origRemove.call(this, k);
      if (B.removeItem) {
        try { B.removeItem(String(k)); } catch (e) {}
      }
      return result;
    };
  });

  /* ---------------------------------------------------------- file chooser */

  // A display:none <input type="file"> stops Android WebView from opening the
  // picker, so keep it rendered but invisible.
  safe(function () {
    var inputs = document.querySelectorAll('input[type="file"]');
    for (var i = 0; i < inputs.length; i++) {
      var input = inputs[i];
      if (input.style.display === 'none') {
        input.style.display = 'block';
        input.style.position = 'absolute';
        input.style.width = '1px';
        input.style.height = '1px';
        input.style.opacity = '0';
        input.style.overflow = 'hidden';
        input.style.left = '-9999px';
      }
    }
  });

  /* ----------------------------------------------------------- system bars */

  function toHex(color) {
    if (!color) return '';
    color = String(color).trim();
    if (color.charAt(0) === '#') return color;
    var m = color.match(/rgba?\(([^)]+)\)/);
    if (!m) return '';
    var parts = m[1].split(',').map(function (x) { return parseFloat(x); });
    if (parts.length < 3) return '';
    function h(n) {
      var s = Math.max(0, Math.min(255, Math.round(n))).toString(16);
      return s.length === 1 ? '0' + s : s;
    }
    return '#' + h(parts[0]) + h(parts[1]) + h(parts[2]);
  }

  function syncChrome() {
    if (!B || !B.updateChrome) return;
    safe(function () {
      var bodyStyle = getComputedStyle(document.body);
      var bg = toHex(bodyStyle.backgroundColor) || '#f3f6fb';
      var rootStyle = getComputedStyle(document.documentElement);
      var gradient = String(rootStyle.getPropertyValue('--header-grad') || '');
      var found = gradient.match(/#[0-9a-fA-F]{6}/);
      var header = found ? found[0] : bg;
      var night = document.body.classList.contains('night');
      B.updateChrome(bg, header, night);
    });
  }

  safe(function () {
    new MutationObserver(syncChrome).observe(document.body, {
      attributes: true,
      attributeFilter: ['class']
    });
    syncChrome();
  });
})();
