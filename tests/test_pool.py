import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('sdk_pool', Path(__file__).resolve().parents[1] / 'scripts/pool.py')
pool = importlib.util.module_from_spec(spec); spec.loader.exec_module(pool)


class PoolRoutingTests(unittest.TestCase):
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

    def test_nested_command_does_not_submit_another_request(self):
        with patch.dict(os.environ, {'_NUXIE_POOL_NESTED':'1'}):
            self.assertIsNone(pool.dispatch('sdk', ['test']))

    def test_transport_preserves_arguments_status_and_output_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            home=Path(temp);helper=home/'.local/share/nuxie-homelab/sdk-task.py';helper.parent.mkdir(parents=True)
            helper.write_text("def route(root, args, **kwargs):\n assert args == ['test', '--configuration', 'Debug']\n assert kwargs['platform'] == 'portable'\n assert kwargs['frontend'] == 'scripts/bazel/sdk.py'\n assert all(p.is_absolute() for p in kwargs['outputs'].values())\n return 17\n")
            with patch.object(pool.Path,'home',return_value=home),patch.dict(os.environ,{'_NUXIE_POOL_NESTED':'0','NUXIE_HOMELAB':'1','NUXIE_EXECUTION':'pool'}):
                self.assertEqual(pool.dispatch('sdk',['test','--configuration','Debug']),17)


if __name__ == '__main__': unittest.main()
