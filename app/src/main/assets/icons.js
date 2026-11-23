/*
 * icons.js — the app's icon set, drawn inline as SVG.
 *
 * The design mock-up loaded Lucide from unpkg.com and Tailwind from a CDN. The app is used on
 * a home Wi-Fi with no internet as often as not, so every stroke icon lives here instead and
 * is inlined into the page: no network, no font, nothing that can fail to load.
 *
 *   FMIcons.svg('download')            -> "<svg …>…</svg>"
 *   FMIcons.svg('download', 'lg')      -> same, with an extra class
 *   FMIcons.apply(root)                -> fills every <i data-icon="…"></i> under root
 */
(function (root) {
    'use strict';

    var PATHS = {
        'hard-drive': '<line x1="22" x2="2" y1="12" y2="12"/>'
            + '<path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>'
            + '<line x1="6" x2="6.01" y1="16" y2="16"/><line x1="10" x2="10.01" y1="16" y2="16"/>',
        'arrow-up-down': '<path d="m21 16-4 4-4-4"/><path d="M17 20V4"/>'
            + '<path d="m3 8 4-4 4 4"/><path d="M7 4v16"/>',
        'arrow-left-right': '<path d="M8 3 4 7l4 4"/><path d="M4 7h16"/>'
            + '<path d="m16 21 4-4-4-4"/><path d="M20 17H4"/>',
        'server': '<rect width="20" height="8" x="2" y="2" rx="2"/>'
            + '<rect width="20" height="8" x="2" y="14" rx="2"/>'
            + '<line x1="6" x2="6.01" y1="6" y2="6"/><line x1="6" x2="6.01" y1="18" y2="18"/>',
        'search': '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
        'x': '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
        'arrow-down-wide-narrow': '<path d="m3 16 4 4 4-4"/><path d="M7 20V4"/>'
            + '<path d="M11 4h10"/><path d="M11 8h7"/><path d="M11 12h4"/>',
        'list': '<line x1="8" x2="21" y1="6" y2="6"/><line x1="8" x2="21" y1="12" y2="12"/>'
            + '<line x1="8" x2="21" y1="18" y2="18"/><line x1="3" x2="3.01" y1="6" y2="6"/>'
            + '<line x1="3" x2="3.01" y1="12" y2="12"/><line x1="3" x2="3.01" y1="18" y2="18"/>',
        'layout-grid': '<rect width="7" height="7" x="3" y="3" rx="1"/>'
            + '<rect width="7" height="7" x="14" y="3" rx="1"/>'
            + '<rect width="7" height="7" x="14" y="14" rx="1"/>'
            + '<rect width="7" height="7" x="3" y="14" rx="1"/>',
        'check-square': '<path d="m9 11 3 3L22 4"/>'
            + '<path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>',
        'check': '<path d="M20 6 9 17l-5-5"/>',
        'folder-plus': '<path d="M12 10v6"/><path d="M9 13h6"/>'
            + '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
        'folder': '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
        'folder-open': '<path d="m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2"/>',
        'folder-input': '<path d="M2 9V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-1"/>'
            + '<path d="M2 13h10"/><path d="m9 16 3-3-3-3"/>',
        'plus': '<path d="M5 12h14"/><path d="M12 5v14"/>',
        'download': '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>'
            + '<polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>',
        'upload': '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>'
            + '<polyline points="17 8 12 3 7 8"/><line x1="12" x2="12" y1="3" y2="15"/>',
        'trash-2': '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/>'
            + '<path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>'
            + '<line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/>',
        'play': '<polygon points="6 3 20 12 6 21 6 3"/>',
        'chevron-left': '<path d="m15 18-6-6 6-6"/>',
        'chevron-right': '<path d="m9 18 6-6-6-6"/>',
        'chevron-down': '<path d="m6 9 6 6 6-6"/>',
        'chevron-up': '<path d="m18 15-6-6-6 6"/>',
        'arrow-left': '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
        'arrow-down-to-line': '<path d="M12 17V3"/><path d="m6 11 6 6 6-6"/><path d="M19 21H5"/>',
        'zoom-in': '<circle cx="11" cy="11" r="8"/>'
            + '<line x1="21" x2="16.65" y1="21" y2="16.65"/>'
            + '<line x1="11" x2="11" y1="8" y2="14"/><line x1="8" x2="14" y1="11" y2="11"/>',
        'home': '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>'
            + '<polyline points="9 22 9 12 15 12 15 22"/>',
        'more-vertical': '<circle cx="12" cy="12" r="1"/>'
            + '<circle cx="12" cy="5" r="1"/><circle cx="12" cy="19" r="1"/>',
        'edit-3': '<path d="M12 20h9"/>'
            + '<path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
        'loader-2': '<path d="M21 12a9 9 0 1 1-6.219-8.56"/>',
        'image': '<rect width="18" height="18" x="3" y="3" rx="2" ry="2"/>'
            + '<circle cx="9" cy="9" r="2"/>'
            + '<path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/>',
        'video': '<path d="m22 8-6 4 6 4V8Z"/>'
            + '<rect width="14" height="12" x="2" y="6" rx="2"/>',
        'music': '<path d="M9 18V5l12-2v13"/>'
            + '<circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
        'file': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/>'
            + '<path d="M14 2v4a2 2 0 0 0 2 2h4"/>',
        'refresh-cw': '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/>'
            + '<path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/>'
            + '<path d="M8 16H3v5"/>',
        'star': '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>',
        'share-2': '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/>'
            + '<circle cx="18" cy="19" r="3"/>'
            + '<line x1="8.59" x2="15.42" y1="13.51" y2="17.49"/>'
            + '<line x1="15.41" x2="8.59" y1="6.51" y2="10.49"/>',
        'info': '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
        'copy': '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/>'
            + '<path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
        'smartphone': '<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/>'
            + '<path d="M12 18h.01"/>',
        'cloud': '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>',
        'lock': '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/>'
            + '<path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
        'eye': '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/>'
            + '<circle cx="12" cy="12" r="3"/>',
        'external-link': '<path d="M15 3h6v6"/><path d="M10 14 21 3"/>'
            + '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
        'circle-check': '<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>',
        'alert-triangle': '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/>'
            + '<path d="M12 9v4"/><path d="M12 17h.01"/>',
        'sliders': '<line x1="4" x2="4" y1="21" y2="14"/><line x1="4" x2="4" y1="10" y2="3"/>'
            + '<line x1="12" x2="12" y1="21" y2="12"/><line x1="12" x2="12" y1="8" y2="3"/>'
            + '<line x1="20" x2="20" y1="21" y2="16"/><line x1="20" x2="20" y1="12" y2="3"/>'
            + '<line x1="2" x2="6" y1="14" y2="14"/><line x1="10" x2="14" y1="8" y2="8"/>'
            + '<line x1="18" x2="22" y1="16" y2="16"/>',
        'sort-desc': '<path d="m3 16 4 4 4-4"/><path d="M7 20V4"/>'
            + '<path d="M11 4h4"/><path d="M11 8h7"/><path d="M11 12h10"/>',
        'arrow-up': '<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',
        'refresh': '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>'
    };

    var ALIAS = {
        'grid': 'layout-grid',
        'select': 'check-square',
        'menu': 'more-vertical',
        'upload-cloud': 'upload',
        'close': 'x'
    };

    function svg(name, extraClass) {
        var key = ALIAS[name] || name;
        var body = PATHS[key];
        if (!body) {
            body = PATHS['file'];
            key = 'file';
        }
        return '<svg class="ic' + (extraClass ? ' ' + extraClass : '') + '" viewBox="0 0 24 24"'
            + ' fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"'
            + ' stroke-linejoin="round" aria-hidden="true" data-icon-name="' + key + '">'
            + body + '</svg>';
    }

    /** Replaces the placeholder <i data-icon="name"> nodes with real SVG markup. */
    function apply(scope) {
        var host = scope || document;
        var nodes = host.querySelectorAll ? host.querySelectorAll('[data-icon]') : [];
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var name = node.getAttribute('data-icon');
            if (name) {
                node.innerHTML = svg(name);
                node.removeAttribute('data-icon');
            }
        }
    }

    root.FMIcons = {svg: svg, apply: apply, names: Object.keys(PATHS)};
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = root.FMIcons;
    }
})(typeof window !== 'undefined' ? window : globalThis);
