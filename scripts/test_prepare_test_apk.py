import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from prepare_test_apk import parse_badging, prepare, signer_fingerprint


BADGING = "package: name='com.bililive.danmaku.debug' versionCode='1' versionName='1.0.0-mvp-debug'\nsdkVersion:'26'\napplication-debuggable\n"
SIGNATURE = "Signer #1 certificate SHA-256 digest: " + "AB" * 32 + "\n"


class PrepareTestApkTest(unittest.TestCase):
    def test_reads_isolated_debug_metadata(self):
        self.assertEqual(parse_badging(BADGING), ("com.bililive.danmaku.debug", "1", "1.0.0-mvp-debug", "26"))

    def test_rejects_wrong_or_missing_metadata(self):
        invalid = [BADGING.replace(".debug'", "'"), BADGING.replace("-debug'", "'"),
                   BADGING.replace("versionCode='1'", "versionCode='0'"),
                   BADGING.replace("application-debuggable", ""), BADGING.replace("sdkVersion:'26'", ""), ""]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_badging(value)

    def test_requires_and_normalizes_signer_sha256(self):
        self.assertEqual(signer_fingerprint(SIGNATURE), "ab" * 32)
        with self.assertRaises(ValueError):
            signer_fingerprint("Signer #1 certificate SHA-256 digest: incomplete")

    def test_shares_only_apk_checksum_and_build_information(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.apk"
            source.write_bytes(b"synthetic APK bytes; tools are mocked")
            destination = root / "shared"
            responses = [subprocess.CompletedProcess([], 0, stdout=BADGING), subprocess.CompletedProcess([], 0, stdout=SIGNATURE)]
            with patch("prepare_test_apk.subprocess.run", side_effect=responses) as run:
                prepare(source, Path("aapt"), Path("apksigner"), destination, "a" * 40, "local")
            self.assertTrue(all(call.kwargs["check"] for call in run.call_args_list))
            self.assertEqual({file.name for file in destination.iterdir()}, {"BiliLiveDanmaku-test.apk", "SHA256SUMS", "BUILD-INFO.txt"})
            self.assertEqual((destination / "BiliLiveDanmaku-test.apk").read_bytes(), source.read_bytes())
            self.assertIn(hashlib.sha256(source.read_bytes()).hexdigest(), (destination / "SHA256SUMS").read_text())
            self.assertIn("Signer SHA-256: " + "ab" * 32, (destination / "BUILD-INFO.txt").read_text())

    def test_failed_signature_does_not_create_shared_files(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "shared"
            responses = [subprocess.CompletedProcess([], 0, stdout=BADGING), subprocess.CalledProcessError(1, "apksigner")]
            with patch("prepare_test_apk.subprocess.run", side_effect=responses), self.assertRaises(subprocess.CalledProcessError):
                prepare(Path("source.apk"), Path("aapt"), Path("apksigner"), destination, "a" * 40, "local")
            self.assertFalse(destination.exists())

    def test_rejects_unidentified_build_before_running_tools(self):
        with patch("prepare_test_apk.subprocess.run") as run, self.assertRaises(ValueError):
            prepare(Path("source.apk"), Path("aapt"), Path("apksigner"), Path("out"), "short-sha", "local")
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
