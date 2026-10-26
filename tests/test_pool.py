import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('sdk_pool', Path(__file__).resolve().parents[1] / 'scripts/pool.py')
pool = importlib.util.module_from_spec(spec); spec.loader.exec_module(pool)


class PoolRoutingTests(unittest.TestCase):
    def setUp(self):
        # Bazel invokes tests from runfiles; these fixtures model SDK callers.
        context = patch.dict(os.environ, {name:'' for name in ('GRADLE_USER_HOME','GRADLE_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')})
        context.start(); self.addCleanup(context.stop)
        previous = Path.cwd()
        os.chdir(pool.ROOT)
        self.addCleanup(os.chdir, previous)

    def test_cleanup_options_do_not_become_tasks(self):
        self.assertEqual(pool.gradle_tasks(['--tests','connectedHostRenderOptionsTest','--args','clean old','test']),['test'])
        self.assertEqual(pool.gradle_tasks(['-p', 'android-plugin', '--console', 'plain', '-P', 'example=value', '-D', 'flag=value', '--project-prop', 'other=value', 'clean']), ['clean'])
        with patch.dict(os.environ, {'_NUXIE_POOL_NESTED':'0','NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool','NUXIE_CI_RUNNER_POLICY_VERSION':'0'}), patch.object(pool.Path, 'home', side_effect=AssertionError('cleanup remains local')):
            self.assertIsNone(pool.dispatch('gradle', ['-p', 'android-plugin', 'clean']))

    def test_declined_gradle_call_preserves_existing_worker_budget(self):
        from types import SimpleNamespace
        with patch.object(pool.sys,'argv',['pool.py','gradle','--max-workers=1','clean']),patch.object(pool,'dispatch',return_value=None),patch.dict(os.environ,{'_NUXIE_POOL_NESTED':'0'}),patch.object(pool.subprocess,'run',return_value=SimpleNamespace(returncode=0)) as run:
            self.assertEqual(pool.main(),0)
            self.assertEqual(run.call_args.args[0],[str(pool.ROOT/'gradlew'),'--max-workers=1','clean'])

    def test_worker_limit_is_single_and_preserves_a_smaller_request(self):
        with patch.dict(os.environ, {'NUXIE_BAZEL_CPU':'4'}):
            for arguments in (['lint','--max-workers=1'], ['--max-workers','1','lint']):
                self.assertEqual(pool.gradle_worker_arguments(arguments),['lint','--no-daemon','--max-workers=1'])
            self.assertEqual(pool.gradle_worker_arguments(['lint','--max-workers=8']),['lint','--no-daemon','--max-workers=4'])

    def test_caller_cache_operations_remain_local(self):
        with patch.dict(os.environ, {'_NUXIE_POOL_NESTED':'0','NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool','NUXIE_CI_RUNNER_POLICY_VERSION':'0'}),patch.object(pool.Path,'home',side_effect=AssertionError('caller-local operation')):
            for task in ('cl','cle','c','clTDUT','cTDUT','pTML','pubTML','publishToMavenLocal'):
                self.assertIsNone(pool.dispatch('gradle',[task,':example-app:assembleDebug']))
            self.assertIsNone(pool.dispatch('gradle',['clean',':example-app:assembleDebug']))
            self.assertIsNone(pool.dispatch('gradle',[':nuxie-android:cleanTestDebugUnitTest']))
            for arguments in (['test','--init-script','/caller/init.gradle'],['-b','/caller/build.gradle','tasks'],['--build-file=/caller/build.gradle','tasks'],['-g/caller/cache','test'],['--gradle-user-home','/caller/cache','test'],['--project-cache-dir=/caller/cache','test'],['--include-build','/caller/other','test'],['-Dorg.gradle.java.home=/caller/java','test'],['-p','/caller/other','test'],['--system-prop','org.gradle.java.home=/caller/java','test'],['--system-prop=org.gradle.java.home=/caller/java','test'],['--status'],['--continuous','test']):
                self.assertIsNone(pool.dispatch('gradle',arguments),arguments)
            self.assertIsNone(pool.dispatch('gradle',['--offline','test']))
            self.assertIsNone(pool.dispatch('gradle',[':nuxie-android:publishToMavenLocal']))

    def test_excluded_device_tasks_do_not_require_a_connected_device(self):
        self.assertEqual(pool.selection('gradle',[':nuxie-android:testDebugUnitTest','--tests','ai.nuxie.sdk.hostrender.HostRenderOptionsTest']),('portable',['android','java17']))
        self.assertEqual(pool.selection('gradle',['check','-x',':nuxie-android:connectedDebugAndroidTest']),('portable',['android','java17']))
        self.assertEqual(pool.selection('gradle',['check','--exclude-task',':nuxie-android:hostRenderSmoke']),('portable',['android','java17']))

    def test_external_implicit_gradle_project_stays_local(self):
        with patch.object(pool.Path,'cwd',return_value=Path('/caller/other-project')):
            self.assertTrue(pool.caller_gradle_context(['test']))
            self.assertFalse(pool.caller_gradle_context(['-p',str(pool.ROOT),'test']))

    def test_explicit_environment_cache_stays_local(self):
        for name in ('GRADLE_USER_HOME','GRADLE_OPTS','JAVA_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):
            with patch.dict(os.environ, {name:'/caller/selected-context'}):
                self.assertTrue(pool.caller_gradle_context(['test']),name)

    def test_relative_render_paths_use_the_harness_project(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary).resolve();project=root/'nuxie-android';project.mkdir()
            fixture=project/'input.riv';fixture.write_bytes(b'input')
            with patch.object(pool,'ROOT',root):
                self.assertEqual(pool.host_render_argument_path('input.riv',exists=True),fixture)
                self.assertEqual(pool.host_render_argument_path('build/frames'),project/'build/frames')
                self.assertEqual(pool.host_render_argument_path(str(fixture),exists=True),fixture)

    def test_explicit_jni_bridge_returns_its_native_product(self):
        import json
        with tempfile.TemporaryDirectory() as temporary:
            home=Path(temporary).resolve();root=home/'sdk';scripts=root/'scripts/bazel';scripts.mkdir(parents=True)
            (scripts/'sdk.py').write_text("import contextlib\n@contextlib.contextmanager\ndef host_inputs():\n yield {}, {}\n")
            helper=home/'.local/share/nuxie-homelab/sdk-task.py';helper.parent.mkdir(parents=True);record=home/'record.json'
            helper.write_text("from pathlib import Path\nimport os,json\ndef route(root,args,**kwargs):\n Path(os.environ['RECORD']).write_text(json.dumps(list(kwargs['outputs'])))\n return 17\n")
            with patch.object(pool,'ROOT',root),patch.object(pool.Path,'home',return_value=home),patch.dict(os.environ,{'_NUXIE_POOL_NESTED':'0','NUXIE_CI_RUNNER_POLICY_VERSION':'0','NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool','RECORD':str(record)}):
                self.assertEqual(pool.dispatch('gradle',['-p',str(root),':nuxie-android:compileHostRenderBridge']),17)
                self.assertIn('nuxie-android/build/host-render/native',json.loads(record.read_text()))

    def test_portable_and_platform_requirements(self):
        platform, tools = pool.selection('sdk', ['test'])
        self.assertEqual(platform, 'portable')
        self.assertEqual(tools, ['android'] if pool.SDK == 'android' else [])
        if pool.SDK != 'android':
            command = 'build-native' if pool.SDK in ('react-native', 'flutter') else 'prepare-ios'
            self.assertEqual(pool.selection('sdk', [command, '--platform', 'ios']), ('macos', ['xcode']))
            command = 'build-native' if pool.SDK in ('react-native', 'flutter') else 'build-android'
            self.assertEqual(pool.selection('sdk', [command, '--platform', 'android']), ('portable', ['android']))

    def test_whole_check_keeps_engine_and_mobile_requirements(self):
        platform, tools = pool.selection('check', [])
        self.assertEqual(platform, 'portable' if pool.SDK == 'android' else 'macos')
        self.assertIn('android', tools)
        extra = {'react-native':'node','unity':'unity','godot':'godot','ue':'unreal'}.get(pool.SDK)
        if extra: self.assertIn(extra, tools)

    def test_ios_checks_do_not_require_android_tools(self):
        if pool.SDK == 'android': return
        _, tools = pool.selection('check-ios', [])
        self.assertIn('xcode', tools)
        self.assertNotIn('android', tools)
        if pool.SDK == 'react-native':
            self.assertEqual(pool.selection('prepare-native', []), ('portable', ['android', 'node']))

    def test_parsed_command_controls_platform_when_options_come_first(self):
        if pool.SDK == 'android': return
        command = 'build-native' if pool.SDK in ('react-native', 'flutter') else 'test-ios'
        platform, tools = pool.selection('sdk', ['--configuration', 'Debug', command, '--platform', 'ios'], command)
        self.assertEqual(platform, 'macos')
        self.assertIn('xcode', tools)

    def test_ci_authorization_stays_with_its_existing_host(self):
        with patch.dict(os.environ, {'NUXIE_CI_RUNNER_POLICY_VERSION':'2','_NUXIE_POOL_NESTED':'0','NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool'}), patch.object(pool.Path,'home',side_effect=AssertionError('CI must not inspect client configuration')):
            self.assertIsNone(pool.dispatch('sdk', ['test']))

    def test_nested_command_does_not_submit_another_request(self):
        with patch.dict(os.environ, {'_NUXIE_POOL_NESTED':'1'}):
            self.assertIsNone(pool.dispatch('sdk', ['test']))

    def test_transport_preserves_arguments_status_and_output_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp);helper=home/'.local/share/nuxie-homelab/sdk-task.py';helper.parent.mkdir(parents=True)
            helper.write_text("def route(root, args, **kwargs):\n assert args == ['test', '--configuration', 'Debug']\n assert kwargs['platform'] == 'portable'\n assert kwargs['frontend'] == 'scripts/bazel/sdk.py'\n assert all(p.is_absolute() for p in kwargs['outputs'].values())\n return 17\n")
            with patch.object(pool.Path,'home',return_value=home),patch.dict(os.environ,{'_NUXIE_POOL_NESTED':'0','NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool','NUXIE_CI_RUNNER_POLICY_VERSION':'0'}):
                self.assertEqual(pool.dispatch('sdk',['test','--configuration','Debug']),17)


if __name__ == '__main__': unittest.main()
