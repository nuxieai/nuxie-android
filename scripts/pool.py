#!/usr/bin/env python3
"""Submit this SDK's existing frontend to the shared, capability-selected pool."""
import importlib.util
import os
import shlex
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SDK = 'android'
OUTPUTS = ['build/bazel-sdk', 'nuxie-android/build/outputs', 'nuxie-android/build/reports', 'nuxie-android/build/test-results', 'example-app/build/outputs', 'build/maven-repository', 'build/reports', 'build/test-results', 'build/outputs']


def selection(mode, arguments, command=None):
    selected = 'android' if mode == 'prepare-native' and SDK in ('react-native', 'unity') else 'all'
    for i, arg in enumerate(arguments):
        if arg == '--platform' and i + 1 < len(arguments): selected = arguments[i + 1]
        elif arg.startswith('--platform='): selected = arg.split('=', 1)[1]
    command = command or (arguments[0] if arguments else '')
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
        serial = next((arguments[i + 1] for i, a in enumerate(arguments[:-1]) if a in ('--serial', '--device')), None)
        serial = serial or next((a.split('=', 1)[1] for a in arguments if a.startswith('--serial=')), None)
        if not serial: raise ValueError('Android device serial is required')
        requirements.add('android-device@' + serial)
    if (mode == 'gradle' or (mode == 'check' and 'android' in requirements)
            or (SDK == 'android' and mode == 'sdk' and command in ('check', 'lint', 'api-check', 'providers'))):
        requirements.add('java17')
    if mode == 'gradle' and any('connected' in a.lower() for a in arguments if not a.startswith('-')):
        serial = os.environ.get('ANDROID_SERIAL')
        if not serial: raise ValueError('ANDROID_SERIAL is required for connected pool tests')
        requirements.add('android-device@' + serial)
    if SDK == 'android' and (command in ('host-render', 'host-render-smoke') or (mode == 'gradle' and any('hostrender' in a.lower() for a in arguments))):
        requirements.add('native-host@' + sys.platform + '-' + os.uname().machine)
        return ('macos' if sys.platform == 'darwin' else 'linux'), sorted(requirements)
    if mode.startswith('script-'):
        requirements.add('java17')
        if mode in ('script-test-talkback', 'script-test-minified-font-fallback', 'script-test-minified-native-logging', 'script-test-startup-process-death'):
            serial = next((arguments[i+1] for i,a in enumerate(arguments[:-1]) if a in ('--serial', '--device')), None)
            if mode == 'script-test-minified-native-logging' and arguments: serial = arguments[0]
            if not serial: raise ValueError('A device serial is required for this pool qualification')
            requirements.add('android-device@' + serial)
    if mode == 'prove-runtime-install-recovery': requirements.add('java17')
    if apple: requirements.add('xcode')
    return ('macos' if apple else 'portable'), sorted(requirements)


