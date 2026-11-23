#!/usr/bin/env python3
"""tools/tests/run.py — run the SynoFile Pro suite without node.

    python3 tools/tests/run.py

This machine has no node and no JDK, but it does have V8 through py_mini_racer. The checks
themselves are tools/tests/suite.js (the same file `node tools/tests/run.js` runs in CI); this
script only hands the project files in and drains the promise queue between evals, because V8
runs microtasks when an eval returns to Python.
"""

import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SKIP_DIRS = {'.git', 'build', 'node_modules', '.gradle'}
TEXT_EXT = {'.js', '.html', '.java', '.xml', '.gradle', '.md', '.pro', '.properties',
            '.txt', '.yml', '.yaml', '.json'}


def collect():
    files = {}
    exists = {}
    for base, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            full = os.path.join(base, name)
            rel = os.path.relpath(full, ROOT).replace(os.sep, '/')
            exists[rel] = True
            if os.path.splitext(name)[1].lower() in TEXT_EXT:
                try:
                    with open(full, encoding='utf-8') as handle:
                        files[rel] = handle.read()
                except (OSError, UnicodeDecodeError):
                    pass
    return files, exists


def main():
    try:
        from py_mini_racer import MiniRacer
    except Exception as exc:  # pragma: no cover - environment problem
        print('py_mini_racer is not available: %s' % exc)
        return 2

    files, exists = collect()
    with open(os.path.join(ROOT, 'tools/tests/suite.js'), encoding='utf-8') as handle:
        suite_js = handle.read()

    ctx = MiniRacer()
    ctx.eval(suite_js)
    ctx.eval('var __env = ' + json.dumps({'files': files, 'exists': exists}) + ';')
    ctx.eval('SynoFileTestSuite.init(__env);')

    # Each eval returns to Python, which lets V8 run the queued microtasks (the boot chain
    # and every folder load a tap starts). results() runs one phase per call.
    result = {'passed': 0, 'failures': [], 'done': False}
    for _ in range(24):
        ctx.eval('0;')
        raw = ctx.eval('JSON.stringify(SynoFileTestSuite.results());')
        result = json.loads(raw)
        if result.get('done'):
            break

    failures = result.get('failures', [])
    passed = result.get('passed', 0)
    total = passed + len(failures)
    if not failures:
        print('PASS %d checks' % total)
        return 0
    print('FAIL %d of %d checks' % (len(failures), total))
    for failure in failures:
        print('  ✗ ' + failure)
    return 1


if __name__ == '__main__':
    sys.exit(main())
