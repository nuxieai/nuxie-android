"""Execute the SDK wrapper against a disposable installed-boundary fixture."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.checkout = self.root / 'checkout'
        (self.checkout / 'scripts/bazel').mkdir(parents=True)
        (self.checkout / 'scripts/ci').mkdir()
        for name in ('bazel.sh', 'ci-cache.sh', 'cache.py', 'remote-cache.sh', 'remote-cache.py', 'cache-tls-pin.py'):
            shutil.copyfile(ROOT / 'scripts/bazel' / name, self.checkout / 'scripts/bazel' / name)
        helper = self.root / 'installed.py'
        helper.write_text('''import json, os, subprocess, sys
if sys.argv[1] == 'environment':
    if os.environ.get('POLICY_STATUS'): sys.exit(int(os.environ['POLICY_STATUS']))
    values = {'NUXIE_NODE_OPTIONS': '--max-old-space-size=3072', 'NUXIE_BAZEL_JOBS': 2, 'NUXIE_BAZEL_CPU': 4, 'NUXIE_BAZEL_MEMORY': 7168,
              'NUXIE_BAZEL_LOCAL_TEST_JOBS': 1, 'NUXIE_BAZEL_BOOTSTRAP_JOBS': 2,
              'NUXIE_BAZEL_BATCH': 1, 'NUXIE_BAZEL_CACHE_DIR': os.environ['OWNED_CACHE'],
              'NUXIE_BAZEL_OUTPUT_USER_ROOT': os.environ['OWNED_CACHE'] + '/outputs',
              'NUXIE_BAZEL_REMOTE_CACHE_MODE': 'off'}
    for key, value in values.items(): print('export ' + key + '=' + str(value))
elif sys.argv[1:3] == ['run', '--']:
    with open(os.environ['CALLS'], 'w') as log: json.dump(sys.argv[4:], log)
    with open(os.environ['CALLS'] + '.cwd', 'w') as log: log.write(os.getcwd())
    sys.exit(int(os.environ.get('STATUS', '0')))
else: sys.exit(99)
''')
        # The fixture substitutes only filesystem installation trust. Production
        # has fixed root-controlled paths and no configurable trust bypass.
        shell = (ROOT / 'scripts/ci/runner-policy.sh').read_text()
        shell += '\nnuxie_runner_policy_verify() { NUXIE_RUNNER_POLICY_HELPER=' + str(helper) + '; }\n'
        (self.checkout / 'scripts/ci/runner-policy.sh').write_text(shell)
        self.bazel = self.root / 'bazel'
        self.calls = self.root / 'calls.json'
        self.bazel.write_text('#!/usr/bin/env python3\nimport json,os,sys\nwith open(os.environ["CALLS"],"w") as log: json.dump(sys.argv[1:],log)\nsys.exit(int(os.environ.get("STATUS","0")))\n')
        self.bazel.chmod(0o755)
        self.environment = {**os.environ, 'NUXIE_CI_RUNNER_POLICY_VERSION': '2',
                            'NUXIE_BAZEL_BIN': str(self.bazel), 'CALLS': str(self.calls),
                            'OWNED_CACHE': str(self.root / 'owned-cache'),
                            'NUXIE_BAZEL_JOBS': '100', 'NUXIE_BAZEL_CPU': '100'}

    def run_wrapper(self, arguments):
        return subprocess.run(['/bin/bash', str(self.checkout / 'scripts/bazel/bazel.sh'), *arguments],
                              env=self.environment, text=True, capture_output=True, timeout=10)

    def test_installed_limits_follow_configs_and_keep_application_boundary(self):
        result = self.run_wrapper(['run', '--config=caller', '//fixture', '--', '--jobs=100'])
        self.assertEqual(result.returncode, 0, result.stderr)
        received = json.loads(self.calls.read_text())
        boundary = received.index('--')
        self.assertEqual(received[boundary:], ['--', '--jobs=100'])
        for argument in ('--jobs=2', '--local_resources=cpu=4', '--local_resources=memory=7168',
                         '--local_test_jobs=1', '--repo_env=NUXIE_BAZEL_BOOTSTRAP_JOBS=2', '--remote_cache='):
            self.assertIn(argument, received[:boundary])
            self.assertGreater(received.index(argument), received.index('--config=caller'))
        self.assertTrue(any(argument.startswith('--profile=' + str(self.checkout / 'output/pipeline/bazel')) for argument in received))
        self.assertTrue(any(argument.startswith('--build_event_json_file=') for argument in received))
        self.assertIn('--disk_cache=' + self.environment['OWNED_CACHE'] + '/actions', received)

    def test_caller_cannot_replace_budgets_outputs_or_evidence(self):
        for option in ('--jobs=100', '-j100', '--local_resources=memory=65536', '--disk_cache=/other',
                       '--repo_env=NUXIE_BAZEL_BOOTSTRAP_JOBS=100', '--profile=/other'):
            with self.subTest(option=option):
                result = self.run_wrapper(['build', '//fixture', option])
                self.assertEqual(result.returncode, 1)
                self.assertFalse(self.calls.exists())
        self.assertEqual(self.run_wrapper(['--output_user_root=/other', 'build', '//fixture']).returncode, 1)
        self.assertFalse(self.calls.exists())

    def test_legacy_keeps_caller_option_order_without_installed_policy(self):
        self.environment.update(NUXIE_CI_RUNNER_POLICY_VERSION='1', NUXIE_BAZEL_REMOTE_CACHE_MODE='off')
        result = self.run_wrapper(['build', '//fixture', '--jobs=9'])
        self.assertEqual(result.returncode, 0, result.stderr)
        received = json.loads(self.calls.read_text())
        self.assertIn('--jobs=100', received)
        self.assertEqual(received[-1], '--jobs=9')
        self.assertLess(received.index('--jobs=100'), received.index('--jobs=9'))
        self.assertFalse(any(argument.startswith('--profile=') for argument in received))

    def test_internal_fixture_workspaces_keep_distinct_output_identity(self):
        for name in ('ordinary', 'schema-fixture'):
            workspace = self.checkout / name
            workspace.mkdir()
            self.environment['NUXIE_BAZEL_WORKSPACE'] = str(workspace)
            result = self.run_wrapper(['build', '//fixture'])
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(str(self.calls) + '.cwd').read_text(), str(workspace))
            received = json.loads(self.calls.read_text())
            self.assertIn('--output_user_root=' + self.environment['OWNED_CACHE'] + '/outputs', received)
            self.assertFalse(any(value.startswith('--output_base=') for value in received))
        self.calls.unlink()
        self.environment['NUXIE_BAZEL_WORKSPACE'] = str(self.root)
        self.assertNotEqual(self.run_wrapper(['build', '//fixture']).returncode, 0)
        self.assertFalse(self.calls.exists())
        symlink = self.checkout / 'alias'
        symlink.symlink_to(self.checkout / 'schema-fixture', target_is_directory=True)
        self.environment['NUXIE_BAZEL_WORKSPACE'] = str(symlink)
        self.assertNotEqual(self.run_wrapper(['build', '//fixture']).returncode, 0)
        self.assertFalse(self.calls.exists())

    def test_ci_setup_applies_trusted_node_heap_and_stops_on_policy_failure(self):
        command = 'source "$1" || exit "$?"; printf "%s" "$NODE_OPTIONS"'
        self.environment['NODE_OPTIONS'] = '--max-old-space-size=65536'
        result = subprocess.run(['/bin/bash', '-c', command, 'fixture', str(self.checkout / 'scripts/bazel/ci-cache.sh')],
                                env=self.environment, text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, '--max-old-space-size=3072')
        self.environment['POLICY_STATUS'] = '42'
        result = subprocess.run(['/bin/bash', '-c', command, 'fixture', str(self.checkout / 'scripts/bazel/ci-cache.sh')],
                                env=self.environment, text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 42)
        self.assertEqual(result.stdout, '')

    def test_binary_failure_preserves_exit_status(self):
        self.environment['STATUS'] = '17'
        self.assertEqual(self.run_wrapper(['test', '//fixture']).returncode, 17)



if __name__ == '__main__':
    unittest.main()
