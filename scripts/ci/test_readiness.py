"""Execute the rendered Android lane with private command recorders."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('renderer', ROOT / 'scripts/ci/pipeline.py')
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class ReadinessTests(unittest.TestCase):
    def test_linux_lane_preserves_apks_api_release_maven_and_fixture_oracles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts/bazel').mkdir(parents=True)
            (root / 'scripts/ci').mkdir()
            (root / 'bin').mkdir()
            log = root / 'commands.jsonl'
            script = '#!' + sys.executable + '\n' + '''import json, os, sys
from pathlib import Path
with open(os.environ['COMMAND_LOG'], 'a') as output:
    output.write(json.dumps({'command': Path(sys.argv[0]).name, 'args': sys.argv[1:]}) + '\\n')
if sys.argv[1:2] == ['-c']: print('0.1.0')
'''
            for name in ['scripts/bazel/sdk.sh', 'scripts/verify-fixtures.sh', 'gradlew', 'bin/python3']:
                path = root / name
                path.write_text(script)
                path.chmod(0o755)
            (root / 'scripts/bazel/ci-cache.sh').write_text(':\n')
            lane = renderer.pipeline({'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_SDK_SPLIT_LANES': '1'})['steps'][0]
            environment = dict(os.environ, **lane['env'], PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
                               JAVA_HOME=str(root / 'java'), ANDROID_HOME=str(root / 'android'), COMMAND_LOG=str(log))
            result = subprocess.run(['bash', '-c', lane['command']], cwd=root, env=environment, text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            commands = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual([item['args'] for item in commands if item['command'] == 'sdk.sh'],
                             [['test'], ['build'], ['example'], ['instrumentation'], ['prepare', '--output', 'build/maven-repository']])
            gradle = [item['args'] for item in commands if item['command'] == 'gradlew']
            self.assertEqual(gradle, [['--no-daemon', '--max-workers=1', ':nuxie-android:test', ':nuxie-android:apiCheck',
                                      ':nuxie-android:lint', ':example-app:assembleDebug'],
                                     ['--no-daemon', '--max-workers=1', 'verifyAndroidReleaseArtifacts']])
            self.assertTrue(any(item['args'] == ['scripts/test-maven-consumer.py', '--version', '0.1.0'] for item in commands))
            self.assertTrue(any(item['command'] == 'verify-fixtures.sh' for item in commands))
            self.assertTrue(any(item['args'] == ['scripts/ci/record-lane.py', '--platform', 'android'] for item in commands))
            self.assertFalse(any(item['command'] in ['xcodebuild', 'pod', 'brew', 'sdkmanager'] for item in commands))


if __name__ == '__main__':
    unittest.main()
