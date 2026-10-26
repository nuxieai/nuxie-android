#!/usr/bin/env python3
"""Submit this SDK's existing frontend to the shared, capability-selected pool."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SDK = 'android'
OUTPUTS = ['build/bazel-sdk', 'nuxie-android/build/outputs', 'nuxie-android/build/reports', 'nuxie-android/build/test-results', 'example-app/build/outputs']


def selection(mode, arguments):
    selected = 'android' if mode == 'prepare-native' and SDK in ('react-native', 'unity') else 'all'
    for i, arg in enumerate(arguments):
        if arg == '--platform' and i + 1 < len(arguments): selected = arguments[i + 1]
        elif arg.startswith('--platform='): selected = arg.split('=', 1)[1]
    command = arguments[0] if arguments else ''
    requirements = set()
    apple = False
    if SDK == 'android':
        requirements.add('android')
    elif mode == 'sdk':
        if command == 'build-native':
            apple = selected in ('ios', 'all')
            if selected in ('android', 'all'): requirements.add('android')
        elif command.endswith('-ios'):
            apple = True
        elif command.endswith('-android'):
            requirements.add('android')
    elif mode == 'check-ios':
        apple = True
    else:
        apple = selected in ('ios', 'all')
        if mode in ('prepare-native', 'prepare-android') and SDK == 'unity': apple = False
        if mode == 'prepare-android': apple = False
        if selected in ('android', 'all'): requirements.add('android')
        if mode == 'check':
            requirements.update({'react-native': ['node'], 'flutter': [],
                'godot': ['godot'], 'unity': ['unity'], 'ue': ['unreal']}.get(SDK, []))
    if mode == 'sdk' and command == 'instrumentation-run':
        serial = next((arguments[i + 1] for i, a in enumerate(arguments[:-1]) if a == '--serial'), None)
        serial = serial or next((a.split('=', 1)[1] for a in arguments if a.startswith('--serial=')), None)
        if not serial: raise ValueError('Android device serial is required')
        requirements.add('android-device@' + serial)
    if (mode == 'gradle' or (mode == 'check' and 'android' in requirements)
            or (SDK == 'android' and mode == 'sdk' and command in ('check', 'lint', 'api-check', 'providers'))):
        requirements.add('java17')
    if apple: requirements.add('xcode')
    return ('macos' if apple else 'portable'), sorted(requirements)


def dispatch(mode, arguments, *, output=None):
    if os.environ.get('_NUXIE_POOL_NESTED') == '1': return None
    helper = Path.home() / '.local/share/nuxie-homelab/sdk-task.py'
    configured = (Path.home() / '.config/nuxie/homelab-cluster.json').is_file()
    if not helper.is_file():
        if configured and os.environ.get('NUXIE_HOMELAB') != '0' and os.environ.get('NUXIE_EXECUTION') != 'local':
            raise RuntimeError('Configured pool needs its SDK task client installed')
        return None
    spec = importlib.util.spec_from_file_location('pool_sdk_task', helper)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    platform, requirements = selection(mode, arguments)
    names = {name: ROOT / name for name in OUTPUTS}
    if output is not None:
        names['build/pool-prepared'] = Path(output).resolve()
        names.pop('build/bazel-sdk', None)
    frontend = 'scripts/bazel/sdk.py' if mode == 'sdk' else 'scripts/pool.py'
    args = list(arguments) if mode == 'sdk' else [mode, *arguments]
    if output is not None and not any(a == '--output' or a.startswith('--output=') for a in args):
        args += ['--output', str(output)]
    return module.route(ROOT, args, frontend=frontend, platform=platform,
                        requirements=requirements, outputs=names)


def main():
    mode, *arguments = sys.argv[1:]
    status = dispatch(mode, arguments)
    if status is not None: return status
    if mode == 'gradle':
        command = [str(ROOT / 'gradlew'), *arguments, '--no-daemon', '--max-workers=' + os.environ.get('NUXIE_BAZEL_CPU', '2')]
    elif SDK == 'react-native' and mode == 'check':
        # Dependencies belong to the selected host's disposable snapshot.
        subprocess.run(['pnpm', '--ignore-workspace', 'install', '--frozen-lockfile'], cwd=ROOT, check=True)
        command = ['node', 'scripts/check.mjs', *arguments]
    else:
        command = [sys.executable, 'scripts/' + mode + '.py', *arguments]
    return subprocess.run(command, cwd=ROOT, env=dict(os.environ, _NUXIE_POOL_NESTED='1')).returncode


if __name__ == '__main__':
    raise SystemExit(main())
