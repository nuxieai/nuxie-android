#!/usr/bin/env python3
"""Submit this SDK's existing frontend to the shared, capability-selected pool."""
import importlib.util
import contextlib
import os
import re
import shlex
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SDK = 'android'
OUTPUTS = ['build/bazel-sdk', 'nuxie-android/build/outputs', 'nuxie-android/build/reports', 'nuxie-android/build/test-results', 'example-app/build/outputs', 'build/maven-repository', 'build/reports', 'build/test-results', 'build/outputs', 'nuxie-android/api/nuxie-android.api']


def option(arguments, name):
    selected = None
    for index, value in enumerate(arguments):
        if value == name and index + 1 < len(arguments): selected = arguments[index + 1]
        elif value.startswith(name + '='): selected = value.split('=', 1)[1]
    return selected


def selection(mode, arguments, command=None, selected_platform=None, selected_device=None):
    selected = 'android' if mode == 'prepare-native' and SDK in ('react-native', 'unity') else 'all'
    for i, arg in enumerate(arguments):
        if arg == '--platform' and i + 1 < len(arguments): selected = arguments[i + 1]
        elif arg.startswith('--platform='): selected = arg.split('=', 1)[1]
    if selected_platform is not None: selected = selected_platform
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
        serial = selected_device or serial or next((a.split('=', 1)[1] for a in arguments if a.startswith('--serial=')), None)
        if not serial: raise ValueError('Android device serial is required')
        requirements.add('android-device@' + serial)
    if (mode == 'gradle' or (mode == 'check' and 'android' in requirements)
            or (SDK == 'android' and mode == 'sdk' and command in ('check', 'lint', 'api-check', 'providers'))):
        requirements.add('java17')
    if mode == 'gradle' and any(device_task(a) for a in gradle_tasks(arguments)):
        serial = os.environ.get('ANDROID_SERIAL')
        if not serial: raise ValueError('ANDROID_SERIAL is required for connected pool tests')
        requirements.add('android-device@' + serial)
    if SDK == 'android' and (command in ('host-render', 'host-render-smoke') or (mode == 'gradle' and any(host_render_task(a) for a in gradle_tasks(arguments)))):
        requirements.add('native-host@' + sys.platform + '-' + os.uname().machine)
        return ('macos' if sys.platform == 'darwin' else 'linux'), sorted(requirements)
    if mode.startswith('script-'):
        requirements.add('java17')
        if mode in ('script-test-talkback', 'script-test-minified-font-fallback', 'script-test-minified-native-logging', 'script-test-startup-process-death'):
            serial = option(arguments, '--serial') or option(arguments, '--device')
            if mode == 'script-test-minified-native-logging' and arguments: serial = arguments[0]
            serial = selected_device or serial
            if not serial: raise ValueError('A device serial is required for this pool qualification')
            requirements.add('android-device@' + serial)
    if mode == 'prove-runtime-install-recovery': requirements.add('java17')
    if apple: requirements.add('xcode')
    return ('macos' if apple else 'portable'), sorted(requirements)


def task_abbreviates(task, name):
    words = re.findall(r'[A-Z]?[a-z0-9]+', name)
    pattern = ''.join('(?:' + '|'.join(re.escape(word[:n]) for n in range(len(word), 0, -1)) + ')' for word in words)
    return re.fullmatch(pattern, task, re.IGNORECASE) is not None


def host_render_task(task):
    return any(task_abbreviates(task, name) for name in ('hostRenderHarness', 'hostRenderSmoke', 'compileHostRenderBridge'))


def device_task(task):
    for word in ('connected', 'install', 'uninstall'):
        if task.lower().startswith(word): return True
        # An initial c alone is ambiguous with clean; it keeps caller semantics.
        for size in range(2 if word == 'connected' else 1, len(word)):
            if task.startswith(word[:size]) and len(task) > size and task[size].isupper(): return True
    return False


def host_render_argument_path(value, *, exists=False):
    selected = Path(value).expanduser()
    # JavaExec defaults to the project that owns hostRenderHarness.
    if not selected.is_absolute(): selected = ROOT / 'nuxie-android' / selected
    return selected.resolve(strict=exists)


