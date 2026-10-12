"""Observe bounded JVM settings at the real Gradle wrapper's Java boundary."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('renderer_for_gradle', ROOT / 'scripts/ci/pipeline.py')
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class GradleBudgetTests(unittest.TestCase):
    def test_v2_unsplit_graph_executes_one_worker_and_legacy_keeps_two(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / 'calls.jsonl'
            wrapper = root / 'gradlew'
            wrapper.write_text('#!' + sys.executable + '\nimport json,os,sys\nwith open(os.environ["JAVA_CALLS"], "a") as output: output.write(json.dumps(sys.argv[1:]) + "\\n")\n')
            wrapper.chmod(0o755)
            for version, workers in [('1', '2'), ('2', '1')]:
                lane = renderer.pipeline({'NUXIE_CI_RUNNER_POLICY_VERSION': version, 'NUXIE_SDK_SPLIT_LANES': '0'})['steps'][0]
                # Execute the actual renderer's consumer commands, not matching its source text.
                commands = [line for line in lane['command'].splitlines() if line.startswith('./gradlew ')]
                log.unlink(missing_ok=True)
                result = subprocess.run(['/bin/bash', '-c', 'set -euo pipefail\n' + '\n'.join(commands)], cwd=root,
                                        env=dict(os.environ, JAVA_CALLS=str(log)), text=True, capture_output=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stderr)
                actual = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertEqual(len(actual), 2)
                self.assertTrue(all('--max-workers=' + workers in arguments for arguments in actual))
                self.assertEqual(actual[0][-3:], [':nuxie-android:test', ':nuxie-android:apiCheck', ':nuxie-android:lint'])
                self.assertEqual(actual[1][-1], 'verifyAndroidReleaseArtifacts')

    def test_v2_nested_wrapper_receives_one_worker_and_single_bounded_heap_value(self):
        original = ROOT / ('gradlew' if ROOT.name == 'nuxie-android' else 'examples/bare/android/gradlew')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            wrapper = root / 'gradlew'
            shutil.copy2(original, wrapper)
            java = root / 'java/bin/java'
            java.parent.mkdir(parents=True)
            calls = root / 'calls.json'
            java.write_text('#!' + sys.executable + '\nimport json,os,sys\nfrom pathlib import Path\nPath(os.environ["JAVA_CALLS"]).write_text(json.dumps(sys.argv[1:]))\n')
            java.chmod(0o755)
            lane = next(lane for lane in renderer.pipeline({'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_SDK_SPLIT_LANES': '1'})['steps'] if lane['key'] == 'android')
            env = dict(os.environ, **lane['env'], JAVA_HOME=str(java.parent.parent), JAVA_CALLS=str(calls))
            result = subprocess.run(['/bin/bash', str(wrapper), ':consumer:assembleDebug'], cwd=root, env=env,
                                    text=True, capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            arguments = json.loads(calls.read_text())
            self.assertIn('-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m', arguments)
            self.assertEqual(sum(argument.startswith('-Dorg.gradle.jvmargs=') for argument in arguments), 1)
            self.assertIn('-Dorg.gradle.workers.max=1', arguments)
            self.assertIn('-Dorg.gradle.parallel=false', arguments)
            self.assertIn('-Dorg.gradle.daemon=false', arguments)
            self.assertIn('-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process', arguments)
            self.assertEqual(arguments[-1], ':consumer:assembleDebug')


if __name__ == '__main__':
    unittest.main()
