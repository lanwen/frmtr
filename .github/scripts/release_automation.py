#!/usr/bin/env python3
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import os
import re
import subprocess
import sys
from pathlib import Path


DETAILS_START = "<!-- frmtr-changelog-details:start -->"
DETAILS_END = "<!-- frmtr-changelog-details:end -->"
ALLOWED_TYPES = {
    "build",
    "chore",
    "ci",
    "deps",
    "docs",
    "feat",
    "feature",
    "fix",
    "perf",
    "refactor",
    "revert",
    "style",
    "test",
}
TITLE_PATTERN = re.compile(
    r"^(?P<type>[a-z]+)(?:\((?P<scope>[A-Za-z0-9._/-]+)\))?(?P<breaking>!)?: (?P<subject>.+)$")
SEMVER_PATTERN = re.compile(r"^(?P<major>0|[1-9]\d*)\.(?P<minor>0|[1-9]\d*)\.(?P<patch>0|[1-9]\d*)$")
SITE_PROPERTIES = Path("site/src/jbake/jbake.properties")
README = Path("README.md")
PUBLISHING = Path("PUBLISHING.md")
README_RELEASE_PLUGIN_VERSION_PATTERN = re.compile(r'(id\("dev\.lanwen\.frmtr"\) version ")(?![^"]*-SNAPSHOT)([^"]+)(")')
README_SNAPSHOT_VERSION_PATTERN = re.compile(r"(Current snapshot: `)([^`]+-SNAPSHOT)(`)")
PUBLISHING_SNAPSHOT_PLUGIN_VERSION_PATTERN = re.compile(r'(id\("dev\.lanwen\.frmtr"\) version ")([^"]+-SNAPSHOT)(")')


@dataclasses.dataclass(frozen=True, order=True)
class Version:
    major: int
    minor: int
    patch: int

    @staticmethod
    def parse(value: str) -> "Version":
        match = SEMVER_PATTERN.match(value)
        if not match:
            raise ValueError(f"Expected semantic version MAJOR.MINOR.PATCH, got {value!r}")
        return Version(
            int(match.group("major")),
            int(match.group("minor")),
            int(match.group("patch")),
        )

    def bump(self, level: str) -> "Version":
        if level == "major":
            return Version(self.major + 1, 0, 0)
        if level == "minor":
            return Version(self.major, self.minor + 1, 0)
        if level == "patch":
            return Version(self.major, self.minor, self.patch + 1)
        raise ValueError(f"Unknown bump level {level!r}")

    def snapshot(self) -> str:
        return f"{self}-SNAPSHOT"

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


@dataclasses.dataclass(frozen=True)
class SemanticTitle:
    type: str
    scope: str | None
    breaking: bool
    subject: str


@dataclasses.dataclass(frozen=True)
class PullRequestEntry:
    number: int
    title: str
    body: str
    url: str
    author: str
    labels: tuple[str, ...]
    semantic: SemanticTitle | None


