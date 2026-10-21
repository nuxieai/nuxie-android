"""Exercise the standalone shell launcher's fallback without an installed Bazel."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LauncherFallbackTests(unittest.TestCase):
    def test_checked_in_python_launcher_can_be_executed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts/bazel'
            scripts.mkdir(parents=True)
            for name in ('bazel.sh', 'launcher.py'):
                shutil.copy2(ROOT / 'scripts/bazel' / name, scripts / name)
            tools = root / 'bin'
            tools.mkdir()
            (tools / 'dirname').symlink_to(shutil.which('dirname'))
            interpreter = tools / 'python3'
            interpreter.write_text('#!' + sys.executable + '\nimport sys\n'
                                   'if sys.argv[1].endswith("launcher.py"): print("pinned launcher selected")\n'
                                   'else: sys.stdin.read()\n')
            interpreter.chmod(0o755)
            result = subprocess.run(['/bin/bash', scripts / 'bazel.sh', 'version'],
                                    env={'PATH': str(tools), 'HOME': str(root)},
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), 'pinned launcher selected')


if __name__ == '__main__':
    unittest.main()