def dispatch(mode, arguments, *, output=None, command=None, input_files=None, environment=None):
    if (os.environ.get('_NUXIE_POOL_NESTED') == '1' or os.environ.get('NUXIE_HOMELAB') == '0'
            or os.environ.get('NUXIE_EXECUTION') == 'local' or '--help' in arguments or '-h' in arguments): return None
    helper = Path.home() / '.local/share/nuxie-homelab/sdk-task.py'
    configured = (Path.home() / '.config/nuxie/homelab-cluster.json').is_file()
    if not helper.is_file():
        if configured and os.environ.get('NUXIE_HOMELAB') != '0' and os.environ.get('NUXIE_EXECUTION') != 'local':
            raise RuntimeError('Configured pool needs its SDK task client installed')
        return None
    spec = importlib.util.spec_from_file_location('pool_sdk_task', helper)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    platform, requirements = selection(mode, arguments, command)
    names = {name: ROOT / name for name in OUTPUTS}
    if output is not None:
        names['build/pool-prepared'] = Path(output).resolve()
        names.pop('build/bazel-sdk', None)
    frontend = 'scripts/bazel/sdk.py' if mode == 'sdk' else 'scripts/pool.py'
    args = list(arguments) if mode == 'sdk' else [mode, *arguments]
    if output is not None and not any(a == '--output' or a.startswith('--output=') for a in args):
        args += ['--output', str(output)]
    input_files = dict(input_files or {}); environment = dict(environment or {})
    if mode == 'gradle':
        if os.environ.get('ANDROID_SERIAL'): environment['ANDROID_SERIAL'] = os.environ['ANDROID_SERIAL']
        if not any(a in ('-p', '--project-dir') or a.startswith('--project-dir=') for a in args):
            args += ['--project-dir', str(Path.cwd().resolve())]

        for index, argument in enumerate(args):
            project = args[index + 1] if argument in ('-p', '--project-dir') and index + 1 < len(args) else argument.split('=', 1)[1] if argument.startswith('--project-dir=') else None
            if not project: continue
            selected = Path(project).expanduser().resolve(strict=True)
            # Generated consumers use source paths from their owning script.
            # Relocate the project itself and its local Maven repository.
            if not selected.is_relative_to(ROOT): raise ValueError('Generated pool Gradle projects must be inside the SDK checkout')
            relative = selected.relative_to(ROOT).as_posix()
            if relative == '.': continue
            for file in selected.rglob('*'):
                if file.is_file() and not any(p in ('build', '.gradle') for p in file.relative_to(selected).parts):
                    input_files[(Path(relative) / file.relative_to(selected)).as_posix()] = file
            names[relative + '/build'] = selected / 'build'
            if argument in ('-p', '--project-dir'): args[index + 1] = str(selected)
            else: args[index] = '--project-dir=' + str(selected)
        repository = ROOT / 'build/maven-repository'
        if repository.is_dir(): input_files['build/maven-repository', 'build/reports', 'build/test-results', 'build/outputs'] = repository
        for project in ROOT.iterdir():
            if project.is_dir() and (project / 'build.gradle.kts').is_file():
                for directory in ('outputs', 'reports', 'test-results'):
                    names[project.name + '/build/' + directory] = project / 'build' / directory
        if any(a.rsplit(':', 1)[-1] == 'apiDump' for a in args):
            names['nuxie-android/api/nuxie-android.api'] = ROOT / 'nuxie-android/api/nuxie-android.api'
        if any('hostrender' in a.lower() for a in args):
            spec = importlib.util.spec_from_file_location('sdk_host_pool', ROOT / 'scripts/bazel/sdk.py')
            host = importlib.util.module_from_spec(spec); spec.loader.exec_module(host)
            files, variables = host.host_inputs()
            input_files.update(files); environment.update(variables)
            for index, argument in enumerate(args):
                if not (argument == '--args' or argument.startswith('--args=')): continue
                position = index + 1 if argument == '--args' else index
                values = shlex.split(args[position] if argument == '--args' else argument.split('=', 1)[1])
                for item, value in enumerate(values[:-1]):
                    if value == '--input':
                        selected = Path(values[item + 1]).expanduser().resolve(strict=True)
                        input_files['.nuxie/sdk-inputs/render-input'] = selected; values[item + 1] = str(selected)
                    elif value == '--output':
                        selected = Path(values[item + 1]).expanduser().resolve()
                        names['build/pool-host-frames'] = selected; values[item + 1] = str(selected)
                args[position] = ('' if argument == '--args' else '--args=') + shlex.join(values)

    if mode.startswith('script-'):
        for name in ('build/maven-repository', 'nuxie-android/build/outputs/aar/nuxie-android-release.aar'):
            if (ROOT / name).exists(): input_files[name] = ROOT / name
        for directory in ('maven-consumer', 'test-store-lint-consumer', 'minified-native-logging-consumer', 'maven-signing-test', 'process-death'):
            names['build/' + directory] = ROOT / 'build' / directory
        for index, argument in enumerate(args):
            if argument == '--repository' and index + 1 < len(args) and args[index + 1].startswith('file:'):
                from urllib.parse import urlsplit, unquote
                selected = Path(unquote(urlsplit(args[index + 1]).path)).resolve(strict=True)
                input_files['.nuxie/sdk-inputs/maven-repository'] = selected
    return module.route(ROOT, args, frontend=frontend, platform=platform,
                        requirements=requirements, outputs=names, input_files=input_files, environment=environment,
                        merge_outputs=[name for name in names if not (SDK == 'react-native' and name == 'dist')])


def main():
    mode, *arguments = sys.argv[1:]
    status = dispatch(mode, arguments)
    if status is not None: return status
    if mode == 'gradle':
        command = [str(ROOT / 'gradlew'), *arguments, '--no-daemon', '--max-workers=' + os.environ.get('NUXIE_BAZEL_CPU', '2')]
    elif mode.startswith('script-'):
        command = [sys.executable, 'scripts/' + mode.removeprefix('script-') + '.py', *arguments]
    elif mode == 'prove-runtime-install-recovery':
        command = ['bash', 'scripts/prove-runtime-install-recovery.sh', *arguments]
    elif SDK == 'react-native' and mode == 'check':
        # Dependencies belong to the selected host's disposable snapshot.
        subprocess.run(['pnpm', '--ignore-workspace', 'install', '--frozen-lockfile'], cwd=ROOT, check=True)
        command = ['node', 'scripts/check.mjs', *arguments]
    else:
        command = [sys.executable, 'scripts/' + mode + '.py', *arguments]
    return subprocess.run(command, cwd=ROOT, env=dict(os.environ, _NUXIE_POOL_NESTED='1')).returncode


if __name__ == '__main__':
    raise SystemExit(main())
