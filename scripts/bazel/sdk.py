#!/usr/bin/env python3
"""Build the native Android SDK directly with Bazel and prepare its Maven boundary."""

import argparse
import hashlib
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cache import startup_options

ROOT = Path(__file__).resolve().parents[2]
GROUP = "ai.nuxie"
ARTIFACT = "nuxie-android"
TARGETS = {".aar": "//:sdk_aar", "-sources.jar": "//:sdk_sources", "-javadoc.jar": "//:sdk_javadoc"}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def declared_version():
    values = re.findall(r'^version = "([^"]+)"$', (ROOT / "build.gradle.kts").read_text(), re.MULTILINE)
    if len(values) != 1:
        raise ValueError("Expected one canonical SDK version in build.gradle.kts")
    return values[0]


def source_identity(version, signed=False):
    revision = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain"))
    pinned = re.search(r"-([0-9a-f]{40})$", version)
    if pinned and pinned.group(1) != revision:
        raise ValueError("Source-addressed Maven version differs from this SDK checkout")
    if dirty and (signed or pinned):
        raise ValueError("Signed or source-addressed Maven preparation requires a clean SDK checkout; commit the reviewed implementation first")
    return revision, dirty


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_space():
    if shutil.disk_usage(ROOT).free < 5 * 1024**3:
        raise ValueError("Android build needs at least 5 GiB of free disk")


def gradle(*tasks, properties=()):
    require_space()
    subprocess.run([str(ROOT / "gradlew"), "--no-daemon", "--max-workers=2", *properties, *tasks], cwd=ROOT, check=True)


def toolchain():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to the existing Android SDK")
    sdk = Path(sdk).resolve()
    for relative in ("platforms/android-36/android.jar", "build-tools/36.0.0/aapt2"):
        if not (sdk / relative).is_file():
            raise ValueError(f"Pinned Android tool is missing: {sdk / relative}")
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", sdk / "ndk/29.0.14206865")).resolve()
    properties = dict(re.findall(r"^(\w[\w.]*)\s*=\s*(.+)$", (ndk / "source.properties").read_text(), re.MULTILINE))
    if properties.get("Pkg.Revision") != "29.0.14206865":
        raise ValueError("Android SDK compilation requires the pinned NDK29.0.14206865")
    os.environ["ANDROID_HOME"] = str(sdk)
    os.environ["ANDROID_NDK_HOME"] = str(ndk)
    return sdk, ndk


def bazel_command(workspace=None, output_base_variable="NUXIE_ANDROID_BAZEL_OUTPUT_BASE"):
    workspace = ROOT if workspace is None else workspace
    candidate = os.environ.get("NUXIE_BAZEL_BIN") or shutil.which("bazelisk") or shutil.which("bazel")
    if not candidate:
        parent = ROOT.parent.parent / "node_modules/.bin/bazelisk"
        if parent.is_file():
            candidate = str(parent)
    executable = [candidate] if candidate else [sys.executable, str(ROOT / "scripts/bazel/launcher.py")]
    startup = ["--nosystem_rc", "--nohome_rc", *startup_options(workspace)]
    for variable, option in (("NUXIE_BAZEL_OUTPUT_USER_ROOT", "--output_user_root"), (output_base_variable, "--output_base")):
        value = os.environ.get(variable)
        if value:
            if not Path(value).is_absolute():
                raise ValueError(variable + " must be absolute")
            startup.append(option + "=" + value)
    if os.environ.get("NUXIE_BAZEL_BATCH", os.environ.get("CI", "0")) == "1":
        startup.append("--batch")
    return [*executable, *startup]


