import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts/bazel" / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


packager = module("package_aar")
sdk = module("sdk")
runtime = module("runtime_artifacts")
suite = module("test_suite")
semantic = module("semantic_fixture")


class BazelSdkPackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def test_cache_override_reaches_bazel_without_overriding_worktree_output_base(self):
        root = self.directory
        with patch.object(sdk, "ROOT", root), patch.object(sdk, "require_space"), \
                patch.dict("os.environ", {"NUXIE_BAZEL_BIN": "selected-bazel",
                                          "NUXIE_BAZEL_CACHE_DIR": str(root / "shared cache")}, clear=True), \
                patch.object(sdk.subprocess, "run") as run:
            sdk.bazel("build", "//:nuxie_android_aar")
        self.assertEqual(run.call_args.args[0], ["selected-bazel", "--nosystem_rc", "--nohome_rc",
                         "--bazelrc=" + str(root / ".bazel-cache.local.bazelrc"),
                         "build", "--jobs=2", "//:nuxie_android_aar"])
        self.assertEqual(run.call_args.kwargs["cwd"], root)
        self.assertIn(str(root / "shared cache/actions"),
                      (root / ".bazel-cache.local.bazelrc").read_text())

    def test_ci_batch_and_user_root_preserve_distinct_workspace_outputs(self):
        sdk_root = self.directory / "sdk"
        fixture_root = sdk_root / "build/semantic-fixture"
        fixture_root.mkdir(parents=True)
        variables = {"NUXIE_BAZEL_BIN": "selected-bazel", "CI": "1",
                     "NUXIE_BAZEL_OUTPUT_USER_ROOT": str(self.directory / "outputs"),
                     "NUXIE_ANDROID_BAZEL_OUTPUT_BASE": str(self.directory / "sdk output"),
                     "NUXIE_BAZEL_CACHE_DIR": str(self.directory / "shared cache")}
        with patch.object(sdk, "ROOT", sdk_root), patch.dict("os.environ", variables, clear=True):
            ordinary = sdk.bazel_command()
            fixture = sdk.bazel_command(fixture_root, "NUXIE_ANDROID_FIXTURE_BAZEL_OUTPUT_BASE")
            self.assertIn("--batch", ordinary)
            self.assertIn("--batch", fixture)
            self.assertIn("--output_base=" + variables["NUXIE_ANDROID_BAZEL_OUTPUT_BASE"], ordinary)
            self.assertFalse(any(value.startswith("--output_base=") for value in fixture))
            for command in (ordinary, fixture):
                self.assertIn("--output_user_root=" + variables["NUXIE_BAZEL_OUTPUT_USER_ROOT"], command)
            with patch.dict("os.environ", {"NUXIE_BAZEL_BATCH": "0"}):
                self.assertNotIn("--batch", sdk.bazel_command())
        self.assertEqual((sdk_root / ".bazel-cache.local.bazelrc").read_bytes(),
                         (fixture_root / ".bazel-cache.local.bazelrc").read_bytes())

    def test_standalone_build_uses_the_checksummed_launcher_without_parent_dependencies(self):
        with patch.object(sdk, "ROOT", self.directory), patch.dict("os.environ", {}, clear=True), \
                patch.object(sdk.shutil, "which", return_value=None):
            self.assertEqual(sdk.bazel_command()[:2], [sdk.sys.executable,
                             str(self.directory / "scripts/bazel/launcher.py")])

    def ci_caches(self, variables):
        environment = {"PATH": os.environ["PATH"], "HOME": str(Path.home()), **variables}
        result = subprocess.run(["bash", "--noprofile", "--norc", "-c",
                                'set -euo pipefail; source "$1"; printf "%s\\n" "$BAZELISK_HOME" '
                                '"$NUXIE_BAZEL_OUTPUT_USER_ROOT" "$NUXIE_BAZEL_CACHE_DIR" "$NUXIE_BAZEL_BATCH"',
                                "ci-cache-test", str(ROOT / "scripts/bazel/ci-cache.sh")],
                               env=environment, check=True, text=True, capture_output=True)
        return result.stdout.splitlines()

    def test_standalone_buildkite_reuses_guarded_agent_caches(self):
        expected_root = Path.home() / ".nuxie-ci/editor-cargo-target/runner-mac-one"
        self.assertEqual(self.ci_caches({"BUILDKITE_AGENT_NAME": "runner mac/one", "RUNNER_NAME": "unused"}),
                         [str(expected_root / "bazelisk"), str(expected_root / "bazel"),
                          str(expected_root / "bazel-shared-cache"), "1"])
        for name in ("", ".", ".."):
            self.assertIn("/editor-cargo-target/default/bazel", self.ci_caches({"RUNNER_NAME": name})[1])

    def test_standalone_buildkite_preserves_explicit_cache_and_output_overrides(self):
        expected = [str(self.directory / name) for name in ("launcher cache", "output state", "shared cache")]
        variables = dict(zip(("BAZELISK_HOME", "NUXIE_BAZEL_OUTPUT_USER_ROOT", "NUXIE_BAZEL_CACHE_DIR"), expected))
        variables["NUXIE_BAZEL_BATCH"] = "0"
        self.assertEqual(self.ci_caches(variables), expected + ["0"])
        self.assertEqual(self.ci_caches({"NUXIE_BAZEL_CACHE_DIR": ""})[2], "")

    def fixture(self):
        base = self.directory / "base.aar"
        with zipfile.ZipFile(base, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"<manifest package='ai.nuxie.sdk'/>")
            archive.writestr("classes.jar", b"compiled SDK jar")
            archive.writestr("R.txt", b"int string nuxie_accessibility_on 0x1234")
            archive.writestr("res/values-fr/nuxie_accessibility.xml", b"<resources><string name='nuxie_accessibility_on'>Activ\xc3\xa9</string></resources>")
        lint = self.directory / "lint.jar"
        lint.write_bytes(b"compiled lint jar")
        consumer = ROOT / "nuxie-android/consumer-rules.pro"
        libraries = []
        shims = []
        for abi in packager.ABIS:
            directory = self.directory / abi
            directory.mkdir()
            for name in packager.RUNTIME_LIBRARIES:
                path = directory / name
                path.write_bytes((abi + ":" + name).encode())
                libraries.append(path)
            path = directory / "libnuxie_runtime_android.so"
            path.write_bytes((abi + ":direct JNI adapter").encode())
            shims.append(abi + "=" + str(path))
        return base, lint, consumer, shims, libraries

    def test_aar_preserves_owned_outputs_and_exact_two_abi_inventory(self):
        inputs = self.fixture()
        output = self.directory / "sdk.aar"
        packager.aar(*inputs, output)
        with zipfile.ZipFile(output) as archive:
            names = {name for name in archive.namelist() if name.endswith(".so")}
            self.assertEqual(names, {f"jni/{abi}/{name}" for abi in ("arm64-v8a", "x86_64")
                                     for name in ("libnux_capi.so", "libc++_shared.so", "libnuxie_runtime_android.so")})
            self.assertEqual(archive.read("classes.jar"), b"compiled SDK jar")
            self.assertEqual(archive.read("proguard.txt"), (ROOT / "nuxie-android/consumer-rules.pro").read_bytes())
            self.assertEqual(archive.read("lint.jar"), b"compiled lint jar")
            self.assertIn(b"minCompileSdk=1\n", archive.read("META-INF/com/android/build/gradle/aar-metadata.properties"))
        repeated = self.directory / "again.aar"
        packager.aar(*inputs, repeated)
        self.assertEqual(output.read_bytes(), repeated.read_bytes())

    def test_packaging_rejects_missing_runtime_or_adapter(self):
        base, lint, consumer, shims, libraries = self.fixture()
        with self.assertRaisesRegex(ValueError, "Incomplete pinned"):
            packager.aar(base, lint, consumer, shims, libraries[:-1], self.directory / "bad.aar")
        with self.assertRaisesRegex(ValueError, "Both directly"):
            packager.aar(base, lint, consumer, shims[:-1], libraries, self.directory / "bad.aar")

    def test_source_jar_uses_the_existing_consumer_package_paths(self):
        paths = sorted((ROOT / "nuxie-android/src/main").rglob("*.kt")) + sorted((ROOT / "nuxie-android/src/main").rglob("*.java"))
        output = self.directory / "sources.jar"
        packager.sources(paths, output)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(archive.read("ai/nuxie/sdk/Nuxie.kt"), (ROOT / "nuxie-android/src/main/kotlin/ai/nuxie/sdk/Nuxie.kt").read_bytes())
            self.assertEqual(len(archive.namelist()), len(paths))
            self.assertFalse(any("hostrender" in name for name in archive.namelist()))

    def test_maven_compile_and_runtime_scopes_match_the_owning_gradle_pom(self):
        # API/runtime scopes were captured by owning Gradle at8c7de363.
        # Main's canonical Gradle declarations omit V3's AndroidX Browser.
        expected = {
            ("com.android.billingclient", "billing", "9.1.0", "compile"),
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.9.0", "compile"),
            ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21", "compile"),
            ("androidx.sqlite", "sqlite-framework", "2.6.2", "runtime"),
            ("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.7.2", "runtime"),
        }
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        pom = ET.fromstring(sdk.pom("0.2.0-" + "a" * 40))
        actual = {tuple(dep.findtext("m:" + key, namespaces=ns) for key in ("groupId", "artifactId", "version", "scope"))
                  for dep in pom.findall("m:dependencies/m:dependency", ns)}
        self.assertEqual(actual, expected)
        self.assertEqual(pom.findtext("m:packaging", namespaces=ns), "aar")

    def test_gradle_module_metadata_has_api_runtime_and_documentation_variants(self):
        for suffix in (".aar", "-sources.jar", "-javadoc.jar"):
            (self.directory / ("nuxie-android-0.1.0" + suffix)).write_bytes(suffix.encode())
        value = sdk.module_metadata("0.1.0", self.directory)
        variants = {item["attributes"].get("org.gradle.usage") + ":" + item["attributes"].get("org.gradle.docstype", "library"): item for item in value["variants"]}
        api = {dep["module"] for dep in variants["java-api:library"]["dependencies"]}
        runtime_modules = {dep["module"] for dep in variants["java-runtime:library"]["dependencies"]}
        self.assertEqual(api, {"billing", "kotlinx-coroutines-android", "kotlin-stdlib"})
        self.assertEqual(runtime_modules - api, {"sqlite-framework", "kotlinx-serialization-json"})
        self.assertIn("java-runtime:sources", variants)
        self.assertIn("java-runtime:javadoc", variants)

    def test_atomic_version_replacement_drops_stale_signatures_and_keeps_other_versions(self):
        destination = self.directory / "0.1.0"
        destination.mkdir()
        (destination / "sdk.aar").write_bytes(b"old")
        (destination / "sdk.aar.asc").write_bytes(b"old signature")
        other = self.directory / "0.2.0"
        other.mkdir()
        (other / "sdk.aar").write_bytes(b"other")
        staging = self.directory / ".staging"
        staging.mkdir()
        (staging / "sdk.aar").write_bytes(b"new")
        sdk.publish_directory(staging, destination)
        self.assertEqual((destination / "sdk.aar").read_bytes(), b"new")
        self.assertFalse((destination / "sdk.aar.asc").exists())
        self.assertEqual((other / "sdk.aar").read_bytes(), b"other")

    def test_prepared_receipt_inventories_the_source_license_outside_maven_products(self):
        inputs = {}
        for suffix, label in sdk.TARGETS.items():
            path = self.directory / ("input" + suffix)
            path.write_bytes(suffix.encode())
            inputs[label] = path
        provenance = self.directory / "provenance.json"
        provenance.write_text(json.dumps({"mode": "published", "sourceRevision": "b" * 40}))
        inputs["@nuxie_runtime_android//:provenance.json"] = provenance
        output = self.directory / "prepared"
        args = SimpleNamespace(output=output, maven_version="0.2.0-" + "a" * 40, sign=False)
        with patch.object(sdk, "source_identity", return_value=("a" * 40, False)), \
                patch.object(sdk, "toolchain", return_value=(self.directory, self.directory)), \
                patch.object(sdk, "bazel"), patch.object(sdk, "artifact", side_effect=inputs.__getitem__), \
                patch.object(sdk, "verify_api"), patch.object(sdk, "verify_native"):
            sdk.prepare(args)
            license_path = output / "licenses/LICENSE"
            previous = license_path.stat().st_mtime_ns
            sdk.prepare(args)
        self.assertEqual(license_path.read_bytes(), (ROOT / "LICENSE").read_bytes())
        self.assertEqual(license_path.stat().st_mtime_ns, previous)
        receipt = json.loads((output / "sdk-artifacts.json").read_text())
        licenses = [item for item in receipt["artifacts"] if item["kind"] == "license"]
        self.assertEqual(licenses, [{"kind": "license", "path": "licenses/LICENSE",
                                    "sha256": hashlib.sha256((ROOT / "LICENSE").read_bytes()).hexdigest(),
                                    "size": (ROOT / "LICENSE").stat().st_size}])
        self.assertTrue(all(item["path"].startswith("ai/nuxie/nuxie-android/")
                            for item in receipt["artifacts"] if item["kind"] == "maven"))

    def test_ordinary_suite_excludes_live_native_smoke_and_preserves_host_option_tests(self):
        paths = list((ROOT / "nuxie-android/src/test").rglob("*.kt")) + list((ROOT / "nuxie-android/src/test").rglob("*.java"))
        selected = suite.classes(paths)
        self.assertNotIn("ai.nuxie.sdk.hostrender.HostRenderSmokeTest", selected)
        self.assertIn("ai.nuxie.sdk.hostrender.HostRenderOptionsTest", selected)
        self.assertIn("ai.nuxie.sdk.NuxieListenerJavaInteropTest", selected)

    def test_local_runtime_header_and_libraries_are_verified_together(self):
        tree = self.directory / "prebuilt"
        for name in runtime.FILES:
            path = tree / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())
        hashes = runtime.inspect_tree(tree, 1024 * 1024)
        self.assertEqual(len(hashes), 5)
        header = tree / "include/nux_capi.generated.h"
        header.write_bytes(b"tampered header")
        self.assertNotEqual(runtime.inspect_tree(tree, 1024 * 1024), hashes)
        with self.assertRaisesRegex(ValueError, "size-budget"):
            runtime.inspect_tree(tree, 1)

    def test_unexpected_archive_paths_are_rejected_before_extraction(self):
        archive = self.directory / "runtime.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("../outside", b"untrusted")
        with self.assertRaisesRegex(ValueError, "five documented"):
            runtime.extract(archive, self.directory / "extracted", 1024)
        self.assertFalse((self.directory / "outside").exists())

    def test_fixture_consumer_uses_only_the_explicit_runtime_public_schema_interface(self):
        runtime_path = self.directory / "selected runtime"
        schema = runtime_path / "crates/nuxie-schema"
        schema.mkdir(parents=True)
        (runtime_path / "MODULE.bazel").write_text('module(name="nuxie_runtime")\n')
        (schema / "BUILD.bazel").write_text('alias(name="nuxie-schema__host",actual=":schema")\n')
        sdk_root = self.directory / "sdk"
        sdk_root.mkdir()
        (sdk_root / ".bazelversion").write_text("9.3.0\n")
        (sdk_root / ".bazel-cache.bazelrc").write_bytes((ROOT / ".bazel-cache.bazelrc").read_bytes())
        with patch.object(semantic, "ROOT", sdk_root):
            generated = semantic.workspace(runtime_path)
        module_text = (generated / "MODULE.bazel").read_text()
        self.assertIn(json.dumps(str(runtime_path)), module_text)
        self.assertIn('@nuxie_runtime//crates/nuxie-schema:nuxie-schema__host', (generated / "BUILD.bazel").read_text())
        self.assertEqual((generated / "semantic-text.rs").readlink(), sdk_root / "scripts/fixtures/semantic-text.rs")
        self.assertEqual((generated / ".bazel-cache.bazelrc").read_bytes(), (ROOT / ".bazel-cache.bazelrc").read_bytes())
        self.assertEqual((generated / ".bazelrc").read_text(), "import %workspace%/.bazel-cache.bazelrc\n")
        self.assertEqual(hashlib.sha256(semantic.ASSET.read_bytes()).hexdigest(), semantic.EXPECTED_SHA256)
        with self.assertRaisesRegex(ValueError, "no Bazel schema interface"):
            semantic.workspace(self.directory / "no runtime metadata")

    def test_exact_revision_and_signed_publications_reject_uncommitted_source(self):
        revision = "a" * 40
        with patch.object(sdk, "git", side_effect=[revision, "?? MODULE.bazel"]):
            with self.assertRaisesRegex(ValueError, "clean SDK checkout"):
                sdk.source_identity("0.2.0-" + revision)
        with patch.object(sdk, "git", side_effect=[revision, " M scripts/stage-runtime.sh"]):
            with self.assertRaisesRegex(ValueError, "clean SDK checkout"):
                sdk.source_identity("0.1.0", signed=True)
        with patch.object(sdk, "git", side_effect=[revision, ""]):
            self.assertEqual(sdk.source_identity("0.2.0-" + revision), (revision, False))
        with patch.object(sdk, "git", side_effect=[revision, " M scripts/stage-runtime.sh"]):
            self.assertEqual(sdk.source_identity("0.1.0"), (revision, True))
        with patch.object(sdk, "git", side_effect=[revision, ""]):
            with self.assertRaisesRegex(ValueError, "differs"):
                sdk.source_identity("0.2.0-" + "b" * 40)

    def test_public_api_oracle_receives_the_actual_publication_bytecode(self):
        publication = self.directory / "sdk.aar"
        with zipfile.ZipFile(publication, "w") as archive:
            archive.writestr("classes.jar", b"the compiled publication bytecode")
        with patch.object(sdk, "ROOT", self.directory), patch.object(sdk, "gradle") as validate:
            sdk.verify_api(publication)
            jar = self.directory / "build/bazel-sdk/api/classes.jar"
            self.assertEqual(jar.read_bytes(), b"the compiled publication bytecode")
            self.assertEqual(validate.call_args.args, (":nuxie-android:bazelApiCheck",))
            self.assertEqual(validate.call_args.kwargs["properties"], ("-PnuxieBazelApiJar=" + str(jar),))
            previous = jar.stat().st_mtime_ns
            sdk.verify_api(publication)
            self.assertEqual(jar.stat().st_mtime_ns, previous)

    def test_instrumentation_accepts_completed_tests_and_runner_skips(self):
        output = (
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_STATUS_CODE: -3\n"
            "INSTRUMENTATION_STATUS_CODE: -4\n"
            "INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        sdk.verify_instrumentation_result(output)

    def test_successful_adb_exit_does_not_hide_junit_failure_or_crashed_runner(self):
        outputs = (
            "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -2\n"
            "INSTRUMENTATION_RESULT: stream=\nFAILURES!!!\nTests run: 1, Failures: 1\nINSTRUMENTATION_CODE: -1\n",
            "INSTRUMENTATION_STATUS_CODE: -1\nINSTRUMENTATION_CODE: -1\n",
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n",
            "INSTRUMENTATION_FAILED: Unable to find instrumentation info\n",
            "INSTRUMENTATION_RESULT: stream=\nOK (0 tests)\nINSTRUMENTATION_CODE: -1\n",
            "INSTRUMENTATION_STATUS_CODE: 1\nOK (1 test)\n",
        )
        for output in outputs:
            with self.subTest(output=output), \
                    patch.object(sdk.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=output)) as run, \
                    patch("builtins.print"):
                with self.assertRaisesRegex(ValueError, "Android instrumentation"):
                    sdk.run_instrumentation(["adb", "-s", "emulator-5554"], "ai.nuxie.sdk.NativeTest")
                self.assertEqual(run.call_args.args[0], ["adb", "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r",
                    "-e", "class", "ai.nuxie.sdk.NativeTest", "ai.nuxie.sdk.test/androidx.test.runner.AndroidJUnitRunner"])

    def test_instrumentation_transport_failure_keeps_adb_diagnostics(self):
        result = subprocess.CompletedProcess(["adb"], 1, stdout="error: device offline\n")
        with patch.object(sdk.subprocess, "run", return_value=result), patch("builtins.print") as output:
            with self.assertRaises(subprocess.CalledProcessError):
                sdk.run_instrumentation(["adb"])
            output.assert_called_once_with(result.stdout, end="", flush=True)


if __name__ == "__main__":
    unittest.main()
