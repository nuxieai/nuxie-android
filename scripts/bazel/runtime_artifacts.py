#!/usr/bin/env python3
"""Validate the existing five-file runtime boundary for Bazel imports and staging."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

FILES = (
    "include/nux_capi.generated.h",
    "jniLibs/arm64-v8a/libc++_shared.so",
    "jniLibs/arm64-v8a/libnux_capi.so",
    "jniLibs/x86_64/libc++_shared.so",
    "jniLibs/x86_64/libnux_capi.so",
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inspect_tree(tree, maximum):
    actual = {path.relative_to(tree).as_posix() for path in tree.rglob("*") if path.is_file()}
    if actual - {".artifact-checksum"} != set(FILES):
        raise ValueError("Runtime tree must contain exactly its generated header and four pinned ABI libraries")
    size = sum((tree / name).stat().st_size for name in FILES)
    if size > maximum or any((tree / name).stat().st_size == 0 for name in FILES):
        raise ValueError("Runtime artifact tree is empty or exceeds runtime/size-budget.json")
    return {name: digest(tree / name) for name in FILES}


def extract(archive_path, output, maximum):
    with zipfile.ZipFile(archive_path) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if len(entries) != len(FILES) or {entry.filename for entry in entries} != set(FILES):
            raise ValueError("Runtime archive must contain exactly the five documented files")
        if sum(entry.file_size for entry in entries) > maximum:
            raise ValueError("Runtime archive exceeds its uncompressed size budget")
        for entry in entries:
            path = output / entry.filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(entry))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    download = commands.add_parser("extract")
    download.add_argument("--archive", type=Path, required=True)
    download.add_argument("--output", type=Path, required=True)
    local = commands.add_parser("verify-local")
    local.add_argument("--tree", type=Path, required=True)
    local.add_argument("--provenance", type=Path, required=True)
    stage = commands.add_parser("stamp")
    stage.add_argument("--tree", type=Path, required=True)
    stage.add_argument("--build-inputs", type=Path, required=True)
    stage.add_argument("--source-revision", required=True)
    stage.add_argument("--ndk", required=True)
    stage.add_argument("--output", type=Path, required=True)
    for command in (download, local, stage):
        command.add_argument("--budget", type=Path, required=True)
    args = parser.parse_args()
    maximum = json.loads(args.budget.read_text())["maximumBytes"]
    if args.command == "extract":
        extract(args.archive, args.output, maximum)
    elif args.command == "verify-local":
        provenance = json.loads(args.provenance.read_text())
        if provenance.get("files") != inspect_tree(args.tree, maximum):
            raise ValueError("Staged runtime header or JNI library changed after source verification; run scripts/stage-runtime.sh again")
        if provenance.get("mode") != "local" or provenance.get("androidNdk") != "29.0.14206865" or not re.fullmatch(r"[0-9a-f]{40}", provenance.get("sourceCommit", "")):
            raise ValueError("Staged runtime source or NDK identity is missing")
    else:
        captured = json.loads(args.build_inputs.read_text())
        if captured["sourceRevision"] != args.source_revision or captured["configuration"]["androidNdk"] != args.ndk:
            raise ValueError("Staged runtime build inputs differ from its source checkout or NDK")
        value = {"mode": "local", "sourceCommit": args.source_revision, "androidNdk": args.ndk,
                 "buildInputsSha256": digest(args.build_inputs), "files": inspect_tree(args.tree, maximum)}
        args.output.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
