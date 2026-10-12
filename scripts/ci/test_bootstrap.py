"""Evaluate the actual Buildkite conditionals and execute its upload command."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def bootstrap_steps():
    # Read the small owning YAML subset without an extra dependency. Actual
    # conditions use Buildkite's comparison/boolean subset shared with JS.
    steps = []
    section = None
    command = False
    for line in (ROOT / '.buildkite/pipeline.yml').read_text().splitlines():
        if line.startswith('  - label:'):
            steps.append({'agents': {}, 'env': {}})
            section = None
            command = False
        elif line.startswith('    command: |'):
            steps[-1]['command'] = ''
            command = True
        elif command and line.startswith('      '):
            steps[-1]['command'] += line[6:] + '\n'
        elif line.startswith('    '):
            command = False
            value = re.match(r'    ([a-z_]+): ?(.*)', line)
            if value:
                key, text = value.groups()
                if key in ('agents', 'env'):
                    section = key
                else:
                    steps[-1][key] = text[1:-1] if text.startswith('"') and text.endswith('"') else text
                    section = None
            elif line.startswith('      ') and section:
                key, value = line.strip().split(': ', 1)
                steps[-1][section][key] = value.strip('"')
    return steps


class BootstrapTests(unittest.TestCase):
    def selected(self, environment):
        script = '''const [steps, env] = JSON.parse(process.argv[1]);
const build = {env: key => env[key] ?? null};
process.stdout.write(JSON.stringify(steps.filter(step => Function('build', `return (${step.if})`)(build))));'''
        result = subprocess.check_output([shutil.which('node'), '-e', script, json.dumps([bootstrap_steps(), environment])], text=True, timeout=15)
        return json.loads(result)

    def test_exactly_one_composer_covers_missing_legacy_final_fallback_and_invalid(self):
        for environment, key in [({}, 'compose-lanes'), ({'NUXIE_CI_RUNNER_POLICY_VERSION': '1'}, 'compose-lanes'),
                                 ({'NUXIE_CI_RUNNER_POLICY_VERSION': '2'}, 'compose-lanes-v2'),
                                 ({'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_CI_RUNNER_TOPOLOGY': 'final'}, 'compose-lanes-v2'),
                                 ({'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_CI_RUNNER_TOPOLOGY': 'fallback'}, 'compose-lanes-fallback-v2'),
                                 ({'NUXIE_CI_RUNNER_POLICY_VERSION': '3'}, 'compose-lanes'),
                                 ({'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_CI_RUNNER_TOPOLOGY': 'bad'}, 'compose-lanes-v2')]:
            with self.subTest(environment=environment):
                selected = self.selected(environment)
                self.assertEqual([step['key'] for step in selected], [key])
                if environment.get('NUXIE_CI_RUNNER_POLICY_VERSION') == '2':
                    step = selected[0]
                    fallback = environment.get('NUXIE_CI_RUNNER_TOPOLOGY') == 'fallback'
                    self.assertEqual(step['env']['NUXIE_CI_RUNNER_PROFILE'], 'temporary-light' if fallback else 'linux-portable')
                    self.assertEqual(step['agents']['nuxie-resources'], 'linux-light-6g-v2' if fallback else 'linux-14g-v2')
                    self.assertEqual(step['agents']['nuxie-platform'], 'linux')
                    self.assertEqual(step['concurrency_group'], 'nuxie/physical/proxmox-heavy')
                    self.assertEqual(step['concurrency_method'], 'eager')
                    self.assertEqual(step['env']['NUXIE_BAZEL_REMOTE_CACHE_MODE'], 'off')

    def test_invalid_and_fallback_composer_command_fail_instead_of_uploading_native_graph(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            agent = directory / 'buildkite-agent'
            agent.write_text('#!/bin/sh\ncat > "$UPLOAD_RECORD"\n')
            agent.chmod(0o755)
            for environment in [{'NUXIE_CI_RUNNER_POLICY_VERSION': '3'},
                                {'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_CI_RUNNER_TOPOLOGY': 'bad'},
                                {'NUXIE_CI_RUNNER_POLICY_VERSION': '2', 'NUXIE_CI_RUNNER_TOPOLOGY': 'fallback'}]:
                step = self.selected(environment)[0]
                path = directory / 'uploaded.json'
                path.unlink(missing_ok=True)
                env = dict(os.environ, **environment, PATH=str(directory) + os.pathsep + os.environ['PATH'], UPLOAD_RECORD=str(path))
                result = subprocess.run(['bash', '-c', step['command']], cwd=ROOT, env=env, text=True, capture_output=True, timeout=15)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(path.read_text() if path.exists() else '', '')


if __name__ == '__main__':
    unittest.main()
