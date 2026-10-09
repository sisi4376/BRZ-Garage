"""Guard the v4.0.0 ZD8 methods that ZC6 work must not change.

Source fingerprints supplement executable transport/cache tests: the user asked
to retain these published methods, not merely obtain similar sample outputs.
Whitespace/comments and optional perf instrumentation are not behavior changes.
The manifest was generated from the v4.0.0 Git tag, not the working directory.
"""
import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def function(source, name):
    match = re.search(r'^[\w *]+?\b' + name + r'\([^;]*?\)\s*\{', source, re.M)
    assert match, name
    begin = source.index('{', match.start())
    depth = 0
    for index in range(begin, len(source)):
        if source[index] == '{':
            depth += 1
        elif source[index] == '}':
            depth -= 1
            if depth == 0:
                return source[match.start():index+1]
    raise AssertionError(name)


def extract(source, selector):
    if selector == 'file':
        return source
    if selector == 'zd8_profile':
        pos = source.index('.name = "ZD8"')
        begin = source.rfind('    {', 0, pos)
        return source[begin:source.index('\n    },', pos)+7]
    if selector == 'gear_timing':
        return '\n'.join(re.findall(r'^#define (?:DIRECT_GEAR_STALE_MS|GEAR_\w+)\s+\d+', source, re.M))
    if selector == 'oil_filter':
        body = function(source, 'default_on_parsed_oil_temp')
        return body[body.index('    const vehicle_override_t *ov ='):]
    return function(source, selector)


def digest(source):
    source = re.sub(r'/\*[\s\S]*?\*/|//[^\n]*', '', source)
    # Performance work is orthogonal and explicitly preserved in the workspace.
    source = re.sub(r'\buint32_t\s+start\s*=\s*perf_now\(\);', '', source)
    source = re.sub(r'\bperf_emit\([^;]*\);', '', source)
    return hashlib.sha256(re.sub(r'\s+', '', source).encode()).hexdigest()


class Zd8ReleaseContractTest(unittest.TestCase):
    def test_published_zd8_methods_and_parameters_are_unchanged(self):
        manifest = json.loads((ROOT / 'tests/fixtures/zd8_v4_0_0_methods.json').read_text())
        for item in manifest['methods']:
            with self.subTest(path=item['path'], method=item['selector']):
                source = (ROOT / item['path']).read_text(encoding='utf-8')
                self.assertEqual(digest(extract(source, item['selector'])), item['sha256'],
                                 'ZC6 work must not change this released ZD8 method')


if __name__ == '__main__':
    unittest.main()
