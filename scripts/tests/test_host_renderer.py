import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts/bazel" / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


host = module("host_runtime")
sdk = module("sdk")


class HostRendererTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="host render test ")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def test_host_runtime_requires_defined_scripting_exports_on_both_platforms(self):
        for suffix, prefix in ((".so", ""), (".dylib", "_")):
            library = self.directory / ("libnux_capi" + suffix)
            library.write_bytes(b"selected runtime")
            symbols = "\n".join("00000001 T " + prefix + name for name in host.SCRIPTING_SYMBOLS)
            with patch.object(host.subprocess, "check_output", return_value=symbols) as inspect:
                self.assertEqual(host.validate(library), library)
                self.assertEqual(inspect.call_args.args[0], ["nm", "-g", str(library)])
            with patch.object(host.subprocess, "check_output", return_value=symbols.replace(" T ", " U ", 1)):
                with self.assertRaisesRegex(ValueError, "lacks required scripting symbols"):
                    host.validate(library)

    def test_missing_or_relative_runtime_fails_before_starting_the_build(self):
        for path in ("", "libnux_capi.so", str(self.directory / "missing.so")):
            with patch.dict(os.environ, {"NUXIE_HOST_CAPI_LIB": path}), patch.object(sdk, "bazel") as build:
                with self.assertRaisesRegex(ValueError, "existing absolute host library"):
                    sdk.run_host_renderer(["--input", "release"])
                build.assert_not_called()

    def test_frontend_passes_harness_arguments_without_gradle_or_string_quoting(self):
        arguments = ["--input", "/release with spaces", "--output", "/frames with spaces",
                     "--frames", "2", "--step-ms", "16", "--size", "390x844"]
        with patch.object(sdk, "validate_host_runtime") as validate, patch.object(sdk, "bazel") as build, \
                patch.object(sdk, "gradle") as gradle, \
                patch.dict(os.environ, {"NUXIE_HOST_CAPI_LIB": "/selected runtime/libnux_capi.dylib"}), \
                patch.object(sys, "argv", ["sdk.py", "host-render", *arguments]), \
                patch.object(sdk, "toolchain"):
            sdk.main()
        validate.assert_called_once_with("/selected runtime/libnux_capi.dylib")
        build.assert_called_once_with("run", "//:host_render_harness", "--", *arguments)
        gradle.assert_not_called()

    def test_smoke_preserves_selected_vulkan_environment(self):
        environment = {"NUXIE_HOST_CAPI_LIB": "/runtime/libnux_capi.so", "VK_ICD_FILENAMES": "/vk/icd.json",
                       "NUXIE_MOLTENVK_LIBRARY": "/vk/libMoltenVK.dylib"}
        with patch.dict(os.environ, environment, clear=True), patch.object(sdk, "validate_host_runtime"), \
                patch.object(sdk, "bazel") as build:
            sdk.run_host_renderer([], smoke=True)
        build.assert_called_once_with("test", "//:host_render_smoke", "--test_env=NUXIE_MOLTENVK_LIBRARY",
                                      "--test_env=VK_ICD_FILENAMES")

    def launcher(self):
        launcher = self.directory / "renderer.sh"
        runfiles = Path(str(launcher) + ".runfiles")
        binary = runfiles / "_main/java"
        binary.parent.mkdir(parents=True)
        binary.write_text("#!" + sys.executable + "\nimport json, os, sys\n"
                          "print(json.dumps({'arguments':sys.argv[1:], 'capi':os.environ['NUXIE_HOST_CAPI_LIB'], "
                          "'vulkan':os.environ.get('VK_ICD_FILENAMES')}))\n")
        binary.chmod(0o755)
        template = (ROOT / "scripts/bazel/host_render_launcher.sh").read_text()
        for key, value in {"@binary@": "_main/java", "@jni@": "_main/adapter.so",
                           "@capi@": "runtime/libnux_capi.so", "@workspace@": "_main"}.items():
            template = template.replace(key, value)
        launcher.write_text(template)
        launcher.chmod(0o755)
        return launcher, runfiles

    def test_launcher_loads_declared_native_runfiles_and_preserves_user_arguments(self):
        launcher, runfiles = self.launcher()
        arguments = ["--input", "/release with spaces", "--output", "/frames with spaces"]
        for explicit in (False, True):
            environment = {"PATH": os.environ["PATH"], "VK_ICD_FILENAMES": "/vk/icd.json",
                           "NUXIE_HOST_CAPI_LIB": "/original/selected/library.so"}
            if explicit:
                environment["RUNFILES_DIR"] = str(runfiles)
            result = subprocess.run([str(launcher), *arguments], env=environment, cwd=self.directory,
                                    check=True, text=True, capture_output=True)
            record = json.loads(result.stdout)
            self.assertEqual(record["arguments"], ["--jvm_flag=-Dnuxie.host.jni.lib=" + str(runfiles / "_main/adapter.so"),
                             "--jvm_flag=-Dnuxie.repo.root=" + str(runfiles / "_main"), *arguments])
            self.assertEqual(record["capi"], str(runfiles / "runtime/libnux_capi.so"))
            self.assertEqual(record["vulkan"], "/vk/icd.json")

    def test_launcher_without_runfiles_fails_closed(self):
        launcher, _ = self.launcher()
        result = subprocess.run([str(launcher)], env={"PATH": os.environ["PATH"],
                                "RUNFILES_DIR": str(self.directory / "missing")}, text=True, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("requires its declared Bazel runfiles", result.stderr)


if __name__ == "__main__":
    unittest.main()
