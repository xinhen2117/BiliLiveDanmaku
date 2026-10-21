#!/usr/bin/env python3
"""One authorized test prerelease only; never moves tags or replaces assets."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

from prepare_test_apk import prepare
from verify_public_suffix_source import check as check_public_suffix_source


REPOSITORY = "xinhen2117/BiliLiveDanmaku"
BRANCH = "refs/heads/improve/test-apk-downloads-20261021"
TAG = "test-20261021.1"
FILES = ("BiliLiveDanmaku-test.apk", "SHA256SUMS", "BUILD-INFO.txt")


def check_context(environment):
    if (environment.get("GITHUB_REPOSITORY") != REPOSITORY
            or environment.get("GITHUB_EVENT_NAME") != "push"
            or environment.get("GITHUB_REF") != BRANCH):
        raise ValueError("Publishing is limited to the authorized repository and one test branch push")
    commit = environment.get("GITHUB_SHA", "")
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValueError("Missing exact build commit")
    return commit


def api(method, endpoint, payload=None):
    command = ["gh", "api", "--method", method, f"repos/{REPOSITORY}/{endpoint}"]
    if payload is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def tag_ref(commit):
    matches = api("GET", f"git/matching-refs/tags/{TAG}")
    exact = [ref for ref in matches if ref["ref"] == f"refs/tags/{TAG}"]
    if len(exact) > 1:
        raise ValueError("Ambiguous tag")
    if exact and (exact[0]["object"].get("type") != "commit" or exact[0]["object"].get("sha") != commit):
        raise ValueError("Existing test tag does not point to this exact commit; refusing to move it")
    return bool(exact)


def check_release(release, commit):
    if (release["tag_name"] != TAG or not release["prerelease"]
            or release["target_commitish"] != commit):
        raise ValueError("Existing release is not this exact test prerelease")


def check_assets(assets, files, complete=False):
    by_name = {asset["name"]: asset for asset in assets}
    if len(by_name) != len(assets) or set(by_name) - set(files):
        raise ValueError("Unexpected or duplicate release assets; refusing to change them")
    if complete and set(by_name) != set(files):
        raise ValueError("Release is missing verified assets")
    for name, asset in by_name.items():
        data = files[name].read_bytes()
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        if asset.get("state") != "uploaded" or asset.get("size") != len(data) or asset.get("digest") != digest:
            raise ValueError(f"Existing asset differs or lacks a verified digest: {name}")
    return by_name


def verify_uploaded_bytes(assets, files):
    for asset in assets:
        asset_id = int(asset["id"])
        result = subprocess.run(
            ["gh", "api", f"repos/{REPOSITORY}/releases/assets/{asset_id}", "-H", "Accept: application/octet-stream"],
            check=True, capture_output=True,
        )
        if result.stdout != files[asset["name"]].read_bytes():
            raise ValueError(f"Downloaded release asset differs: {asset['name']}")


def publish(commit, files):
    exists = tag_ref(commit)
    releases = api("GET", "releases?per_page=100")
    if len(releases) == 100:
        raise ValueError("Release lookup requires pagination; refusing an ambiguous create")
    matches = [release for release in releases if release["tag_name"] == TAG]
    if len(matches) > 1:
        raise ValueError("Ambiguous release")
    release = matches[0] if matches else None
    if release:
        check_release(release, commit)
        if not exists:
            raise ValueError("Release exists without its expected tag")
        check_assets(release["assets"], files, complete=not release["draft"])
        if not release["draft"]:
            verify_uploaded_bytes(release["assets"], files)
            return release  # Already complete: no write, no duplicate publication.
    if not exists:
        api("POST", "git/refs", {"ref": f"refs/tags/{TAG}", "sha": commit})
    if release is None:
        body = (
            "开发测试版 / Debug，未经真机验证，不是稳定版。\n\n"
            "Android 8.0+；包名 com.bililive.danmaku.debug，版本 1.0.0-mvp-debug。"
            "测试包与正式包分开安装，数据不互通。首次请使用默认 Mock 模式，无需登录。\n\n"
            "临时 Debug 签名可能在其他构建中变化，不能保证覆盖升级；卸载会清除该测试包的本地设置和登录信息。"
            "不要在测试包里使用重要账号或敏感凭据。真实登录/弹幕、功耗与防烧屏效果未经真机验证。\n\n"
            "本次包含下载/版本说明、扫码失败可刷新与登录文案修正。"
            "单元测试、Lint、APK 编译及签名/包名/许可资产检查通过，不代表实际设备测试通过。"
            "Lint 仍有已披露的警告及部分库检查未运行。\n\n"
            f"构建提交：{commit}\n构建记录：{os.environ.get('BUILD_RUN_URL', '')}\n\n"
            "下载 APK 时同时核对 SHA256SUMS 和 BUILD-INFO.txt。源码与第三方许可说明随本提交保留。"
        )
        release = api("POST", "releases", {"tag_name": TAG, "target_commitish": commit,
                      "name": "2026-10-21 测试版（Debug，未真机验证）", "body": body,
                      "draft": True, "prerelease": True, "make_latest": "false"})
    check_release(release, commit)
    existing = check_assets(release["assets"], files)
    for name in FILES:
        if name not in existing:
            # No --clobber: never delete or overwrite an existing release asset.
            subprocess.run(["gh", "release", "upload", TAG, str(files[name]), "--repo", REPOSITORY], check=True)
    release = api("GET", f"releases/{release['id']}")
    check_release(release, commit)
    check_assets(release["assets"], files, complete=True)
    verify_uploaded_bytes(release["assets"], files)
    if not tag_ref(commit):
        raise ValueError("Tag disappeared; refusing to publish")
    # Only expose the prerelease after all three uploaded files are verified.
    release = api("PATCH", f"releases/{release['id']}", {"draft": False, "prerelease": True, "make_latest": "false"})
    check_release(release, commit)
    if release["draft"]:
        raise ValueError("Release remains a draft")
    return release


def main():
    commit = check_context(os.environ)
    source = Path("test-apk")
    files = {name: source / name for name in FILES}
    if {path.name for path in source.iterdir()} != set(FILES):
        raise ValueError("Downloaded artifact must contain exactly the three allowed files")
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/34.0.0"
    check_public_suffix_source(Path("app/src/main/assets/open_source_licenses/OkHttp-4.12.0-public_suffix_list.dat"), files[FILES[0]])
    with tempfile.TemporaryDirectory() as temporary:
        verified = Path(temporary)
        prepare(files[FILES[0]], sdk / "aapt", sdk / "apksigner", verified, commit, os.environ["BUILD_RUN_URL"])
        for name, path in files.items():
            if path.read_bytes() != (verified / name).read_bytes():
                raise ValueError(f"Artifact metadata/bytes do not match the independently verified APK: {name}")
    information = files["BUILD-INFO.txt"].read_text(encoding="utf-8").splitlines()
    if "Version: 1.0.0-mvp-debug (1)" not in information or "Minimum SDK: 26" not in information:
        raise ValueError("This one-time publication expects the reviewed test version and minimum SDK")
    release = publish(commit, files)
    print(f"Verified test prerelease: {release['html_url']}")


if __name__ == "__main__":
    main()
