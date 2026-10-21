import copy
import hashlib
import unittest
from unittest.mock import patch

import append_test_release_note as note


class AppendTestReleaseNoteTest(unittest.TestCase):
    def setUp(self):
        self.original = "Synthetic original release notes"
        digest = hashlib.sha256(self.original.encode()).hexdigest()
        self.hash_patch = patch.object(note, "ORIGINAL_BODY_SHA256", digest)
        self.hash_patch.start()
        self.addCleanup(self.hash_patch.stop)
        self.release = {
            "id": note.RELEASE_ID, "tag_name": note.TAG, "target_commitish": note.COMMIT,
            "draft": False, "prerelease": True, "body": self.original,
            "assets": [{"name": name, "id": value[0], "state": "uploaded", "size": value[1],
                        "digest": "sha256:" + value[2]} for name, value in note.ASSETS.items()],
        }

    def test_only_exact_push_context_is_accepted(self):
        environment = {"GITHUB_REPOSITORY": note.REPOSITORY, "GITHUB_EVENT_NAME": "push",
                       "GITHUB_REF": note.BRANCH}
        note.check_context(environment)
        for key in environment:
            with self.subTest(key=key), self.assertRaises(ValueError):
                note.check_context(dict(environment, **{key: "different"}))

    def test_body_may_only_be_original_or_original_plus_single_note(self):
        self.assertEqual(note.original_body(self.original), self.original)
        self.assertEqual(note.original_body(self.original + "\n\n" + note.NOTE), self.original)
        for body in [None, "edited", self.original + " unrelated edit",
                     self.original + "\n\n" + note.NOTE + "\n\n" + note.NOTE]:
            with self.subTest(body=body), self.assertRaises(ValueError):
                note.original_body(body)

    def test_release_identity_and_public_prerelease_are_fixed(self):
        note.check_release(self.release)
        for key, value in [("id", 123), ("tag_name", "other"), ("target_commitish", "a" * 40),
                           ("draft", True), ("prerelease", False)]:
            changed = dict(self.release, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                note.check_release(changed)

    def test_asset_set_ids_sizes_and_hashes_are_fixed(self):
        for key, value in [("id", 123), ("size", 0), ("digest", "sha256:wrong"), ("state", "new")]:
            changed = copy.deepcopy(self.release)
            changed["assets"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                note.check_release(changed)
        for assets in [self.release["assets"][:-1], self.release["assets"] * 2]:
            with self.assertRaises(ValueError):
                note.check_release(dict(self.release, assets=assets))

    def test_changed_or_annotated_tag_is_rejected(self):
        ref = {"ref": "refs/tags/" + note.TAG, "object": {"type": "commit", "sha": note.COMMIT}}
        with patch.object(note, "api", return_value=ref):
            note.verify_tag()
        for field, value in [("type", "tag"), ("sha", "a" * 40)]:
            changed = copy.deepcopy(ref)
            changed["object"][field] = value
            with patch.object(note, "api", return_value=changed), self.assertRaises(ValueError):
                note.verify_tag()

    def test_only_body_patch_is_possible_and_other_writes_fail_before_subprocess(self):
        with patch.object(note.subprocess, "run") as run:
            for method, endpoint, payload in [
                ("POST", "git/refs", {}), ("DELETE", "releases/123", None),
                ("PATCH", f"releases/{note.RELEASE_ID}", {"draft": False}),
                ("PATCH", f"releases/{note.RELEASE_ID}", {"body": "unrelated"}),
                ("PATCH", "releases/123", {"body": self.original + "\n\n" + note.NOTE}),
            ]:
                with self.assertRaises(ValueError):
                    note.api(method, endpoint, payload)
            run.assert_not_called()

    def test_download_byte_mismatch_is_rejected(self):
        with patch.object(note.subprocess, "run") as run:
            run.return_value.stdout = b"changed"
            with self.assertRaises(ValueError):
                note.verify_downloads()

    def run_append(self, initial, second=None):
        state = copy.deepcopy(initial)
        writes = []
        reads = 0

        def api(method, endpoint, payload=None):
            nonlocal reads
            self.assertEqual(endpoint, f"releases/{note.RELEASE_ID}")
            if method == "PATCH":
                self.assertEqual(set(payload), {"body"})
                writes.append(payload)
                state.update(payload)
            else:
                self.assertEqual(method, "GET")
                reads += 1
                if reads == 2 and second is not None:
                    state.update(second)
            return copy.deepcopy(state)

        with patch.object(note, "api", side_effect=api), patch.object(note, "verify_tag"), \
                patch.object(note, "verify_downloads"):
            note.append_note()
        return state, writes

    def test_append_preserves_original_body_and_changes_no_other_field(self):
        state, writes = self.run_append(self.release)
        self.assertEqual(writes, [{"body": self.original + "\n\n" + note.NOTE}])
        self.assertEqual({k: v for k, v in state.items() if k != "body"},
                         {k: v for k, v in self.release.items() if k != "body"})

    def test_repeat_is_read_only(self):
        initial = dict(self.release, body=self.original + "\n\n" + note.NOTE)
        state, writes = self.run_append(initial)
        self.assertEqual(state, initial)
        self.assertEqual(writes, [])

    def test_intervening_body_edit_stops_without_patch(self):
        with self.assertRaises(ValueError):
            self.run_append(self.release, {"body": self.original + " someone else's edit"})


if __name__ == "__main__":
    unittest.main()
