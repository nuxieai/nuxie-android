#!/usr/bin/env python3
"""Fail-closed Android release artifact verification."""

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
import zipfile
from pathlib import Path


MINIMUM_PAGE_ALIGNMENT = 0x4000
PT_LOAD = 1


class VerificationError(Exception):
    pass


def read_program_headers(data: bytes, label: str) -> list[tuple[int, int, int]]:
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise VerificationError(f"{label}: not an ELF file")
    elf_class = data[4]
    encoding = data[5]
    if encoding not in (1, 2):
        raise VerificationError(f"{label}: unsupported ELF byte order {encoding}")
    byte_order = "<" if encoding == 1 else ">"

    if elf_class == 1:
        header_size = 52
        program_header_size = 32
        if len(data) < header_size:
            raise VerificationError(f"{label}: truncated ELF32 header")
        program_offset = struct.unpack_from(f"{byte_order}I", data, 28)[0]
        entry_size, entry_count = struct.unpack_from(f"{byte_order}HH", data, 42)
        format_string = f"{byte_order}IIIIIIII"
        offset_index, virtual_address_index, alignment_index = 1, 2, 7
    elif elf_class == 2:
        header_size = 64
        program_header_size = 56
        if len(data) < header_size:
            raise VerificationError(f"{label}: truncated ELF64 header")
        program_offset = struct.unpack_from(f"{byte_order}Q", data, 32)[0]
        entry_size, entry_count = struct.unpack_from(f"{byte_order}HH", data, 54)
        format_string = f"{byte_order}IIQQQQQQ"
        offset_index, virtual_address_index, alignment_index = 2, 3, 7
    else:
        raise VerificationError(f"{label}: unsupported ELF class {elf_class}")

    if entry_size < program_header_size:
        raise VerificationError(f"{label}: invalid program-header entry size {entry_size}")
    table_end = program_offset + entry_size * entry_count
    if program_offset < header_size or table_end > len(data):
        raise VerificationError(f"{label}: truncated program-header table")

    load_segments: list[tuple[int, int, int]] = []
    for index in range(entry_count):
        values = struct.unpack_from(format_string, data, program_offset + index * entry_size)
        if values[0] == PT_LOAD:
            load_segments.append(
                (
                    values[offset_index],
                    values[virtual_address_index],
                    values[alignment_index],
                )
            )
    if not load_segments:
        raise VerificationError(f"{label}: ELF has no PT_LOAD segments")
    return load_segments


def verify_elf(data: bytes, label: str) -> None:
    for index, (offset, virtual_address, alignment) in enumerate(read_program_headers(data, label)):
        if alignment < MINIMUM_PAGE_ALIGNMENT or alignment & (alignment - 1):
            raise VerificationError(
                f"{label}: PT_LOAD[{index}] p_align must be a power of two >= 0x4000 "
                f"(found 0x{alignment:x})"
            )
        if offset % MINIMUM_PAGE_ALIGNMENT != virtual_address % MINIMUM_PAGE_ALIGNMENT:
            raise VerificationError(
                f"{label}: PT_LOAD[{index}] p_offset and p_vaddr are not congruent modulo 0x4000 "
                f"(0x{offset:x} vs 0x{virtual_address:x})"
            )


def verify_elf_archive(
    archive_path: Path,
    expected_abis: list[str] | None = None,
    expected_libraries: list[str] | None = None,
) -> int:
    if not archive_path.is_file():
        raise VerificationError(f"Archive does not exist: {archive_path}")
    try:
        with zipfile.ZipFile(archive_path) as archive:
            elf_entries = [entry for entry in archive.infolist() if not entry.is_dir() and entry.filename.endswith(".so")]
            if not elf_entries:
                raise VerificationError(f"{archive_path}: archive contains no .so files")
            expected_abis = expected_abis or []
            expected_libraries = expected_libraries or []
            if bool(expected_abis) != bool(expected_libraries):
                raise VerificationError("Expected ABIs and libraries must be supplied together")
            failures: list[str] = []
            names = {entry.filename for entry in elf_entries}
            missing = [
                f"{abi}/{library}"
                for abi in expected_abis
                for library in expected_libraries
                if not any(name.endswith(f"/{abi}/{library}") for name in names)
            ]
            if missing:
                failures.append(
                    f"{archive_path}: archive is missing expected native libraries: {', '.join(missing)}"
                )
            for entry in elf_entries:
                try:
                    verify_elf(archive.read(entry), f"{archive_path}!/{entry.filename}")
                except VerificationError as error:
                    failures.append(str(error))
            if failures:
                raise VerificationError("\n".join(failures))
    except zipfile.BadZipFile as error:
        raise VerificationError(f"{archive_path}: invalid ZIP archive") from error
    return len(elf_entries)


def verify_apk(
    apk_path: Path,
    zipalign: Path,
    expected_abis: list[str] | None = None,
    expected_libraries: list[str] | None = None,
) -> int:
    failures: list[str] = []
    try:
        count = verify_elf_archive(apk_path, expected_abis, expected_libraries)
    except VerificationError as error:
        failures.append(str(error))
        count = 0

    if not zipalign.is_file():
        failures.append(f"zipalign does not exist: {zipalign}")
    else:
        result = subprocess.run(
            [str(zipalign), "-c", "-P", "16", "-v", "4", str(apk_path)],
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            details = (result.stderr or result.stdout).strip()
            suffix = f": {details}" if details else ""
            failures.append(f"zipalign -P 16 rejected {apk_path}{suffix}")
    if failures:
        raise VerificationError("\n".join(failures))
    return count


def verify_bundle_config(config_path: Path) -> None:
    if not config_path.is_file():
        raise VerificationError(f"Bundle config dump does not exist: {config_path}")
    try:
        config = config_path.read_text()
    except (OSError, UnicodeError) as error:
        raise VerificationError(f"Could not read bundle config dump: {config_path}") from error
    if "PAGE_ALIGNMENT_16K" not in config:
        raise VerificationError(f"{config_path}: bundle config does not declare PAGE_ALIGNMENT_16K")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    archive = commands.add_parser("elf-archive")
    archive.add_argument("archive", type=Path)
    archive.add_argument("--expected-abi", action="append", default=[])
    archive.add_argument("--expected-library", action="append", default=[])
    apk = commands.add_parser("apk")
    apk.add_argument("archive", type=Path)
    apk.add_argument("--zipalign", type=Path, required=True)
    apk.add_argument("--expected-abi", action="append", default=[])
    apk.add_argument("--expected-library", action="append", default=[])
    bundle_config = commands.add_parser("bundle-config")
    bundle_config.add_argument("config", type=Path)
    return root


def main(arguments: list[str] | None = None) -> int:
    args = parser().parse_args(arguments)
    try:
        if args.command == "elf-archive":
            count = verify_elf_archive(args.archive, args.expected_abi, args.expected_library)
            print(f"Verified {count} ELF files in {args.archive}.")
            return 0
        if args.command == "apk":
            count = verify_apk(
                args.archive,
                args.zipalign,
                args.expected_abi,
                args.expected_library,
            )
            print(f"Verified {count} ELF files and APK ZIP alignment in {args.archive}.")
            return 0
        if args.command == "bundle-config":
            verify_bundle_config(args.config)
            print(f"Verified PAGE_ALIGNMENT_16K in {args.config}.")
            return 0
        raise AssertionError(f"Unhandled command: {args.command}")
    except VerificationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
