import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
VERIFIER = ROOT / 'scripts/bazel/runtime_artifacts.py'
spec = importlib.util.spec_from_file_location('runtime_artifacts', VERIFIER)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
bundle_spec = importlib.util.spec_from_file_location('bundle_release', ROOT / 'scripts/bundle-maven-release.py')
bundler = importlib.util.module_from_spec(bundle_spec)
bundle_spec.loader.exec_module(bundler)


class RuntimeSelectionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.tree = self.directory / 'prebuilt'
        self.provenance = self.directory / 'local-artifact.json'
        self.budget = self.directory / 'size-budget.json'
        self.budget.write_text(json.dumps({'maximumBytes': 10000}))
        for name in runtime.FILES:
            path = self.tree / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())
        self.provenance.write_text(json.dumps({
            'mode': 'local', 'sourceCommit': 'a' * 40, 'androidNdk': '29.0.14206865',
            'files': runtime.inspect_tree(self.tree, 10000),
        }))

    def select(self, mode):
        # Override the request runner's =1, including a truly unset environment.
        environment = os.environ.copy()
        environment.pop('NUXIE_RUNTIME_USE_LOCAL', None)
        if mode is not None:
            environment['NUXIE_RUNTIME_USE_LOCAL'] = mode
        return subprocess.run([
            sys.executable, str(VERIFIER), 'select', '--tree', str(self.tree),
            '--provenance', str(self.provenance), '--budget', str(self.budget),
        ], env=environment, capture_output=True, text=True)

    def test_unset_and_one_select_verified_local(self):
        for mode in (None, '1'):
            with self.subTest(mode=mode):
                result = self.select(mode)
                self.assertEqual((result.returncode, result.stdout, result.stderr), (0, 'local\n', ''))

    def test_zero_selects_published_without_local_staging(self):
        self.provenance.unlink()
        result = self.select('0')
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, 'published\n', ''))

    def test_other_values_are_refused(self):
        for mode in ('', '2', 'true'):
            with self.subTest(mode=mode):
                result = self.select(mode)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout, '')
                self.assertEqual(result.stderr, 'NUXIE_RUNTIME_USE_LOCAL must be unset, 1 or 0\n')

    def assert_not_staged(self):
        for mode in (None, '1'):
            with self.subTest(mode=mode):
                result = self.select(mode)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout, '')
                self.assertEqual(result.stderr, runtime.LOCAL_RUNTIME_REQUIRED + '\n')

    def test_missing_provenance_has_the_shared_staging_message(self):
        self.provenance.unlink()
        self.assert_not_staged()

    def test_incomplete_tree_has_the_shared_staging_message(self):
        (self.tree / runtime.FILES[-1]).unlink()
        self.assert_not_staged()

    def test_tampered_header_or_library_has_the_shared_staging_message(self):
        for name in (runtime.FILES[0], runtime.FILES[-1]):
            with self.subTest(name=name):
                path = self.tree / name
                previous = path.read_bytes()
                path.write_bytes(b'tampered')
                self.assert_not_staged()
                path.write_bytes(previous)

    def test_wrong_source_or_ndk_provenance_is_refused(self):
        original = json.loads(self.provenance.read_text())
        for field, value in [('mode', 'published'), ('sourceCommit', ''), ('androidNdk', '28')]:
            with self.subTest(field=field):
                self.provenance.write_text(json.dumps(dict(original, **{field: value})))
                self.assert_not_staged()

    def test_release_bundling_refuses_local_before_reading_or_signing(self):
        for mode in (None, '1', '', 'other'):
            with self.subTest(mode=mode), patch.dict(os.environ, {}, clear=True), \
                 patch.object(bundler.subprocess, 'run') as run:
                if mode is not None:
                    os.environ['NUXIE_RUNTIME_USE_LOCAL'] = mode
                with self.assertRaisesRegex(ValueError, 'NUXIE_RUNTIME_USE_LOCAL=0'):
                    bundler.bundle(self.directory, '1.0.0', self.directory / 'key.asc', 'A' * 40,
                                   self.directory / 'bundle.zip')
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
