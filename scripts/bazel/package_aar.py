#!/usr/bin/env python3
"""Deterministic packaging of directly compiled Android SDK outputs."""

import argparse
import io
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET

ABIS = ("arm64-v8a", "x86_64")
RUNTIME_LIBRARIES = ("libc++_shared.so", "libnux_capi.so")


def write_zip(output, entries):
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)


def aar(base, lint, consumer_rules, shims, runtime, output):
    with zipfile.ZipFile(base) as archive:
        entries = {name: archive.read(name) for name in archive.namelist() if not name.endswith("/")}
    for name in ("AndroidManifest.xml", "classes.jar", "R.txt"):
        if not entries.get(name):
            raise ValueError(f"Compiled resource AAR is missing {name}")
    if not any(name.startswith("res/values-fr/") for name in entries):
        raise ValueError("Compiled resource AAR lost the French accessibility resources")
    entries["lint.jar"] = Path(lint).read_bytes()
    entries["proguard.txt"] = Path(consumer_rules).read_bytes()
    entries["META-INF/com/android/build/gradle/aar-metadata.properties"] = (
        b"aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=1\nminCompileSdkExtension=0\n"
        b"minAndroidGradlePluginVersion=1.0.0\ncoreLibraryDesugaringEnabled=false\n"
    )
    expected = {f"{abi}/{name}" for abi in ABIS for name in RUNTIME_LIBRARIES}
    actual = set()
    for path in runtime:
        path = Path(path)
        name = path.parent.name + "/" + path.name
        if name not in expected or name in actual:
            raise ValueError(f"Unexpected or duplicate runtime library: {name}")
        entries["jni/" + name] = path.read_bytes()
        actual.add(name)
    if actual != expected:
        raise ValueError(f"Incomplete pinned runtime library set: {sorted(expected - actual)}")
    shim_paths = {}
    for item in shims:
        abi, path = item.split("=", 1)
        if abi not in ABIS or abi in shim_paths:
            raise ValueError(f"Unexpected or duplicate JNI adapter ABI: {abi}")
        shim_paths[abi] = Path(path)
    if set(shim_paths) != set(ABIS):
        raise ValueError("Both directly compiled JNI adapters are required")
    for abi, path in shim_paths.items():
        entries[f"jni/{abi}/libnuxie_runtime_android.so"] = path.read_bytes()
    write_zip(output, entries)


def sources(paths, output):
    entries = {}
    for path in paths:
        path = Path(path)
        marker = "/src/main/"
        relative = str(path).split(marker, 1)[1]
        relative = relative.split("/", 1)[1]
        if relative in entries:
            raise ValueError(f"Duplicate source archive path: {relative}")
        entries[relative] = path.read_bytes()
    write_zip(output, entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    archive = commands.add_parser("aar")
    for name in ("base", "lint", "consumer-rules", "output"):
        archive.add_argument("--" + name, type=Path, required=True)
    archive.add_argument("--runtime", action="append", default=[])
    archive.add_argument("--shim", action="append", default=[])
    source = commands.add_parser("sources")
    source.add_argument("--output", type=Path, required=True)
    source.add_argument("sources", nargs="+")
    manifest = commands.add_parser("manifest")
    manifest.add_argument("--input", type=Path, required=True)
    manifest.add_argument("--output", type=Path, required=True)
    instrumentation = commands.add_parser("instrumentation-manifest")
    instrumentation.add_argument("--input", type=Path, required=True)
    instrumentation.add_argument("--output", type=Path, required=True)
    native = commands.add_parser("native-aar")
    native.add_argument("--input", type=Path, required=True)
    native.add_argument("--output", type=Path, required=True)
    directory = commands.add_parser("directory")
    directory.add_argument("--input", type=Path, required=True)
    directory.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "aar":
        aar(args.base, args.lint, args.consumer_rules, args.shim, args.runtime, args.output)
    elif args.command == "sources":
        sources(args.sources, args.output)
    elif args.command == "directory":
        entries = {path.relative_to(args.input).as_posix(): path.read_bytes() for path in args.input.rglob("*") if path.is_file()}
        if not entries.get("ai/nuxie/sdk/Nuxie.html"):
            raise ValueError("Dokka did not generate documentation for the public Nuxie API")
        write_zip(args.output, entries)
    elif args.command == "native-aar":
        with zipfile.ZipFile(args.input) as archive:
            entries = {name: archive.read(name) for name in archive.namelist() if name.startswith("jni/")}
        empty = io.BytesIO()
        with zipfile.ZipFile(empty, "w"):
            pass
        entries["classes.jar"] = empty.getvalue()
        entries["AndroidManifest.xml"] = b'<manifest package="ai.nuxie.sdk.nativebridge" />'
        entries["R.txt"] = b""
        write_zip(args.output, entries)
    else:
        ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
        root = ET.fromstring(args.input.read_bytes())
        root.set("package", "ai.nuxie.sdk.test" if args.command == "instrumentation-manifest" else "ai.nuxie.sdk")
        sdk = ET.SubElement(root, "uses-sdk")
        sdk.set("{http://schemas.android.com/apk/res/android}minSdkVersion", "23")
        sdk.set("{http://schemas.android.com/apk/res/android}targetSdkVersion", "36")
        if args.command == "instrumentation-manifest":
            runner = ET.SubElement(root, "instrumentation")
            runner.set("{http://schemas.android.com/apk/res/android}name", "androidx.test.runner.AndroidJUnitRunner")
            runner.set("{http://schemas.android.com/apk/res/android}targetPackage", "ai.nuxie.sdk.test")
        args.output.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))


if __name__ == "__main__":
    main()
