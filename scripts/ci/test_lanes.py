"""Real Git receipts and rendered graph contracts; no native tools are invoked."""
import importlib.util
import json
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


renderer = load('renderer', 'pipeline.py')
recorder = load('recorder', 'record-lane.py')
tools = load('toolchain', 'toolchain.py')


class PipelineTests(unittest.TestCase):
    def test_default_retains_entire_legacy_graph(self):
        self.assertEqual(renderer.pipeline({}), json.loads((ROOT / '.buildkite/lanes.json').read_text()))

    def test_split_fails_closed_on_legacy_invalid_and_fallback(self):
        for environment in [{'NUXIE_SDK_SPLIT_LANES': '1'}, {'NUXIE_CI_RUNNER_POLICY_VERSION': '3'},
                            {'NUXIE_SDK_SPLIT_LANES': 'yes'}, {'NUXIE_CI_RUNNER_POLICY_VERSION': '2',
                             'NUXIE_SDK_SPLIT_LANES': '1', 'NUXIE_CI_RUNNER_TOPOLOGY': 'fallback'}]:
            with self.subTest(environment=environment), self.assertRaises(ValueError):
                renderer.pipeline(environment)

    def test_every_v2_job_selects_installed_capability_and_physical_group(self):
        for split in ('0', '1'):
            graph = renderer.pipeline({'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_SDK_SPLIT_LANES': split})
            for lane in graph['steps']:
                apple = lane['agents']['nuxie-platform'] == 'macos'
                self.assertEqual(lane['agents']['nuxie-resources'], 'mac-64g-v2' if apple else 'linux-14g-v2')
                self.assertEqual(lane['env']['NUXIE_CI_RUNNER_POLICY_VERSION'], '2')
                self.assertEqual(lane['env']['NUXIE_BAZEL_REMOTE_CACHE_MODE'], 'off')
                self.assertEqual(lane['env']['NUXIE_BAZEL_REMOTE_CACHE_CREDENTIAL_FILE'], '')
                self.assertNotIn('secrets', lane)
                self.assertIn('-Dorg.gradle.workers.max=1', lane['env']['GRADLE_OPTS'])
                self.assertIn('-Xmx2048m', lane['env']['GRADLE_OPTS'])
                self.assertIn('kotlin.compiler.execution.strategy=in-process', lane['env']['GRADLE_OPTS'])
                self.assertEqual(lane['concurrency'], 1)
                self.assertEqual(lane['concurrency_method'], 'eager')
                self.assertEqual(lane['concurrency_group'], 'nuxie/physical/mac-m2' if apple else 'nuxie/physical/proxmox-heavy')
                self.assertIn('output/pipeline/**/*', lane['artifact_paths'])
            if split == '1':
                self.assertEqual([lane['key'] for lane in graph['steps']], ['android'])
                self.assertTrue(all(not lane.get('depends_on') for lane in graph['steps']))

    def test_qualified_read_is_profile_specific_and_never_grants_writer_secret(self):
        environment = {'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_SDK_SPLIT_LANES': '1',
                       'NUXIE_CI_BAZEL_REMOTE_CACHE_MODE': 'read', 'NUXIE_CI_BAZEL_CACHE_PROFILES': 'linux-native',
                       'NUXIE_BAZEL_REMOTE_CACHE_ELIGIBLE': '1', 'NUXIE_BAZEL_REMOTE_CACHE_DOMAIN': 'sha256:' + 'a' * 64,
                       'NUXIE_BAZEL_REMOTE_CACHE_URL': 'https://cache.example.test',
                       'NUXIE_BAZEL_REMOTE_CACHE_CA_FILE': '/opt/cache/leaf.pem',
                       'NUXIE_BAZEL_REMOTE_CACHE_TLS_PIN_FILE': '/opt/cache/pin.json',
                       'NUXIE_BAZEL_REMOTE_CACHE_CREDENTIAL_FILE': '/home/ci/reader.json',
                       'NUXIE_BAZEL_REMOTE_CACHE_WRITER_JSON': 'ambient secret must not reach lane'}
        for lane in renderer.pipeline(environment)['steps']:
            native = lane['env']['NUXIE_CI_RUNNER_PROFILE'] == 'linux-native'
            self.assertEqual(lane['env']['NUXIE_BAZEL_REMOTE_CACHE_MODE'], 'read' if native else 'off')
            self.assertEqual(lane['env']['NUXIE_BAZEL_REMOTE_CACHE_WRITER_JSON'], '')
            self.assertNotIn('secrets', lane)
            if not native:
                self.assertEqual(lane['env']['NUXIE_BAZEL_REMOTE_CACHE_CREDENTIAL_FILE'], '')
                self.assertEqual(lane['env']['NUXIE_BAZEL_REMOTE_CACHE_TLS_PIN_FILE'], '')

    def test_actual_cli_emits_json_and_rejects_invalid_activation(self):
        environment = dict(os.environ, NUXIE_CI_RUNNER_POLICY_VERSION='2', NUXIE_SDK_SPLIT_LANES='1', NUXIE_CI_RUNNER_TOPOLOGY='final')
        result = subprocess.run(['python3', '-B', str(HERE / 'pipeline.py')], env=environment, text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([lane['key'] for lane in json.loads(result.stdout)['steps']], ['android'])
        environment['NUXIE_CI_RUNNER_TOPOLOGY'] = 'fallback'
        result = subprocess.run(['python3', '-B', str(HERE / 'pipeline.py')], env=environment, text=True, capture_output=True, timeout=15)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.patch = patch.object(recorder, 'ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.pin = 'a' * 40
        self.android = 'build/maven-repository/sdk-artifacts.json'
        (self.root / '.gitignore').write_text('output/\n.native/\nandroid/\nbuild/\n')
        for name, data in {'runtime/artifact.json': '{}', 'runtime/android-toolchain.properties': 'ndkVersion=29.0.14206865\n'}.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(data)
        self.commit()
        self.environment = {'BUILDKITE_COMMIT': self.revision, 'BUILDKITE_BUILD_ID': '00000000-0000-4000-8000-000000000001',
                            'BUILDKITE_JOB_ID': '00000000-0000-4000-8000-000000000002'}

    def commit(self):
        subprocess.run(['git', 'add', '.'], cwd=self.root, check=True)
        subprocess.run(['git', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@nuxie.test', 'commit', '-qm', 'fixture'], cwd=self.root, check=True)
        self.revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.root, text=True).strip()

    def product(self, sdk='android'):
        path = self.root / (self.android if sdk == 'android' else '.native/artifacts/ios/sdk-artifacts.json')
        path.parent.mkdir(parents=True, exist_ok=True)
        data = b'independent native product'
        (path.parent / 'product.a').write_bytes(data)
        value = {'schemaVersion': 1, 'sdk': sdk, 'sourceRevision': self.revision, 'sourceDirty': False,
                 'artifacts': [{'path': 'product.a', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}]}
        if sdk == 'ios':
            value['products'] = [{'platform': 'ios-simulator', 'configuration': 'Debug', 'architectures': [platform.machine()]}]
        path.write_text(json.dumps(value))
        return path, value

    def test_portable_record_has_exact_job_and_source_without_native_fallback(self):
        receipt = recorder.record('portable', self.environment)
        self.assertEqual(receipt['sourceRevision'], self.revision)
        self.assertEqual(receipt['jobId'], self.environment['BUILDKITE_JOB_ID'])
        self.assertEqual(receipt['buildId'], self.environment['BUILDKITE_BUILD_ID'])
        self.assertEqual(receipt['nativeProducts'], {})
        self.assertEqual(receipt['prerequisiteJobs'], [])

    def test_selected_original_receipt_is_bound_to_job_pin_and_inventory(self):
        manifest, original = self.product()
        receipt = recorder.record('android', self.environment)
        native = receipt['nativeProducts']['android']
        self.assertEqual(native['manifestSha256'], hashlib.sha256(manifest.read_bytes()).hexdigest())
        self.assertEqual(native['artifacts'], original['artifacts'])
        self.assertEqual(native['sourceRevision'], original['sourceRevision'])
        self.assertEqual(list(receipt['nativeProducts']), ['android'])
        self.assertEqual(receipt['configuration'], 'Debug')
        self.assertEqual(receipt['architecture'], platform.machine())

    def test_missing_wrong_pin_or_mutated_product_fails_without_substitution(self):
        with self.assertRaises(ValueError):
            recorder.record('android', self.environment)
        path, value = self.product()
        value['sourceRevision'] = 'b' * 40
        path.write_text(json.dumps(value))
        with self.assertRaises(ValueError):
            recorder.record('android', self.environment)
        path, _ = self.product()
        (path.parent / 'product.a').write_bytes(b'tampered same size product')
        with self.assertRaises(ValueError):
            recorder.record('android', self.environment)

    def test_source_and_job_drift_fail_closed(self):
        for variable, value in [('BUILDKITE_COMMIT', 'b' * 40), ('BUILDKITE_JOB_ID', ''), ('BUILDKITE_BUILD_ID', 'not-a-job')]:
            with self.subTest(variable=variable), self.assertRaises(ValueError):
                recorder.record('portable', dict(self.environment, **{variable: value}))
        (self.root / '.gitignore').write_text('changed source')
        with self.assertRaisesRegex(ValueError, 'modified tracked source'):
            recorder.record('portable', self.environment)


if __name__ == '__main__':
    unittest.main()
