#!/usr/bin/env python3
from __future__ import annotations

import contextlib
import io
import unittest
from unittest import mock

import release_automation


def pr_entry(number: int, title: str) -> release_automation.PullRequestEntry:
    return release_automation.PullRequestEntry(
        number,
        title,
        "",
        f"https://example.invalid/pull/{number}",
        "contributor",
        (),
        release_automation.parse_semantic_title(title),
    )


class SnapshotTargetTest(unittest.TestCase):
    def test_feature_snapshot_target_bumps_once_per_release_line(self) -> None:
        entries = [
            pr_entry(1, "feat(cli): add config file"),
            pr_entry(2, "feat(core): format compact constructors"),
        ]
        latest_release = ("v0.1.0", release_automation.Version(0, 1, 0))

        with (
            mock.patch.object(release_automation, "latest_release_tag", return_value=latest_release),
            mock.patch.object(release_automation, "read_version", return_value="0.1.1-SNAPSHOT"),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            self.assertEqual("0.2.0-SNAPSHOT", release_automation.snapshot_target_from(entries))

        with (
            mock.patch.object(release_automation, "latest_release_tag", return_value=latest_release),
            mock.patch.object(release_automation, "read_version", return_value="0.2.0-SNAPSHOT"),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            self.assertIsNone(release_automation.snapshot_target_from(entries))


if __name__ == "__main__":
    unittest.main()
