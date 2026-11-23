/*
 * tools/tests/run.js — Node entry point for the SynoFile Pro test suite.
 *
 *   node tools/tests/run.js
 *
 * GitHub Actions runs this before assembleDebug (see app/build.gradle → jsTests). The checks
 * themselves live in suite.js so the same run can also happen on a machine without node
 * (tools/tests/run.py, V8 through py_mini_racer).
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const SKIP_DIRS = new Set(['.git', 'build', 'node_modules', '.gradle']);
const TEXT_EXT = new Set(['.js', '.html', '.java', '.xml', '.gradle', '.md', '.pro', '.properties', '.txt', '.yml', '.yaml', '.json']);

function collect() {
    const files = {};
    const exists = {};
    (function walk(dir) {
        let entries;
        try {
            entries = fs.readdirSync(dir, {withFileTypes: true});
        } catch (e) {
            return;
        }
        for (const entry of entries) {
            const full = path.join(dir, entry.name);
            const rel = path.relative(ROOT, full).split(path.sep).join('/');
            if (entry.isDirectory()) {
                if (SKIP_DIRS.has(entry.name)) {
                    continue;
                }
                walk(full);
                continue;
            }
            exists[rel] = true;
            if (TEXT_EXT.has(path.extname(entry.name).toLowerCase())) {
                try {
                    files[rel] = fs.readFileSync(full, 'utf8');
                } catch (e) {
                    // unreadable files simply stay out of the map
                }
            }
        }
    })(ROOT);
    return {files, exists};
}

const suite = require('./suite.js');
const env = collect();
suite.init(env);

function finish(result) {
    const total = result.passed + result.failures.length;
    if (result.failures.length === 0) {
        console.log('PASS ' + total + ' checks');
        process.exit(0);
    }
    console.log('FAIL ' + result.failures.length + ' of ' + total + ' checks');
    result.failures.forEach((failure) => console.log('  ✗ ' + failure));
    process.exit(1);
}

// The page boots through promise chains and every folder tap loads through the mock bridge.
// Each results() call runs one phase; give the microtask queue a turn in between.
async function drive() {
    let result = {passed: 0, failures: [], done: false};
    for (let i = 0; i < 24 && !result.done; i++) {
        await new Promise((resolve) => setTimeout(resolve, 10));
        result = suite.results();
    }
    finish(result);
}

drive();
