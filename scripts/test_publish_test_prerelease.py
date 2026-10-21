import copy
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import publish_test_prerelease as publisher


SHA = "a" * 40
ENV = {"GITHUB_REPOSITORY": publisher.REPOSITORY, "GITHUB_EVENT_NAME": "push", "GITHUB_REF": publisher.BRANCH, "GITHUB_SHA": SHA}


class PublishTestPrereleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.files = {}
        for name in publisher.FILES:
            path = Path(self.directory.name) / name
            path.write_bytes(("synthetic " + name).encode())
            self.files[name] = path
        self.tag = {"ref": f"refs/tags/{publisher.TAG}", "object": {"type": "commit", "sha": SHA}}
        self.release = {"id": 7, "tag_name": publisher.TAG, "target_commitish": SHA, "prerelease": True,
                        "draft": True, "assets": [], "html_url": "https://github.com/test/example"}

    def asset(self, name, index=1):
        data = self.files[name].read_bytes()
        return {"id": index, "name": name, "state": "uploaded", "size": len(data), "digest": "sha256:" + hashlib.sha256(data).hexdigest()}

    def test_context_only_accepts_exact_repository_push_branch_and_sha(self):
        self.assertEqual(publisher.check_context(ENV), SHA)
        for key, value in [("GITHUB_REPOSITORY", "other/repo"), ("GITHUB_EVENT_NAME", "pull_request"),
                           ("GITHUB_REF", "refs/heads/master"), ("GITHUB_REF", "refs/heads/improve/test-apk-downloads-20261021"), ("GITHUB_SHA", "short")]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                publisher.check_context({**ENV, key: value})

    def test_fixed_second_tag_and_debug_version_are_required(self):
        self.assertEqual(publisher.TAG, "test-20261021.2")
        publisher.check_expected_version(["Version: 1.0.1-mvp-debug (2)", "Minimum SDK: 26"])
        for information in [
            ["Version: 1.0.0-mvp-debug (1)", "Minimum SDK: 26"],
            ["Version: 1.0.1-mvp-debug (1)", "Minimum SDK: 26"],
            ["Version: 1.0.1-mvp-debug (2)", "Minimum SDK: 25"],
            ["Version: 1.0.1-mvp (2)", "Minimum SDK: 26"],
        ]:
            with self.subTest(information=information), self.assertRaises(ValueError):
                publisher.check_expected_version(information)

    def test_wrong_tag_sha_stops_before_any_write(self):
        self.tag["object"]["sha"] = "b" * 40
        with patch.object(publisher, "api", return_value=[self.tag]) as api, self.assertRaises(ValueError):
            publisher.publish(SHA, self.files)
        self.assertEqual([call.args[0] for call in api.call_args_list], ["GET"])

    def test_rejects_existing_stable_release_or_wrong_target(self):
        for changes in [{"prerelease": False}, {"target_commitish": "master"}, {"tag_name": "another"}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                publisher.check_release({**self.release, **changes}, SHA)

    def test_same_commit_different_asset_is_not_overwritten(self):
        asset = self.asset(publisher.FILES[0])
        asset["digest"] = "sha256:" + "0" * 64
        self.release["assets"] = [asset]
        with patch.object(publisher, "api", side_effect=[[self.tag], [self.release]]) as api, self.assertRaises(ValueError):
            publisher.publish(SHA, self.files)
        self.assertTrue(all(call.args[0] == "GET" for call in api.call_args_list))

    def test_rejects_unknown_duplicate_incomplete_or_unverified_assets(self):
        asset = self.asset(publisher.FILES[0])
        invalid = [[{**asset, "name": "secret.keystore"}], [asset, asset],
                   [{**asset, "digest": None}], [{**asset, "state": "starter"}], []]
        for assets in invalid:
            with self.subTest(assets=assets), self.assertRaises(ValueError):
                publisher.check_assets(assets, self.files, complete=True)

    def test_completed_prerelease_is_idempotent_and_read_only(self):
        self.release.update(draft=False, assets=[self.asset(name, i) for i, name in enumerate(publisher.FILES, 1)])
        with patch.object(publisher, "api", side_effect=[[self.tag], [self.release]]) as api, patch.object(publisher, "verify_uploaded_bytes") as verify:
            result = publisher.publish(SHA, self.files)
        self.assertFalse(result["draft"])
        verify.assert_called_once()
        self.assertTrue(all(call.args[0] == "GET" for call in api.call_args_list))

    def test_creates_draft_and_uploads_every_asset_before_publication(self):
        state = {"tag": None, "release": None}
        history = []

        def api(method, endpoint, payload=None):
            history.append((method, endpoint, copy.deepcopy(payload)))
            if endpoint.startswith("git/matching-refs/"):
                return [state["tag"]] if state["tag"] else []
            if endpoint == "releases?per_page=100":
                return []
            if method == "POST" and endpoint == "git/refs":
                self.assertEqual(payload["sha"], SHA)
                state["tag"] = self.tag
                return self.tag
            if method == "POST" and endpoint == "releases":
                self.assertTrue(payload["draft"])
                self.assertTrue(payload["prerelease"])
                self.assertEqual(payload["tag_name"], "test-20261021.2")
                for required in ["1.0.1-mvp-debug", "versionCode 2", "200 条只交付 65 条", "600 条只保留 512 条",
                                 "dm_v2", "WBI", "不能保证覆盖升级", "不承诺全部实时弹幕", "未经真机验证"]:
                    self.assertIn(required, payload["body"])
                state["release"] = copy.deepcopy(self.release)
                return copy.deepcopy(state["release"])
            if method == "PATCH":
                self.assertIn(("verified",), history)
                self.assertEqual(len(state["release"]["assets"]), 3)
                self.assertEqual(payload, {"draft": False, "prerelease": True, "make_latest": "false"})
                state["release"]["draft"] = False
            return copy.deepcopy(state["release"])

        def upload(command, **kwargs):
            self.assertTrue(state["release"]["draft"])
            self.assertNotIn("--clobber", command)
            name = Path(command[4]).name
            state["release"]["assets"].append(self.asset(name, len(state["release"]["assets"]) + 1))
            return subprocess.CompletedProcess(command, 0)

        with patch.object(publisher, "api", side_effect=api), patch.object(publisher.subprocess, "run", side_effect=upload), patch.object(publisher, "verify_uploaded_bytes", side_effect=lambda *args: history.append(("verified",))):
            result = publisher.publish(SHA, self.files)
        self.assertFalse(result["draft"])
        self.assertTrue(result["prerelease"])
        self.assertEqual(sum(item[0] == "PATCH" for item in history), 1)

    def test_byte_verification_failure_never_makes_draft_public(self):
        self.release["assets"] = [self.asset(name, i) for i, name in enumerate(publisher.FILES, 1)]
        with patch.object(publisher, "api", side_effect=[[self.tag], [self.release], self.release]) as api, patch.object(publisher, "verify_uploaded_bytes", side_effect=ValueError("bytes differ")), self.assertRaises(ValueError):
            publisher.publish(SHA, self.files)
        self.assertFalse(any(call.args[0] == "PATCH" for call in api.call_args_list))

    def test_downloaded_asset_bytes_must_match(self):
        asset = self.asset(publisher.FILES[0])
        with patch.object(publisher.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=b"wrong")), self.assertRaises(ValueError):
            publisher.verify_uploaded_bytes([asset], self.files)


if __name__ == "__main__":
    unittest.main()
