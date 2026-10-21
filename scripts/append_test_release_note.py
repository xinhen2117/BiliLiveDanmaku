#!/usr/bin/env python3
"""Append one authorized warning to one existing prerelease; never touch its binaries."""

import hashlib
import json
import os
import subprocess

REPOSITORY = "xinhen2117/BiliLiveDanmaku"
BRANCH = "refs/heads/maintain/test-release-note-20261021"
TAG = "test-20261021.1"
COMMIT = "654ae346ede1470c41d080dfaa0d68076ecdd383"
RELEASE_ID = 419700277
ORIGINAL_BODY_SHA256 = "4f17781532d7a6fd96a2d6f199dfd3164775c13100a895f21006b6f7bfe84948"
NOTE = "已知局限：高流量/批量弹幕可能丢失，后续版本优化中。"
ASSETS = {
    "BiliLiveDanmaku-test.apk": (662881303, 17481955, "1e27f4651013341e439d1bbedb034e070a2c304865e8ecaf768a76c34f914127"),
    "BUILD-INFO.txt": (662881487, 941, "f17a73ff94f86fe995f943308ade29013ebb0bc7a69bad64d47d648752829fe4"),
    "SHA256SUMS": (662881411, 91, "eb278f5f49bcca4c69dc5e3b5224a8777229eeb29a088a112a0d19d64ec27746"),
}


def check_context(environment):
    if (environment.get("GITHUB_REPOSITORY") != REPOSITORY
            or environment.get("GITHUB_EVENT_NAME") != "push"
            or environment.get("GITHUB_REF") != BRANCH):
        raise ValueError("Only the one authorized metadata branch push may append this note")


def api(method, endpoint, payload=None):
    if method != "GET" and not (
            method == "PATCH" and endpoint == f"releases/{RELEASE_ID}"
            and isinstance(payload, dict) and set(payload) == {"body"}
            and original_body(payload["body"]) != payload["body"]):
        raise ValueError("Only the fixed release body may be changed")
    command = ["gh", "api", "--method", method, f"repos/{REPOSITORY}/{endpoint}"]
    if payload is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                            check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def original_body(body):
    if not isinstance(body, str):
        raise ValueError("Missing original release body")
    suffix = "\n\n" + NOTE
    original = body[:-len(suffix)] if body.endswith(suffix) else body
    if hashlib.sha256(original.encode("utf-8")).hexdigest() != ORIGINAL_BODY_SHA256:
        raise ValueError("Release body changed; refusing to overwrite another edit")
    return original


def check_release(release):
    if (release.get("id") != RELEASE_ID or release.get("tag_name") != TAG
            or release.get("target_commitish") != COMMIT
            or release.get("draft") is not False or release.get("prerelease") is not True):
        raise ValueError("This is not the already published, approved test prerelease")
    original_body(release.get("body"))
    assets = release.get("assets", [])
    if len(assets) != len(ASSETS) or {asset["name"] for asset in assets} != set(ASSETS):
        raise ValueError("The published asset set changed")
    for asset in assets:
        asset_id, size, digest = ASSETS[asset["name"]]
        if (asset.get("id") != asset_id or asset.get("state") != "uploaded"
                or asset.get("size") != size or asset.get("digest") != "sha256:" + digest):
            raise ValueError("Published asset identity or hash changed")


def verify_tag():
    ref = api("GET", f"git/ref/tags/{TAG}")
    if (ref.get("ref") != f"refs/tags/{TAG}"
            or ref.get("object", {}).get("type") != "commit"
            or ref.get("object", {}).get("sha") != COMMIT):
        raise ValueError("Published tag changed; refusing to update its notes")


def verify_downloads():
    for asset_id, size, digest in ASSETS.values():
        result = subprocess.run(
            ["gh", "api", f"repos/{REPOSITORY}/releases/assets/{asset_id}",
             "-H", "Accept: application/octet-stream"], check=True, capture_output=True,
        )
        if len(result.stdout) != size or hashlib.sha256(result.stdout).hexdigest() != digest:
            raise ValueError("Downloaded published asset bytes changed")


def append_note():
    verify_tag()
    release = api("GET", f"releases/{RELEASE_ID}")
    check_release(release)
    verify_downloads()
    original = original_body(release["body"])
    expected = original + "\n\n" + NOTE
    # Re-read immediately before a write; unexpected edits stop instead of being overwritten.
    current = api("GET", f"releases/{RELEASE_ID}")
    check_release(current)
    if current["body"] != expected:
        api("PATCH", f"releases/{RELEASE_ID}", {"body": expected})
    # No automatic write retries: an uncertain write must be inspected on the next run.
    verified = api("GET", f"releases/{RELEASE_ID}")
    check_release(verified)
    verify_tag()
    if verified["body"] != expected:
        raise ValueError("Release note result is uncertain; inspect before any retry")
    print(f"Verified append-only test release note: https://github.com/{REPOSITORY}/releases/tag/{TAG}")


def main():
    check_context(os.environ)
    append_note()


if __name__ == "__main__":
    main()