def gradle_tasks(arguments):
    values = iter(arguments)
    tasks = []
    takes_value = {'--tests', '--args', '--configuration', '--dependency', '--dependency-verification', '-P', '--project-prop', '-D', '--system-prop', '-p', '--project-dir', '-g', '--gradle-user-home', '-I', '--init-script',
                   '-c', '--settings-file', '-b', '--build-file', '-x', '--exclude-task',
                   '--include-build', '--project-cache-dir', '--console', '--warning-mode',
                   '--max-workers', '--priority', '--configuration-cache-problems'}
    for value in values:
        if value in takes_value: next(values, None)
        elif not value.startswith('-'): tasks.append(value.rsplit(':', 1)[-1])
    return tasks


def gradle_worker_arguments(arguments):
    values = iter(arguments); result = []; workers = int(os.environ.get('NUXIE_BAZEL_CPU', '2'))
    for value in values:
        if value == '--max-workers': workers = min(workers, int(next(values)))
        elif value.startswith('--max-workers='): workers = min(workers, int(value.split('=', 1)[1]))
        elif value not in ('--daemon', '--no-daemon'): result.append(value)
    if workers < 1: raise ValueError('Gradle worker count must be positive')
    return [*result, '--no-daemon', '--max-workers=' + str(workers)]


def caller_gradle_operation(task):
    # Publication mutates a selected repository; cleanup mutates caller output.
    # Preserve Gradle's camel-case abbreviations for these caller operations.
    for verb in ('clean', 'publish'):
        if task.lower().startswith(verb): return True
        for length in range(1, len(verb)):
            prefix = verb[:length]
            if task.lower() == prefix or (task.startswith(prefix) and len(task) > length and task[length].isupper()): return True
    return False


