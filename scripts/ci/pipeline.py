#!/usr/bin/env python3
"""Render dormant legacy confidence or explicitly qualified SDK platform lanes."""
import copy
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from cache_policy import cache_environment


def step(label, selected, commands, timeout=90):
    return {'label': label, 'key': selected, 'command': '\n'.join(['set -euo pipefail', 'source scripts/bazel/ci-cache.sh', *commands]) + '\n',
            'agents': {'queue': 'nuxie-pr', 'nuxie-platform': 'macos' if selected == 'ios' else 'linux'},
            'timeout_in_minutes': timeout, 'retry': {'automatic': [{'exit_status': -1, 'limit': 1}]}}


def pipeline(environment=None):
    environment = os.environ if environment is None else environment
    version = environment.get('NUXIE_CI_RUNNER_POLICY_VERSION', '1')
    split = environment.get('NUXIE_SDK_SPLIT_LANES', '0')
    topology = environment.get('NUXIE_CI_RUNNER_TOPOLOGY', 'final')
    if version not in ('', '1', '2') or split not in ('0', '1') or topology not in ('final', 'fallback'):
        raise ValueError('Unsupported runner policy, SDK split or topology setting')
    if split == '1' and (version != '2' or topology != 'final'):
        raise ValueError('SDK split requires installed policy v2 and final 14 GiB Linux topology')
    if version == '2' and topology == 'fallback':
        raise ValueError('SDK native confidence requires final topology; fallback may only compose a refusal')
    document = json.loads((ROOT / '.buildkite/lanes.json').read_text())
    if split == '1':
        document['steps'] = split_steps()
    if version == '2':
        for lane in document['steps']:
            apple = lane['agents'].get('nuxie-platform') == 'macos'
            lane['agents']['nuxie-resources'] = 'mac-64g-v2' if apple else 'linux-14g-v2'
            lane['concurrency'] = 1
            lane['concurrency_group'] = 'nuxie/physical/mac-m2' if apple else 'nuxie/physical/proxmox-heavy'
            lane['concurrency_method'] = 'eager'
            lane['env'] = {'NUXIE_CI_RUNNER_POLICY_VERSION': '2',
                           'NUXIE_CI_RUNNER_PROFILE': 'macos-apple' if apple else 'linux-portable' if lane.get('key') == 'portable' else 'linux-native',
                           'NUXIE_BAZEL_REMOTE_CACHE_MODE': 'off', 'NUXIE_BAZEL_REMOTE_CACHE_WRITER_JSON': '',
                           'NUXIE_BAZEL_REMOTE_CACHE_CREDENTIAL_FILE': '', 'NUXIE_BAZEL_REMOTE_CACHE_ELIGIBLE': '0',
                           'CMAKE_BUILD_PARALLEL_LEVEL': '2', 'GRADLE_OPTS': '-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1 '
                                          '-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m" '
                                          '-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process'}
            for suffix in ('URL', 'DOMAIN', 'CA_FILE', 'TLS_PIN_FILE'):
                lane['env']['NUXIE_BAZEL_REMOTE_CACHE_' + suffix] = ''
            lane['env'].update(cache_environment(environment, lane['env']['NUXIE_CI_RUNNER_PROFILE']))
            lane['artifact_paths'] = list(dict.fromkeys([*lane.get('artifact_paths', []), 'output/pipeline/**/*', *native_artifacts()]))
            lane['command'] = lane['command'].replace('export NUXIE_BAZEL_JOBS=2\n', '').replace('--max-workers=2', '--max-workers=1')
    if split == '1':
        for lane in document['steps']:
            lane['command'] += 'python3 scripts/ci/record-lane.py --platform ' + lane['key'] + '\n'
    return copy.deepcopy(document)


def native_artifacts():
    return ['build/maven-repository/**/*', '**/build/reports/**/*', 'bazel-testlogs/**/*']


def split_steps():
    return [step(':android: SDK, APK and Maven consumers', 'android', [
        'python3 scripts/ci/toolchain.py --platform android',
        'export ANDROID_SDK_ROOT="$ANDROID_HOME"', 'export PATH="$JAVA_HOME/bin:$PATH"',
        'scripts/bazel/sdk.sh test', 'scripts/bazel/sdk.sh build', 'scripts/bazel/sdk.sh example',
        'scripts/bazel/sdk.sh instrumentation', 'scripts/bazel/sdk.sh prepare --output build/maven-repository',
        './gradlew --no-daemon --max-workers=1 :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint :example-app:assembleDebug',
        './gradlew --no-daemon --max-workers=1 verifyAndroidReleaseArtifacts',
        'python3 scripts/test-maven-consumer.py --version "$(python3 -c \'import sys; sys.path.insert(0, "scripts/bazel"); import sdk; print(sdk.declared_version())\')"',
        'scripts/verify-fixtures.sh',
    ])]


if __name__ == '__main__':
    print(json.dumps(pipeline()))
