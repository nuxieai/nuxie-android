"""Observe real subprocess tool identities without provisioning any toolchain."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('observed_tools', ROOT / 'scripts/ci/toolchain.py')
tools = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tools)


class ToolchainTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.java = self.root / 'java'
        (self.java / 'bin').mkdir(parents=True)
        self.sdk = self.root / 'sdk'
        self.sdk.mkdir()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.major = '17' if ROOT.name == 'nuxie-react-native' else '21'
        self.program(self.java / 'bin/java', 'openjdk version "' + self.major + '.0.1"')
        self.program(self.bin / 'node', 'v24.14.1')
        self.program(self.bin / 'flutter', json.dumps({'frameworkVersion': '3.41.4'}))
        self.program(self.bin / 'xcodebuild', 'synthetic Xcode observation')
        self.program(self.bin / 'ruby', 'synthetic Ruby observation')
        for relative in tools.ANDROID_FILES:
            path = self.sdk / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('public fixture')
        for revision in tools.NDK_VERSIONS:
            (self.sdk / ('ndk/' + revision + '/source.properties')).write_text('Pkg.Revision = ' + revision + '\n')
        self.environment = {'JAVA_HOME': str(self.java), 'ANDROID_HOME': str(self.sdk)}
        self.process_environment = patch.dict(os.environ, {'PATH': str(self.bin) + os.pathsep + os.environ['PATH']})
        self.process_environment.start()
        self.addCleanup(self.process_environment.stop)

    def program(self, path, result):
        path.write_text('#!' + sys.executable + '\nprint(' + repr(result) + ')\n')
        path.chmod(0o755)

    def test_provisioned_android_facts_are_observed_without_installing(self):
        before = sorted(str(path.relative_to(self.root)) for path in self.root.rglob('*'))
        facts = tools.validate('android', self.environment)
        self.assertEqual(facts['javaHome'], str(self.java))
        self.assertEqual(facts['androidHome'], str(self.sdk))
        self.assertIn(self.major + '.0.1', facts['javaVersion'])
        self.assertEqual(before, sorted(str(path.relative_to(self.root)) for path in self.root.rglob('*')))

    def test_wrong_java_missing_platform_and_changed_ndk_fail_closed(self):
        self.program(self.java / 'bin/java', 'openjdk version "99.0.1"')
        with self.assertRaisesRegex(ValueError, 'Expected provisioned Java'):
            tools.validate('android', self.environment)
        self.program(self.java / 'bin/java', 'openjdk version "' + self.major + '.0.1"')
        target = self.sdk / tools.ANDROID_FILES[0]
        target.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing provisioned Android input'):
            tools.validate('android', self.environment)
        target.write_text('public fixture')
        (self.sdk / ('ndk/' + tools.NDK_VERSIONS[0] + '/source.properties')).write_text('Pkg.Revision = unexpected\n')
        with self.assertRaisesRegex(ValueError, 'NDK identity differs'):
            tools.validate('android', self.environment)

    def test_portable_does_not_require_android_or_apple_tools(self):
        (self.bin / 'xcodebuild').unlink()
        (self.bin / 'ruby').unlink()
        facts = tools.validate('portable', {})
        self.assertNotIn('javaHome', facts)
        self.assertNotIn('xcodeVersion', facts)
        if ROOT.name == 'nuxie-react-native':
            self.program(self.bin / 'node', 'v99.0.0')
            with self.assertRaisesRegex(ValueError, 'Node 24.14.1'):
                tools.validate('portable', {})
        if ROOT.name == 'nuxie-flutter':
            self.program(self.bin / 'flutter', json.dumps({'frameworkVersion': '99.0.0'}))
            with self.assertRaisesRegex(ValueError, 'Flutter 3.41.4'):
                tools.validate('portable', {})


if __name__ == '__main__':
    unittest.main()