def run(args: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=check, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def read_version() -> str:
    for line in Path("gradle.properties").read_text().splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise ValueError("gradle.properties does not contain a version= line")


def write_version(version: str) -> None:
    lines = Path("gradle.properties").read_text().splitlines()
    updated = [f"version={version}" if line.startswith("version=") else line for line in lines]
    Path("gradle.properties").write_text("\n".join(updated) + "\n")


def write_site_version(version: str) -> None:
    lines = SITE_PROPERTIES.read_text().splitlines()
    found = False
    updated = []
    for line in lines:
        if line.startswith("frmtr.version="):
            found = True
            updated.append(f"frmtr.version={version}")
        else:
            updated.append(line)
    if not found:
        raise ValueError(f"{SITE_PROPERTIES} does not contain a frmtr.version= line")
    SITE_PROPERTIES.write_text("\n".join(updated) + "\n")


def write_readme_plugin_version(version: str) -> None:
    content = README.read_text()
    updated, replacements = README_RELEASE_PLUGIN_VERSION_PATTERN.subn(rf"\g<1>{version}\g<3>", content)
    if replacements == 0:
        raise ValueError(f"{README} does not contain a versioned release dev.lanwen.frmtr plugin declaration")
    README.write_text(updated)


def write_snapshot_docs_version(version: str) -> None:
    readme_content = README.read_text()
    readme_updated, readme_replacements = README_SNAPSHOT_VERSION_PATTERN.subn(rf"\g<1>{version}\g<3>", readme_content)
    if readme_replacements == 0:
        raise ValueError(f"{README} does not contain a current snapshot version")
    README.write_text(readme_updated)

    publishing_content = PUBLISHING.read_text()
    publishing_updated, publishing_replacements = PUBLISHING_SNAPSHOT_PLUGIN_VERSION_PATTERN.subn(
        rf"\g<1>{version}\g<3>",
        publishing_content,
    )
    if publishing_replacements == 0:
        raise ValueError(f"{PUBLISHING} does not contain a snapshot dev.lanwen.frmtr plugin declaration")
    PUBLISHING.write_text(publishing_updated)


def required_value(args: argparse.Namespace, name: str, env_name: str) -> str:
    value = getattr(args, name, "") or os.environ.get(env_name, "")
    if not value:
        raise ValueError(f"Expected --{name.replace('_', '-')} or {env_name}.")
    return value


def parse_semantic_title(title: str) -> SemanticTitle | None:
    match = TITLE_PATTERN.match(title.strip())
    if not match:
        return None
    semantic_type = match.group("type")
    if semantic_type not in ALLOWED_TYPES:
        return None
    return SemanticTitle(
        semantic_type,
        match.group("scope"),
        bool(match.group("breaking")),
        match.group("subject").strip(),
    )


def check_title(args: argparse.Namespace) -> int:
    title = args.title.strip()
    author = args.author.strip()
    return validate_pr_schema(title, author, args.body)


def check_pr(args: argparse.Namespace) -> int:
    event = json.loads(Path(args.event_file).read_text())
    pr = event["pull_request"]
    title = pr["title"]
    author = pr["user"]["login"]
    body = pr.get("body") or ""
    return validate_pr_schema(title, author, body)


def validate_pr_schema(title: str, author: str, body: str) -> int:
    semantic = parse_semantic_title(title)
    if semantic and semantic.subject:
        return 0 if valid_details_markers(body) else 1
    if author == "dependabot[bot]" and title.startswith("Bump "):
        return 0
    print(
        "::error::PR title must use '<type>(optional-scope)!: subject'. "
        f"Allowed types: {', '.join(sorted(ALLOWED_TYPES))}.",
        file=sys.stderr,
    )
    print(f"::error::Invalid title: {title}", file=sys.stderr)
    return 1


def valid_details_markers(body: str) -> bool:
    starts = body.count(DETAILS_START)
    ends = body.count(DETAILS_END)
    if starts == 0 and ends == 0:
        return True
    if starts != 1 or ends != 1 or body.index(DETAILS_START) > body.index(DETAILS_END):
        print("::error::Use exactly one ordered changelog details marker pair.", file=sys.stderr)
        return False
    details = extract_details(body)
    for line in details.splitlines():
        if line.startswith("#"):
            print("::error::Changelog details must not contain Markdown headings.", file=sys.stderr)
            return False
    return True


def latest_release_tag() -> tuple[str | None, Version | None]:
    result = run(["git", "tag", "--list", "v[0-9]*"], check=False)
    tags: list[tuple[Version, str]] = []
    for raw_tag in result.stdout.splitlines():
        tag = raw_tag.strip()
        try:
            tags.append((Version.parse(tag.removeprefix("v")), tag))
        except ValueError:
            continue
    if not tags:
        return None, None
    version, tag = max(tags)
    return tag, version


def commit_set_since(tag: str | None) -> set[str]:
    range_arg = "HEAD" if tag is None else f"{tag}..HEAD"
    result = run(["git", "rev-list", range_arg])
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def merged_prs_since_latest_tag() -> list[PullRequestEntry]:
    tag, _ = latest_release_tag()
    commits = commit_set_since(tag)
    result = run([
        "gh",
        "pr",
        "list",
        "--state",
        "merged",
        "--base",
        "main",
        "--limit",
        "100",
        "--json",
        "number,title,body,url,author,labels,mergeCommit,mergedAt",
    ])
    prs = json.loads(result.stdout)
    entries: list[PullRequestEntry] = []
    for pr in prs:
        merge_commit = (pr.get("mergeCommit") or {}).get("oid")
        if merge_commit not in commits:
            continue
        labels = tuple(label.get("name", "") for label in pr.get("labels", []))
        if "release" in labels or "snapshot" in labels:
            continue
        author = (pr.get("author") or {}).get("login", "")
        title = pr.get("title", "").strip()
        body = pr.get("body") or ""
        entry = PullRequestEntry(
            int(pr["number"]),
            title,
            body,
            pr.get("url", ""),
            author,
            labels,
            parse_semantic_title(title),
        )
        if should_include_entry(entry):
            entries.append(entry)
    return sorted(entries, key=lambda entry: entry.number)


def should_include_entry(entry: PullRequestEntry) -> bool:
    title = entry.title.lower()
    semantic = entry.semantic
    is_dependency = (
        (semantic is not None and (semantic.type == "deps" or semantic.scope == "deps"))
        or title.startswith("bump ")
        or "dependabot" in entry.author.lower()
    )
    if not is_dependency:
        return True
    dependency_context = f"{entry.title}\n{entry.body}".lower()
    return "javaparser" in dependency_context or "com.github.javaparser" in dependency_context


def bump_level(entries: list[PullRequestEntry], base_version: Version) -> str:
    for entry in entries:
        if entry.semantic and entry.semantic.breaking:
            return "minor" if base_version.major == 0 else "major"
        if "BREAKING CHANGE:" in entry.body or "BREAKING-CHANGE:" in entry.body:
            return "minor" if base_version.major == 0 else "major"
    if any(entry.semantic and entry.semantic.type in {"feat", "feature"} for entry in entries):
        return "minor"
    return "patch"


def release_version_from(entries: list[PullRequestEntry]) -> str:
    current = read_version()
    if not current.endswith("-SNAPSHOT"):
        return current
    current_candidate = Version.parse(current.removesuffix("-SNAPSHOT"))
    _, latest = latest_release_tag()
    if latest is None:
        return str(current_candidate)
    required = latest.bump(bump_level(entries, latest))
    return str(max(current_candidate, required))


def parse_snapshot_version(value: str) -> Version:
    if not value.endswith("-SNAPSHOT"):
        raise ValueError(f"Expected snapshot version ending in -SNAPSHOT, got {value!r}")
    return Version.parse(value.removesuffix("-SNAPSHOT"))


def snapshot_target_from(entries: list[PullRequestEntry]) -> str | None:
    current = read_version()
    if not current.endswith("-SNAPSHOT"):
        print(f"Current version {current} is already a release version; snapshot target is unchanged.")
        return None
    _, latest = latest_release_tag()
    if latest is None:
        print("No release tag exists yet; snapshot target is unchanged.")
        return None
    level = bump_level(entries, latest)
    if level == "patch":
        print("No feature or breaking-change PR requires a higher snapshot target.")
        return None
    required = latest.bump(level)
    current_candidate = Version.parse(current.removesuffix("-SNAPSHOT"))
    # The target is calculated from the latest release tag, not from each feature.
    # Once the current snapshot reaches it, more features wait for the same release.
    if current_candidate >= required:
        print(f"Current snapshot {current} already covers required target {required.snapshot()}.")
        return None
    return required.snapshot()


def extract_details(body: str) -> str:
    if DETAILS_START not in body or DETAILS_END not in body:
        return ""
    start = body.index(DETAILS_START) + len(DETAILS_START)
    end = body.index(DETAILS_END, start)
    return body[start:end].strip()


def changelog_entry(entry: PullRequestEntry) -> str:
    semantic = entry.semantic
    prefix = semantic.type if semantic else "change"
    text = f"- `{prefix}` {entry.title} ([#{entry.number}]({entry.url}))"
    details = extract_details(entry.body)
    if details:
        indented = "\n".join(f"  {line}" if line else "" for line in details.splitlines())
        text = f"{text}\n{indented}"
    return text


def requires_higher_snapshot_target(entry: PullRequestEntry) -> bool:
    if entry.semantic and (entry.semantic.breaking or entry.semantic.type in {"feat", "feature"}):
        return True
    return "BREAKING CHANGE:" in entry.body or "BREAKING-CHANGE:" in entry.body


def release_notes(version: str, entries: list[PullRequestEntry]) -> str:
    today = dt.date.today().isoformat()
    lines = [f"## [{version}] - {today}", "", "### Merged Pull Requests", ""]
    lines.extend(changelog_entry(entry) for entry in entries)
    return "\n".join(lines).rstrip() + "\n"


def update_changelog(version: str, entries: list[PullRequestEntry]) -> None:
    path = Path("CHANGELOG.md")
    content = path.read_text()
    marker = "## [Unreleased]"
    marker_index = content.index(marker)
    next_heading = content.find("\n## [", marker_index + len(marker))
    link_index = content.find("\n[Unreleased]:", marker_index)
    section_end = next_heading if next_heading != -1 else link_index
    if section_end == -1:
        raise ValueError("Could not find end of [Unreleased] section")
    existing_unreleased = content[marker_index + len(marker):section_end].strip()
    generated = release_notes(version, entries)
    if existing_unreleased:
        generated = generated.rstrip() + "\n\n### Previous Unreleased Notes\n\n" + existing_unreleased + "\n"
    replacement = f"{marker}\n\n{generated}\n"
    updated = content[:marker_index] + replacement + content[section_end:].lstrip("\n")
    updated = update_changelog_links(updated, version)
    path.write_text(updated)


def update_changelog_links(content: str, version: str) -> str:
    unreleased = f"[Unreleased]: https://github.com/lanwen/frmtr/compare/v{version}...main"
    release = f"[{version}]: https://github.com/lanwen/frmtr/releases/tag/v{version}"
    lines = content.rstrip().splitlines()
    output: list[str] = []
    inserted_release = False
    for line in lines:
        if line.startswith("[Unreleased]:"):
            output.append(unreleased)
            output.append(release)
            inserted_release = True
        elif not line.startswith(f"[{version}]:"):
            output.append(line)
    if not inserted_release:
        output.extend(["", unreleased, release])
    return "\n".join(output) + "\n"


def release_pr_body(version: str, entries: list[PullRequestEntry]) -> str:
    current = read_version()
    current_candidate = Version.parse(current.removesuffix("-SNAPSHOT")) if current.endswith("-SNAPSHOT") else Version.parse(current)
    _, latest = latest_release_tag()
    base_version = latest or current_candidate
    level = bump_level(entries, base_version)
    bullets = "\n".join(f"- {entry.title} ([#{entry.number}]({entry.url}))" for entry in entries)
    return (
        f"## Release {version}\n\n"
        f"Version bump: `{level}`\n\n"
        "This PR is generated from merged PRs since the latest release tag. "
        "Dependency bumps are omitted unless they update JavaParser.\n\n"
        "### Included PRs\n\n"
        f"{bullets if bullets else '- No merged PRs found.'}\n\n"
        "Merge this PR to publish the release from the `gradle.properties` change on `main`.\n"
    )


def prepare_release(args: argparse.Namespace) -> int:
    body_file = required_value(args, "body_file", "RELEASE_PR_BODY_FILE")
    current = read_version()
    if not current.endswith("-SNAPSHOT"):
        print(f"Current version {current} is already a release version; nothing to prepare.")
        return 0
    entries = merged_prs_since_latest_tag()
    if not entries:
        print("No included merged PRs since the latest release tag; nothing to prepare.")
        return 0
    version = release_version_from(entries)
    write_version(version)
    write_site_version(version)
    write_readme_plugin_version(version)
    update_changelog(version, entries)
    Path(body_file).parent.mkdir(parents=True, exist_ok=True)
    Path(body_file).write_text(release_pr_body(version, entries))
    print(version)
    return 0


def snapshot_pr_body(snapshot_version: str, release_version: str) -> str:
    return (
        f"## Next snapshot {snapshot_version}\n\n"
        f"This PR restores snapshot development after release `{release_version}`.\n"
    )


def prepare_snapshot(args: argparse.Namespace) -> int:
    release_version = Version.parse(required_value(args, "release_version", "RELEASE_VERSION"))
    body_file = required_value(args, "body_file", "SNAPSHOT_PR_BODY_FILE")
    snapshot_version = release_version.bump("patch").snapshot()
    write_version(snapshot_version)
    write_snapshot_docs_version(snapshot_version)
    Path(body_file).parent.mkdir(parents=True, exist_ok=True)
    Path(body_file).write_text(snapshot_pr_body(snapshot_version, str(release_version)))
    print(snapshot_version)
    return 0


def snapshot_target_pr_body(snapshot_version: str, entries: list[PullRequestEntry]) -> str:
    triggering_entries = [entry for entry in entries if requires_higher_snapshot_target(entry)]
    bullets = "\n".join(f"- {entry.title} ([#{entry.number}]({entry.url}))" for entry in triggering_entries)
    return (
        f"## Snapshot target {snapshot_version}\n\n"
        "This PR raises the snapshot version because merged feature or breaking-change PRs "
        "now require a higher release target.\n\n"
        "### Triggering PRs\n\n"
        f"{bullets if bullets else '- No merged PRs found.'}\n"
    )


def manual_snapshot_target_pr_body(snapshot_version: str) -> str:
    return (
        f"## Snapshot target {snapshot_version}\n\n"
        "This PR sets the snapshot version requested by manual workflow dispatch.\n"
    )


def prepare_snapshot_target(args: argparse.Namespace) -> int:
    body_file = required_value(args, "body_file", "SNAPSHOT_PR_BODY_FILE")
    requested_snapshot = getattr(args, "snapshot_version", "") or os.environ.get("SNAPSHOT_VERSION", "")
    if requested_snapshot:
        parse_snapshot_version(requested_snapshot)
        write_version(requested_snapshot)
        write_snapshot_docs_version(requested_snapshot)
        Path(body_file).parent.mkdir(parents=True, exist_ok=True)
        Path(body_file).write_text(manual_snapshot_target_pr_body(requested_snapshot))
        print(requested_snapshot)
        return 0

    entries = merged_prs_since_latest_tag()
    snapshot_version = snapshot_target_from(entries)
    if snapshot_version is None:
        return 0
    write_version(snapshot_version)
    write_snapshot_docs_version(snapshot_version)
    Path(body_file).parent.mkdir(parents=True, exist_ok=True)
    Path(body_file).write_text(snapshot_target_pr_body(snapshot_version, entries))
    print(snapshot_version)
    return 0


def ensure_publishable_release_version(args: argparse.Namespace) -> int:
    version = read_version()
    output_path = os.environ.get("GITHUB_OUTPUT")
    outputs: dict[str, str] = {"version": version}
    if version.endswith("-SNAPSHOT"):
        outputs["should_release"] = "false"
        write_outputs(output_path, outputs)
        print(f"Version {version} is a snapshot; release publishing is skipped.")
        return 0

    release_version = Version.parse(version)
    tag = f"v{version}"

    head_sha = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    remote_tags = run(["git", "ls-remote", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"], check=False).stdout
    tag_exists = "false"
    if remote_tags.strip():
        tag_exists = "true"
        lines = [line.split() for line in remote_tags.splitlines() if line.strip()]
        peeled = next((parts[0] for parts in lines if parts[1].endswith("^{}")), None)
        remote_sha = peeled or lines[0][0]
        if remote_sha != head_sha:
            print(f"::error::Tag {tag} already points at {remote_sha}, but main is {head_sha}.", file=sys.stderr)
            return 1

    _, latest = latest_release_tag()
    if latest is not None and release_version < latest:
        print(f"::error::Release version {release_version} is older than latest tag v{latest}.", file=sys.stderr)
        return 1
    if latest is not None and release_version == latest and tag_exists != "true":
        print(f"::error::Release version {release_version} already exists locally as v{latest}.", file=sys.stderr)
        return 1

    outputs.update({
        "release_tag": tag,
        "should_release": "true",
        "tag_exists": tag_exists,
    })
    write_outputs(output_path, outputs)
    return 0


def write_outputs(output_path: str | None, outputs: dict[str, str]) -> None:
    if not output_path:
        for key, value in outputs.items():
            print(f"{key}={value}")
        return
    with Path(output_path).open("a") as output:
        for key, value in outputs.items():
            output.write(f"{key}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    title = subparsers.add_parser("check-title")
    title.add_argument("--title", required=True)
    title.add_argument("--author", required=True)
    title.add_argument("--body", default="")
    title.set_defaults(func=check_title)

    check_pr_parser = subparsers.add_parser("check-pr")
    check_pr_parser.add_argument("--event-file", required=True)
    check_pr_parser.set_defaults(func=check_pr)

    release = subparsers.add_parser("prepare-release")
    release.add_argument("--body-file", default="")
    release.set_defaults(func=prepare_release)

    snapshot = subparsers.add_parser("prepare-snapshot")
    snapshot.add_argument("--release-version", default="")
    snapshot.add_argument("--body-file", default="")
    snapshot.set_defaults(func=prepare_snapshot)

    snapshot_target = subparsers.add_parser("prepare-snapshot-target")
    snapshot_target.add_argument("--body-file", default="")
    snapshot_target.add_argument("--snapshot-version", default="")
    snapshot_target.set_defaults(func=prepare_snapshot_target)

    validate = subparsers.add_parser("ensure-publishable-release-version")
    validate.set_defaults(func=ensure_publishable_release_version)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
