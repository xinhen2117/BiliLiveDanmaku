#!/usr/bin/env python3
"""Validate a debug APK and prepare only the three files intended for sharing."""

import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess


def parse_badging(text):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
    minimum_sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    if package is None or minimum_sdk is None:
        raise ValueError("APK package/version/minimum SDK metadata is missing")
    app_id, version_code, version_name = package.groups()
    if app_id != "com.bililive.danmaku.debug":
        raise ValueError("Refusing to share an APK without the isolated debug application ID")
    if not version_code.isdigit() or int(version_code) < 1 or not version_name.endswith("-debug"):
        raise ValueError("Invalid debug version metadata")
    if "application-debuggable" not in text.splitlines():
        raise ValueError("Expected an explicitly debuggable test APK")
    return app_id, version_code, version_name, minimum_sdk.group(1)


def signer_fingerprint(text):
    match = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$", text, re.M)
    if match is None:
        raise ValueError("Verified APK has no readable SHA-256 signer fingerprint")
    return match.group(1).lower()


def prepare(apk, aapt, apksigner, destination, commit, run_url):
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValueError("A full build commit SHA is required")
    # These tools must succeed before anything is copied for distribution.
    badging = subprocess.run([str(aapt), "dump", "badging", str(apk)], check=True, capture_output=True, text=True).stdout
    app_id, code, version, sdk = parse_badging(badging)
    signature = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)], check=True, capture_output=True, text=True).stdout
    fingerprint = signer_fingerprint(signature)
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / "BiliLiveDanmaku-test.apk"
    shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (destination / "SHA256SUMS").write_text(f"{digest}  {target.name}\n", encoding="utf-8")
    (destination / "BUILD-INFO.txt").write_text(
        "BiliLiveDanmaku DEBUG TEST BUILD / 未真机验证的测试包\n"
        f"Build commit: {commit}\nBuild run: {run_url}\n"
        f"Application ID: {app_id}\nVersion: {version} ({code})\nMinimum SDK: {sdk}\n"
        f"Debuggable: true\nAPK SHA-256: {digest}\nSigner SHA-256: {fingerprint}\n\n"
        "This is not a production release. No real-device, live-login or power testing is implied.\n"
        "首次试用请保持 Mock 模式；不要在测试包中使用重要账号或敏感凭据。\n"
        "The temporary debug certificate can change between CI runs; in-place updates are not guaranteed.\n"
        "同包名但签名不同会导致更新失败。卸载测试包会清除其本地设置和登录信息。\n"
        "The .debug application ID is separate from the production app; their data is not shared.\n",
        encoding="utf-8",
    )
    print(f"Verified debug APK: {app_id}, {version}; SHA-256 {digest}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("apk", "aapt", "apksigner", "destination"):
        parser.add_argument(f"--{argument}", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-url", default="Local build (no CI run)")
    arguments = parser.parse_args()
    prepare(**vars(arguments))
