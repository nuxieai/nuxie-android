import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class JvmLauncherTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="jvm launcher ")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.runfiles = self.directory / "declared runfiles"
        self.workspace = self.runfiles / "_main"
        self.workspace.mkdir(parents=True)
        self.scratch = self.directory / "private scratch"
        self.scratch.mkdir()
        self.probe = self.workspace / "probe.py"
        self.probe.write_text(f"#!{sys.executable}\n" + '''import json, os, sys
from pathlib import Path
def read(path):
    return Path(path).read_text() if Path(path).is_file() else None
sdk = Path(os.environ["ANDROID_SDK_ROOT"])
print(json.dumps({
    "cwd": os.getcwd(), "arguments": sys.argv[1:],
    "sdk": str(sdk), "home": os.environ["ANDROID_HOME"],
    "cache": os.environ["ANDROID_SDK_CACHE_DIR"],
    "jar": read(sdk / "platforms/android-36/android.jar"),
    "metadata": read(sdk / "platforms/android-36/source.properties"),
    "local_fixture": read("src/test/case.txt"),
    "shared_fixture": read("../fixtures/case.txt"),
    "package_fixture": read("../_main/nuxie-android/src/test/case.txt"),
    "cwd_entries": sorted(path.name for path in Path.cwd().iterdir()),
}))
''')
        self.probe.chmod(0o755)

    def launch(self, android_jar=""):
        launcher = self.directory / "launcher.sh"
        text = (ROOT / "scripts/bazel/jvm_test_launcher.sh").read_text()
        launcher.write_text(text.replace("@binary@", "_main/probe.py")
                           .replace("@android_jar@", android_jar))
        variables = {**os.environ, "TEST_SRCDIR": str(self.runfiles),
                     "TEST_WORKSPACE": "_main", "TEST_TMPDIR": str(self.scratch),
                     "ANDROID_SDK_ROOT": str(self.directory / "caller sdk"),
                     "ANDROID_HOME": "caller home", "ANDROID_SDK_CACHE_DIR": "caller cache"}
        result = subprocess.run(["bash", str(launcher), "argument with spaces", "--flag=value"],
                                env=variables, capture_output=True, text=True, check=True)
        value = json.loads(result.stdout)
        self.assertEqual(value["cwd"], str(self.scratch / "sdk-cwd/nuxie-android"))
        self.assertEqual(value["arguments"], ["argument with spaces", "--flag=value"])
        return value

    def test_lint_launch_discovers_declared_api_in_private_sdk_without_module_sources(self):
        jar = self.runfiles / "androidsdk/platforms/android-36/android.jar"
        jar.parent.mkdir(parents=True)
        jar.write_text("declared Android API classes")
        value = self.launch("androidsdk/platforms/android-36/android.jar")
        self.assertEqual(value["sdk"], str(self.scratch / "lint-sdk"))
        self.assertEqual(value["home"], value["sdk"])
        self.assertEqual(value["cache"], str(self.scratch / "lint-cache"))
        self.assertTrue(Path(value["cache"]).is_dir())
        self.assertEqual(value["jar"], "declared Android API classes")
        self.assertEqual((Path(value["sdk"]) / "platforms/android-36/android.jar").resolve(), jar)
        self.assertEqual(dict(line.split("=", 1) for line in value["metadata"].splitlines()),
                         {"Platform.Version": "16", "AndroidVersion.ApiLevel": "36", "Pkg.Revision": "2"})
        self.assertNotIn("*", value["cwd_entries"])

    def test_sdk_launch_preserves_gradle_and_robolectric_fixture_paths(self):
        module_fixture = self.workspace / "nuxie-android/src/test/case.txt"
        module_fixture.parent.mkdir(parents=True)
        module_fixture.write_text("module fixture")
        shared_fixture = self.workspace / "fixtures/case.txt"
        shared_fixture.parent.mkdir()
        shared_fixture.write_text("shared fixture")
        value = self.launch()
        self.assertEqual(value["local_fixture"], "module fixture")
        self.assertEqual(value["package_fixture"], "module fixture")
        self.assertEqual(value["shared_fixture"], "shared fixture")
        self.assertEqual(value["sdk"], str(self.directory / "caller sdk"))
        self.assertEqual(value["home"], "caller home")
        self.assertEqual(value["cache"], "caller cache")
        self.assertFalse((self.scratch / "lint-sdk").exists())


if __name__ == "__main__":
    unittest.main()
