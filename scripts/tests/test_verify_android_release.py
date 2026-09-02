import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
VERIFIER = REPO_ROOT / "scripts" / "verify-android-release.py"


def elf64(*, alignment: int = 0x4000, offset: int = 0, virtual_address: int = 0) -> bytes:
    header = bytearray(64)
    header[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", header, 16, 3, 183, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    program_header = struct.pack(
        "<IIQQQQQQ",
        1,
        5,
        offset,
        virtual_address,
        virtual_address,
        1,
        1,
        alignment,
    )
    return bytes(header) + program_header


class VerifyAndroidReleaseTest(unittest.TestCase):
    def run_verifier(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VERIFIER), *arguments],
            cwd=REPO_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_accepts_archive_when_every_elf_is_16k_aligned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "sdk.aar"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("jni/arm64-v8a/libnux_capi.so", elf64())
                output.writestr("jni/x86_64/libnuxie_runtime_android.so", elf64(alignment=0x10000))

            result = self.run_verifier("elf-archive", str(archive))

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Verified 2 ELF files", result.stdout)

    def test_rejects_archive_when_any_load_segment_is_under_aligned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "app.apk"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("lib/arm64-v8a/libgood.so", elf64())
                output.writestr("lib/arm64-v8a/libbad.so", elf64(alignment=0x1000))

            result = self.run_verifier("elf-archive", str(archive))

            self.assertEqual(result.returncode, 1)
            self.assertIn("libbad.so", result.stderr)
            self.assertIn("power of two >= 0x4000", result.stderr)

    def test_reports_every_invalid_elf_in_one_archive_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "app.apk"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("lib/arm64-v8a/libfirst.so", elf64(alignment=0x1000))
                output.writestr("lib/x86_64/libsecond.so", elf64(alignment=0x2000))

            result = self.run_verifier("elf-archive", str(archive))

            self.assertEqual(result.returncode, 1)
            self.assertIn("libfirst.so", result.stderr)
            self.assertIn("libsecond.so", result.stderr)

    def test_rejects_load_segment_that_is_not_congruent_to_a_16k_page(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "app.aab"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr(
                    "base/lib/arm64-v8a/libnux_capi.so",
                    elf64(offset=0x1000, virtual_address=0x2000),
                )

            result = self.run_verifier("elf-archive", str(archive))

            self.assertEqual(result.returncode, 1)
            self.assertIn("not congruent modulo 0x4000", result.stderr)

    def test_rejects_archive_without_native_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "empty.aar"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("classes.jar", b"not relevant")

            result = self.run_verifier("elf-archive", str(archive))

            self.assertEqual(result.returncode, 1)
            self.assertIn("contains no .so files", result.stderr)

    def test_rejects_archive_missing_an_expected_abi_library_pair(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "sdk.aar"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("jni/arm64-v8a/libnux_capi.so", elf64())
                output.writestr("jni/x86_64/libnux_capi.so", elf64())
                output.writestr("jni/arm64-v8a/libnuxie_runtime_android.so", elf64())

            result = self.run_verifier(
                "elf-archive",
                str(archive),
                "--expected-abi",
                "arm64-v8a",
                "--expected-abi",
                "x86_64",
                "--expected-library",
                "libnux_capi.so",
                "--expected-library",
                "libnuxie_runtime_android.so",
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("x86_64/libnuxie_runtime_android.so", result.stderr)

    def test_apk_command_requires_zipalign_16k_check_to_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "app.apk"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("lib/arm64-v8a/libnux_capi.so", elf64())

            accepted = self.run_verifier("apk", str(archive), "--zipalign", "/usr/bin/true")
            rejected = self.run_verifier("apk", str(archive), "--zipalign", "/usr/bin/false")

            self.assertEqual(accepted.returncode, 0, accepted.stderr)
            self.assertIn("APK ZIP alignment", accepted.stdout)
            self.assertEqual(rejected.returncode, 1)
            self.assertIn("zipalign -P 16 rejected", rejected.stderr)

    def test_apk_command_reports_elf_and_zip_failures_together(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "app.apk"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("lib/arm64-v8a/libnux_capi.so", elf64(alignment=0x1000))

            result = self.run_verifier("apk", str(archive), "--zipalign", "/usr/bin/false")

            self.assertEqual(result.returncode, 1)
            self.assertIn("p_align must be a power of two >= 0x4000", result.stderr)
            self.assertIn("zipalign -P 16 rejected", result.stderr)

    def test_bundle_config_requires_16k_page_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "bundle-config.txt"
            config.write_text('"pageAlignment": "PAGE_ALIGNMENT_16K"\n')
            accepted = self.run_verifier("bundle-config", str(config))

            config.write_text('"pageAlignment": "PAGE_ALIGNMENT_4K"\n')
            rejected = self.run_verifier("bundle-config", str(config))

            self.assertEqual(accepted.returncode, 0, accepted.stderr)
            self.assertIn("PAGE_ALIGNMENT_16K", accepted.stdout)
            self.assertEqual(rejected.returncode, 1)
            self.assertIn("does not declare PAGE_ALIGNMENT_16K", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