def bazel(command, *args, capture=False, workspace=None, output_base_variable="NUXIE_ANDROID_BAZEL_OUTPUT_BASE"):
    require_space()
    workspace = ROOT if workspace is None else workspace
    executable = bazel_command(workspace, output_base_variable)
    flags = []
    if command in ("build", "test", "run", "cquery", "aquery"):
        jobs = os.environ.get("NUXIE_BAZEL_JOBS", "2")
        if not re.fullmatch(r"[1-9][0-9]*", jobs):
            raise ValueError("NUXIE_BAZEL_JOBS must be a positive integer")
        flags.append("--jobs=" + jobs)
    result = subprocess.run([*executable, command, *flags, *args], cwd=workspace,
                            check=True, text=True, stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def artifact(label):
    paths = bazel("cquery", label, "--output=files", capture=True).splitlines()
    if len(paths) != 1:
        raise ValueError(f"Expected exactly one artifact for {label}; got {paths}")
    path = Path(paths[0])
    return path if path.is_absolute() else ROOT / path


def dependencies():
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
    return [
        ("com.android.billingclient", "billing", versions["billing"], "compile"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", versions["coroutines"], "compile"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", versions["kotlin"], "compile"),
        ("androidx.sqlite", "sqlite-framework", versions["sqlite"], "runtime"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-json", versions["serialization-json"], "runtime"),
    ]


def pom(version):
    metadata = json.loads((ROOT / "scripts/bazel/publication.json").read_text())
    namespace = "http://maven.apache.org/POM/4.0.0"
    ET.register_namespace("", namespace)
    node = ET.Element("{" + namespace + "}project")
    node.append(ET.Comment(" do_not_remove: published-with-gradle-metadata "))
    def append(parent, field, value):
        child = ET.SubElement(parent, "{" + namespace + "}" + field)
        child.text = value
        return child
    for field, value in (("modelVersion", "4.0.0"), ("groupId", GROUP), ("artifactId", ARTIFACT), ("version", version), ("packaging", "aar")):
        append(node, field, value)
    for field in ("name", "description", "url"):
        append(node, field, metadata[field])
    for plural, singular in (("licenses", "license"), ("developers", "developer")):
        parent = append(node, plural, None)
        child = append(parent, singular, None)
        for field, value in metadata[singular].items():
            append(child, field, value)
    scm = append(node, "scm", None)
    for field, value in metadata["scm"].items():
        append(scm, field, value)
    deps = append(node, "dependencies", None)
    for group, name, selected, scope in dependencies():
        dependency = append(deps, "dependency", None)
        for field, value in (("groupId", group), ("artifactId", name), ("version", selected), ("scope", scope)):
            append(dependency, field, value)
    ET.indent(node)
    return ET.tostring(node, encoding="utf-8", xml_declaration=True)


def module_metadata(version, directory):
    stem = ARTIFACT + "-" + version
    def file(suffix):
        path = directory / (stem + suffix)
        data = path.read_bytes()
        value = {"name": path.name, "url": path.name, "size": len(data)}
        value.update({algorithm: hashlib.new(algorithm, data).hexdigest() for algorithm in ("md5", "sha1", "sha256", "sha512")})
        return value
    variants = []
    for usage in ("java-api", "java-runtime"):
        deps = [{"group": group, "module": name, "version": {"requires": selected}}
                for group, name, selected, scope in dependencies() if usage == "java-runtime" or scope == "compile"]
        variants.append({"name": "releaseApiElements-published" if usage == "java-api" else "releaseRuntimeElements-published",
                         "attributes": {"org.gradle.category": "library", "org.gradle.dependency.bundling": "external",
                                        "org.gradle.libraryelements": "aar", "org.gradle.usage": usage,
                                        "org.jetbrains.kotlin.platform.type": "androidJvm"},
                         "dependencies": deps, "files": [file(".aar")]})
    for kind, suffix in (("sources", "-sources.jar"), ("javadoc", "-javadoc.jar")):
        variants.append({"name": "release" + kind.title() + "Elements-published",
                         "attributes": {"org.gradle.category": "documentation", "org.gradle.dependency.bundling": "external",
                                        "org.gradle.docstype": kind, "org.gradle.usage": "java-runtime"},
                         "files": [file(suffix)]})
    return {"formatVersion": "1.1", "component": {"group": GROUP, "module": ARTIFACT, "version": version,
                                                   "attributes": {"org.gradle.status": "release"}}, "variants": variants}


def verify_native(aar_path, ndk):
    command = [sys.executable, str(ROOT / "scripts/verify-android-release.py"), "elf-archive", str(aar_path)]
    for abi in ("arm64-v8a", "x86_64"):
        command += ["--expected-abi", abi]
    for name in ("libc++_shared.so", "libnux_capi.so", "libnuxie_runtime_android.so"):
        command += ["--expected-library", name]
    subprocess.run(command, check=True)
    readers = list((ndk / "toolchains/llvm/prebuilt").glob("*/bin/llvm-readelf"))
    if len(readers) != 1:
        raise ValueError("Expected one readelf in the pinned NDK")
    with tempfile.TemporaryDirectory(prefix="nuxie-sdk-elf-") as temporary:
        with zipfile.ZipFile(aar_path) as archive:
            expected = {f"jni/{abi}/{name}" for abi in ("arm64-v8a", "x86_64")
                        for name in ("libc++_shared.so", "libnux_capi.so", "libnuxie_runtime_android.so")}
            if {name for name in archive.namelist() if name.endswith(".so")} != expected:
                raise ValueError("SDK AAR contains unexpected native libraries")
            for abi in ("arm64-v8a", "x86_64"):
                path = Path(temporary) / (abi + ".so")
                path.write_bytes(archive.read(f"jni/{abi}/libnuxie_runtime_android.so"))
                dynamic = subprocess.check_output([str(readers[0]), "--dynamic", str(path)], text=True)
                needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
                if "libnux_capi.so" not in needed or any("/" in value for value in needed):
                    raise ValueError("JNI adapter must link the runtime by its portable library name")


def verify_api(aar_path):
    directory = ROOT / "build/bazel-sdk/api"
    directory.mkdir(parents=True, exist_ok=True)
    jar = directory / "classes.jar"
    with zipfile.ZipFile(aar_path) as archive:
        data = archive.read("classes.jar")
    if not jar.is_file() or jar.read_bytes() != data:
        temporary = jar.with_suffix(".tmp")
        temporary.write_bytes(data)
        temporary.replace(jar)
    gradle(":nuxie-android:bazelApiCheck", properties=("-PnuxieBazelApiJar=" + str(jar),))


def verify_instrumentation_result(output):
    # adb shell can exit successfully even when AndroidJUnitRunner reports a
    # failing test or crashes. Use the runner's raw result protocol as the oracle.
    status = [int(value) for value in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", output, re.MULTILINE)]
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", output, re.MULTILINE)
    summary = re.search(r"^OK \(([1-9]\d*) tests?\)\s*$", output, re.MULTILINE)
    failed = re.search(r"^INSTRUMENTATION_(?:FAILED|ABORTED):|^INSTRUMENTATION_RESULT: shortMsg=", output, re.MULTILINE)
    if terminal != ["-1"] or summary is None or failed or any(value not in (1, 0, -3, -4) for value in status):
        raise ValueError("Android instrumentation failed or did not report a completed test run")
    if not any(value in (0, -3, -4) for value in status):
        raise ValueError("Android instrumentation reported no completed tests")


def run_instrumentation(adb, test_class=None):
    command = adb + ["shell", "am", "instrument", "-w", "-r"]
    if test_class:
        command += ["-e", "class", test_class]
    command += ["ai.nuxie.sdk.test/androidx.test.runner.AndroidJUnitRunner"]
    result = subprocess.run(command, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end="", flush=True)
    result.check_returncode()
    verify_instrumentation_result(result.stdout)


def sign(directory, files):
    key = os.environ.get("NUXIE_SIGNING_KEY")
    if not key:
        raise ValueError("NUXIE_SIGNING_KEY is required for signed release preparation")
    with tempfile.TemporaryDirectory(prefix="nuxie-sdk-signing-") as temporary:
        command = ["gpg", "--batch", "--no-tty", "--homedir", temporary]
        try:
            subprocess.run(command + ["--import"], input=key, text=True, check=True, capture_output=True)
            for path in files:
                subprocess.run(command + ["--yes", "--armor", "--pinentry-mode", "loopback", "--passphrase-fd", "0",
                                          "--detach-sign", str(path)], input=os.environ.get("NUXIE_SIGNING_PASSWORD", ""),
                               text=True, check=True, capture_output=True)
        finally:
            subprocess.run(["gpgconf", "--homedir", temporary, "--kill", "gpg-agent"], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def publish_directory(staging, destination):
    """Replace one Maven version atomically, preserving other coordinates and versions."""
    if destination.exists():
        expected = {path.name for path in staging.iterdir()}
        if {path.name for path in destination.iterdir()} == expected and all(
                (destination / path.name).is_file() and (destination / path.name).read_bytes() == path.read_bytes()
                for path in staging.iterdir()):
            return
    backup = staging.parent / (staging.name + "-backup")
    if destination.exists():
        destination.rename(backup)
    try:
        staging.rename(destination)
    except BaseException:
        if backup.exists():
            backup.rename(destination)
        raise
    if backup.exists():
        shutil.rmtree(backup)


def prepare(args):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", args.maven_version):
        raise ValueError("Expected a Maven version without path characters")
    revision, dirty = source_identity(args.maven_version, args.sign)
    _, ndk = toolchain()
    bazel("build", *TARGETS.values())
    inputs = {suffix: artifact(label) for suffix, label in TARGETS.items()}
    provenance = json.loads(artifact("@nuxie_runtime_android//:provenance.json").read_text())
    if provenance["mode"] != "published" and (args.sign or re.search(r"-[0-9a-f]{40}$", args.maven_version)):
        raise ValueError("Signed or source-addressed Maven preparation requires the published pinned runtime")
    verify_api(inputs[".aar"])
    verify_native(inputs[".aar"], ndk)
    license_data = (ROOT / "LICENSE").read_bytes()
    if source_identity(args.maven_version, args.sign) != (revision, dirty):
        raise ValueError("SDK source identity changed during preparation")
    output = args.output.resolve()
    destination = output / "ai/nuxie/nuxie-android" / args.maven_version
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".nuxie-sdk-", dir=destination.parent) as temporary:
        staging = Path(temporary)
        stem = ARTIFACT + "-" + args.maven_version
        for suffix, path in inputs.items():
            shutil.copyfile(path, staging / (stem + suffix))
        (staging / (stem + ".pom")).write_bytes(pom(args.maven_version))
        (staging / (stem + ".module")).write_text(json.dumps(module_metadata(args.maven_version, staging), indent=2) + "\n")
        products = sorted(staging.iterdir())
        if args.sign:
            if provenance["mode"] != "published":
                raise ValueError("Signed releases must use the published pinned runtime")
            sign(staging, products)
        for path in list(staging.iterdir()):
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                (staging / (path.name + "." + algorithm)).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())
        with (output / ".sdk-prepare.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            publish_directory(staging, destination)
            records = [{"kind": "maven", "path": path.relative_to(output).as_posix(), "sha256": sha256(path), "size": path.stat().st_size}
                       for path in sorted(destination.iterdir()) if path.is_file()]
            license_path = output / "licenses/LICENSE"
            license_path.parent.mkdir(exist_ok=True)
            if not license_path.is_file() or license_path.read_bytes() != license_data:
                temporary_license = license_path.with_name(".LICENSE.tmp")
                temporary_license.write_bytes(license_data)
                temporary_license.replace(license_path)
            records.append({"kind": "license", "path": "licenses/LICENSE", "sha256": sha256(license_path),
                            "size": license_path.stat().st_size})
            manifest = {"schemaVersion": 1, "sdk": "android", "sourceRevision": revision,
                        "sourceDirty": dirty, "runtime": provenance,
                        "maven": {"groupId": GROUP, "artifactId": ARTIFACT, "version": args.maven_version, "repository": "."}, "artifacts": records}
            temporary_manifest = output / ".sdk-artifacts.json.tmp"
            temporary_manifest.write_text(json.dumps(manifest, indent=2) + "\n")
            temporary_manifest.replace(output / "sdk-artifacts.json")
    print(output / "sdk-artifacts.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepared = commands.add_parser("prepare")
    prepared.add_argument("--output", type=Path, default=ROOT / "build/bazel-sdk/maven")
    prepared.add_argument("--maven-version", default=declared_version())
    prepared.add_argument("--sign", action="store_true")
    for name in ("build", "test", "example", "instrumentation", "doctor", "check", "api-check", "lint", "providers", "host-render"):
        commands.add_parser(name)
    device = commands.add_parser("instrumentation-run")
    device.add_argument("--serial", required=True)
    device.add_argument("--class", dest="test_class")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args)
    elif args.command == "doctor":
        print("SDK, NDK:", *toolchain())
    else:
        toolchain()
        if args.command == "test":
            bazel("test", "//:tests")
        elif args.command == "check":
            bazel("test", "//:tests")
            bazel("build", "//:sdk_aar", "//:example_app_debug", "//:sdk_instrumentation_apk")
            verify_api(artifact("//:sdk_aar"))
            # These are independent compatibility oracles for the reviewed
            # public API dump, Android lint policy and AGP packaging contract.
            gradle(":nuxie-android:test", ":nuxie-android:apiCheck", ":nuxie-android:lint", ":example-app:assembleDebug", "verifyAndroidReleaseArtifacts")
        elif args.command == "api-check":
            bazel("build", "//:sdk_aar")
            verify_api(artifact("//:sdk_aar"))
            gradle(":nuxie-android:apiCheck")
        elif args.command in ("lint", "host-render"):
            gradle({"lint": ":nuxie-android:lint", "host-render": ":nuxie-android:hostRenderSmoke"}[args.command])
        elif args.command == "providers":
            for provider in ("revenuecat", "superwall"):
                gradle(":example-" + provider + ":test", ":example-app:test", ":example-app:verifyPurchaseProviderSelection", ":example-app:assembleDebug",
                       properties=("-PnuxieExamplePurchaseProvider=" + provider,))
        elif args.command == "instrumentation-run":
            bazel("build", "//:sdk_instrumentation_apk")
            apk = artifact("//:sdk_instrumentation_apk")
            adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", args.serial]
            subprocess.run(adb + ["install", "-r", str(apk)], check=True)
            run_instrumentation(adb, args.test_class)
        else:
            label = {"build": "//:sdk_aar", "example": "//:example_app_debug", "instrumentation": "//:sdk_instrumentation_apk"}[args.command]
            bazel("build", label)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
