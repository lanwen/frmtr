#!/usr/bin/env python3
from __future__ import annotations

import contextlib
import io
import os
import tempfile
import unittest
from pathlib import Path
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


class PrepareReleaseTest(unittest.TestCase):
    def test_prepare_release_updates_site_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cwd = Path.cwd()
            workspace = Path(directory)
            (workspace / "site/src/jbake").mkdir(parents=True)
            (workspace / "gradle.properties").write_text("version=0.1.1-SNAPSHOT\n")
            (workspace / "CHANGELOG.md").write_text(
                "# Changelog\n\n"
                "## [Unreleased]\n\n"
                "[Unreleased]: https://github.com/lanwen/frmtr/compare/v0.1.0...main\n"
            )
            (workspace / "site/src/jbake/jbake.properties").write_text(
                "site.host=https://lanwen.github.io/frmtr\n"
                "frmtr.version=0.0.0-dev\n"
            )
            (workspace / "README.md").write_text(
                "# frmtr\n\n"
                "```kotlin\n"
                "plugins {\n"
                "    id(\"dev.lanwen.frmtr\") version \"0.1.0\"\n"
                "}\n"
                "```\n"
                "\n"
                "Snapshot builds are published from `main`. Current snapshot: `0.1.1-SNAPSHOT`.\n"
            )

            try:
                os.chdir(workspace)
                args = mock.Mock(body_file="build/release-pr-body.md")
                with (
                    mock.patch.object(
                        release_automation,
                        "merged_prs_since_latest_tag",
                        return_value=[pr_entry(1, "fix(core): preserve comments")],
                    ),
                    mock.patch.object(
                        release_automation,
                        "latest_release_tag",
                        return_value=("v0.1.0", release_automation.Version(0, 1, 0)),
                    ),
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    self.assertEqual(0, release_automation.prepare_release(args))
            finally:
                os.chdir(cwd)

            self.assertEqual("version=0.1.1\n", (workspace / "gradle.properties").read_text())
            self.assertIn("frmtr.version=0.1.1\n", (workspace / "site/src/jbake/jbake.properties").read_text())
            self.assertIn(
                'id("dev.lanwen.frmtr") version "0.1.1"',
                (workspace / "README.md").read_text(),
            )
            self.assertIn("Current snapshot: `0.1.1-SNAPSHOT`", (workspace / "README.md").read_text())


class PrepareSnapshotTargetTest(unittest.TestCase):
    def test_prepare_snapshot_target_updates_documented_snapshot_versions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cwd = Path.cwd()
            workspace = Path(directory)
            (workspace / "gradle.properties").write_text("version=0.1.1-SNAPSHOT\n")
            (workspace / "README.md").write_text(
                "# frmtr\n\n"
                "Snapshot builds are published from `main`. Current snapshot: `0.1.1-SNAPSHOT`.\n"
            )
            (workspace / "PUBLISHING.md").write_text(
                "# Publishing\n\n"
                "```kotlin\n"
                "plugins {\n"
                "    id(\"dev.lanwen.frmtr\") version \"0.1.1-SNAPSHOT\"\n"
                "}\n"
                "```\n"
            )

            try:
                os.chdir(workspace)
                args = mock.Mock(body_file="build/snapshot-pr-body.md", snapshot_version="")
                with (
                    mock.patch.object(
                        release_automation,
                        "merged_prs_since_latest_tag",
                        return_value=[pr_entry(1, "feat(core): add text block formatting")],
                    ),
                    mock.patch.object(
                        release_automation,
                        "latest_release_tag",
                        return_value=("v0.1.0", release_automation.Version(0, 1, 0)),
                    ),
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    self.assertEqual(0, release_automation.prepare_snapshot_target(args))
            finally:
                os.chdir(cwd)

            self.assertEqual("version=0.2.0-SNAPSHOT\n", (workspace / "gradle.properties").read_text())
            self.assertIn("Current snapshot: `0.2.0-SNAPSHOT`", (workspace / "README.md").read_text())
            self.assertIn(
                'id("dev.lanwen.frmtr") version "0.2.0-SNAPSHOT"',
                (workspace / "PUBLISHING.md").read_text(),
            )


if __name__ == "__main__":
    unittest.main()