def caller_gradle_context(arguments):
    if any(os.environ.get(name) for name in ('GRADLE_USER_HOME', 'GRADLE_OPTS', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')): return True
    # These options select caller-owned configuration, caches or persistent
    # daemon/watch operations, rather than the SDK's declared build inputs.
    local = {'--system-prop', '--init-script', '--settings-file', '--build-file', '--gradle-user-home',
             '--project-cache-dir', '--include-build', '--offline', '--stop', '--status',
             '--foreground', '--daemon', '--continuous', '-t', '--write-locks',
             '--update-locks', '--write-verification-metadata', '--export-keys', '--profile'}
    values = iter(arguments); project_directory = Path.cwd().resolve()
    for value in values:
        if value.split('=', 1)[0] in local or value.startswith(('-I', '-c', '-b', '-g', '-D')): return True
        project = next(values, None) if value in ('-p', '--project-dir') else value.partition('=')[2] if value.startswith('--project-dir=') else value[2:].removeprefix('=') if value.startswith('-p') and len(value) > 2 else None
        if project: project_directory = Path(project).expanduser().resolve()
    return not project_directory.is_relative_to(ROOT.resolve())


def dispatch(mode, arguments, *, output=None, command=None, input_files=None, environment=None, selected_platform=None, selected_device=None):
    with contextlib.ExitStack() as stack:
        return _dispatch(mode, arguments, output=output, command=command, input_files=input_files, environment=environment, selected_platform=selected_platform, selected_device=selected_device, _stack=stack)


def _dispatch(mode, arguments, *, output=None, command=None, input_files=None, environment=None, selected_platform=None, selected_device=None, _stack=None):
    if (os.environ.get('_NUXIE_POOL_NESTED') == '1' or os.environ.get('NUXIE_HOMELAB') == '0'
            or os.environ.get('NUXIE_EXECUTION') == 'local' or os.environ.get('NUXIE_CI_RUNNER_POLICY_VERSION') == '2' or '--help' in arguments or '-h' in arguments): return None
    if mode == 'gradle':
        tasks = gradle_tasks(arguments)
        # Gradle permits clean/cl and camel-case clean-task abbreviations.
        if any(caller_gradle_operation(task) and not host_render_task(task) for task in tasks): return None
        if caller_gradle_context(arguments): return None
        # These explicitly address the caller's dependency cache or Maven home.
        if '--offline' in arguments or any(task.endswith('ToMavenLocal') for task in tasks): return None
        if (tasks and all(value in ('clean', 'fetch', 'runtimeFetch') for value in tasks)) or '--stop' in arguments: return None
    helper = Path.home() / '.local/share/nuxie-homelab/sdk-task.py'
    configured = (Path.home() / '.config/nuxie/homelab-cluster.json').is_file()
    if not helper.is_file():
        if configured and os.environ.get('NUXIE_HOMELAB') != '0' and os.environ.get('NUXIE_EXECUTION') != 'local':
            raise RuntimeError('Configured pool needs its SDK task client installed')
        return None
    spec = importlib.util.spec_from_file_location('pool_sdk_task', helper)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    platform, requirements = selection(mode, arguments, command, selected_platform, selected_device)
    names = {name: ROOT / name for name in OUTPUTS}
    focused_outputs = (mode in ('script-test-minified-font-fallback', 'script-test-maven-consumer') or (mode == 'sdk' and command in ('host-render', 'host-render-smoke')))
    if focused_outputs: names = {}
    if output is not None:
        names['build/pool-prepared'] = Path(output).resolve()
        names.pop('build/bazel-sdk', None)
    frontend = 'scripts/bazel/sdk.py' if mode == 'sdk' else 'scripts/pool.py'
    args = list(arguments) if mode == 'sdk' else [mode, *arguments]
    if output is not None:
        for index, argument in enumerate(args):
            if argument == '--output' and index + 1 < len(args): args[index + 1] = str(Path(output).resolve())
            elif argument.startswith('--output='): args[index] = '--output=' + str(Path(output).resolve())
    if output is not None and not any(a == '--output' or a.startswith('--output=') for a in args):
        args += ['--output', str(output)]
    input_files = dict(input_files or {}); environment = dict(environment or {})
    for project in ([] if focused_outputs else ROOT.iterdir()):
        if project.is_dir() and (project / 'build.gradle.kts').is_file():
            for directory in ('outputs', 'reports', 'test-results', 'libs'):
                names[project.name + '/build/' + directory] = project / 'build' / directory
    if mode == 'gradle':
        if os.environ.get('NUXIE_SIGNING_KEY') and any('publish' in a.lower() for a in args):
            raise ValueError('Signed Gradle publication is unsupported through the pool; use sdk.sh prepare --sign for caller-side signing')
        if os.environ.get('ANDROID_SERIAL'): environment['ANDROID_SERIAL'] = os.environ['ANDROID_SERIAL']
        if not any(a in ('-p', '--project-dir') or a.startswith('--project-dir=') or (a.startswith('-p') and len(a) > 2) for a in args):
            args += ['--project-dir', str(Path.cwd().resolve())]

        for index, argument in enumerate(args):
            project = args[index + 1] if argument in ('-p', '--project-dir') and index + 1 < len(args) else argument.split('=', 1)[1] if argument.startswith('--project-dir=') else argument[2:].removeprefix('=') if argument.startswith('-p') and len(argument) > 2 else None
            if not project: continue
            selected = Path(project).expanduser().resolve(strict=True)
            # Generated consumers use source paths from their owning script.
            # Relocate the project itself and its local Maven repository.
            if not selected.is_relative_to(ROOT): raise ValueError('Generated pool Gradle projects must be inside the SDK checkout')
            relative = selected.relative_to(ROOT).as_posix()
            if argument in ('-p', '--project-dir'): args[index + 1] = str(selected)
            else: args[index] = '--project-dir=' + str(selected)
            if relative == '.': continue
            for file in selected.rglob('*'):
                if file.is_file() and not any(p in ('build', '.gradle') for p in file.relative_to(selected).parts):
                    input_files[(Path(relative) / file.relative_to(selected)).as_posix()] = file
            for directory in ('outputs', 'reports', 'test-results', 'libs'):
                names[relative + '/build/' + directory] = selected / 'build' / directory
            if argument in ('-p', '--project-dir'): args[index + 1] = str(selected)
            else: args[index] = '--project-dir=' + str(selected)
        if any(task.lower().startswith('verify') or (task.startswith('v') and len(task) > 1 and task[1].isupper()) for task in gradle_tasks(arguments)):
            for name, source in names.items():
                if name.endswith(('/outputs', '/libs')) and source.is_dir(): input_files[name] = source
        repository = ROOT / 'build/maven-repository'
        if repository.is_dir(): input_files['build/maven-repository'] = repository
        if any(a.rsplit(':', 1)[-1] in ('fetch', 'runtimeFetch') for a in args):
            names['runtime/prebuilt'] = ROOT / 'runtime/prebuilt'
        if any(a.rsplit(':', 1)[-1] == 'apiDump' for a in args):
            names['nuxie-android/api/nuxie-android.api'] = ROOT / 'nuxie-android/api/nuxie-android.api'
        if any(host_render_task(a) for a in gradle_tasks(arguments)):
            names['nuxie-android/build/host-render/native'] = ROOT / 'nuxie-android/build/host-render/native'
            spec = importlib.util.spec_from_file_location('sdk_host_pool', ROOT / 'scripts/bazel/sdk.py')
            host = importlib.util.module_from_spec(spec); spec.loader.exec_module(host)
            files, variables = _stack.enter_context(host.host_inputs())
            input_files.update(files); environment.update(variables)
            for index, argument in enumerate(args):
                if not (argument == '--args' or argument.startswith('--args=')): continue
                position = index + 1 if argument == '--args' else index
                values = shlex.split(args[position] if argument == '--args' else argument.split('=', 1)[1])
                for item, value in enumerate(values[:-1]):
                    if value == '--input':
                        selected = host_render_argument_path(values[item + 1], exists=True)
                        input_files['.nuxie/sdk-inputs/render-input'] = selected; values[item + 1] = str(selected)
                    elif value == '--output':
                        selected = host_render_argument_path(values[item + 1])
                        names['build/pool-host-frames'] = selected; values[item + 1] = str(selected)
                args[position] = ('' if argument == '--args' else '--args=') + shlex.join(values)

    if mode.startswith('script-'):
        # ADB is a worker-local SDK tool, never a caller executable path.
        for index in range(len(args) - 1, -1, -1):
            if args[index].startswith('--adb='): del args[index]
            elif args[index] == '--adb': del args[index:index + 2]
        for name in ('build/maven-repository', 'nuxie-android/build/outputs/aar/nuxie-android-release.aar'):
            if (ROOT / name).exists(): input_files[name] = ROOT / name
        for directory in ('maven-consumer', 'test-store-lint-consumer', 'minified-native-logging-consumer'):
            for suffix in ('build/outputs', 'build/reports', 'build/test-results', 'qualification.log'):
                name = 'build/' + directory + '/' + suffix; names[name] = ROOT / name
        diagnostic_files = {
            'maven-consumer': ('unsafe-test-store.log', 'missing-selected-repository.log'),
            'test-store-lint-consumer': ('unsafe-literal.log', 'safe-debug-gate.log', 'unsafe-kotlin-property.log', 'safe-kotlin-debug-gate.log'),
            'minified-native-logging-consumer': ('build.log', 'disabled.log', 'redacted.log', 'raw.log'),
        }
        for directory, files in diagnostic_files.items():
            for filename in files:
                name = 'build/' + directory + '/' + filename; names[name] = ROOT / name
        for directory in ('maven-signing-test', 'process-death'):
            names['build/' + directory] = ROOT / 'build' / directory
        repository = option(args, '--repository')
        if repository and repository.startswith('file:'):
            from urllib.parse import urlsplit, unquote
            selected = Path(unquote(urlsplit(repository).path)).resolve(strict=True)
            input_files['.nuxie/sdk-inputs/maven-repository'] = selected
    names = {name: destination for name, destination in names.items() if not any(name.startswith(parent + '/') for parent in names if parent != name)}
    return module.route(ROOT, args, frontend=frontend, platform=platform,
                        requirements=requirements, outputs=names, input_files=input_files, environment=environment,
                        merge_outputs=[name for name in names if not (SDK == 'react-native' and name == 'dist')])


def main():
    mode, *arguments = sys.argv[1:]
    status = dispatch(mode, arguments)
    if status is not None: return status
    if mode == 'gradle':
        command = [str(ROOT / 'gradlew'), *arguments]
        if os.environ.get('_NUXIE_POOL_NESTED') == '1':
            command = [str(ROOT / 'gradlew'), *gradle_worker_arguments(arguments)]
    elif mode.startswith('script-'):
        command = [sys.executable, 'scripts/' + mode.removeprefix('script-') + '.py', *arguments]
    elif mode == 'prove-runtime-install-recovery':
        subprocess.run([str(ROOT / 'gradlew'), '--no-daemon', '--max-workers=1', 'help'], cwd=ROOT, env=dict(os.environ, _NUXIE_POOL_NESTED='1'), check=True)
        command = ['bash', 'scripts/prove-runtime-install-recovery.sh', *arguments]
    elif SDK == 'react-native' and mode == 'check':
        # Dependencies belong to the selected host's disposable snapshot.
        subprocess.run(['pnpm', '--ignore-workspace', 'install', '--frozen-lockfile'], cwd=ROOT, check=True)
        command = ['node', 'scripts/check.mjs', *arguments]
    else:
        command = [sys.executable, 'scripts/' + mode + '.py', *arguments]
    return subprocess.run(command, cwd=Path.cwd() if mode == 'gradle' else ROOT, env=dict(os.environ, _NUXIE_POOL_NESTED='1')).returncode


if __name__ == '__main__':
    raise SystemExit(main())
