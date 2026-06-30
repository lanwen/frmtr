# Release Automation

frmtr releases are driven from pull requests and the final `gradle.properties` version on `main`.

## Pull Request Title Schema

PR titles use a Conventional Commits-style schema:

```text
<type>(optional-scope)!: subject
```

Allowed types:

- `feat`: user-visible feature; raises the release target at least to the next minor version.
- `feature`: temporary alias for `feat`; prefer `feat` in new PRs because external Conventional Commits tooling expects
  it.
- `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `style`, `revert`: included in the changelog and raise the release target at least to the next patch version.
- `deps`: dependency bump. Dependency PRs are omitted from generated release notes unless they mention JavaParser.

Append `!` after the type or scope for a breaking change, or include `BREAKING CHANGE:` in the PR body. While frmtr is
pre-1.0, breaking changes raise the release target to the next minor version; from 1.0 onward they raise it to the next
major version.

Examples:

```text
fix(cli): preserve quoted glob selectors
feat(core): format compact record constructors
refactor(java)!: remove legacy comment ownership path
deps: bump JavaParser to 3.28.3
chore(release): prepare 0.2.0
chore(snapshot): start 0.3.0-SNAPSHOT
```

## Changelog Detail Markers

The generated changelog always includes the PR title. Add optional long-form release-note text between these markers in
the PR body:

```markdown
<!-- frmtr-changelog-details:start -->
Explain the user-visible behavior or migration note here.
<!-- frmtr-changelog-details:end -->
```

Only the text between the markers is copied into `CHANGELOG.md`; omit the markers when the title is enough.

Write the details for an end user reading the changelog, not for a reviewer reading the diff:

- Be concise and concrete about **how the change affects the formatted output**. One or two sentences is usually enough.
- Do **not** include implementation details, the root cause, or why the old behavior was wrong.
- Prefer a **minimal before/after snippet** of the visible effect over prose, and put that snippet in a **fenced code
  block** so the line breaks render — plain lines collapse into one paragraph in Markdown.

For example, a one-line summary followed by a fenced before/after:

A line comment at the start of a braceless `else` body now stays indented inside the body instead of moving above `else`.

```java
// before
}
// note
else doThing();

// after
} else
    // note
    doThing();
```

Avoid a paragraph naming which printer changed or why the old behavior was wrong.

The PR-title workflow rejects duplicate, unclosed, out-of-order marker pairs and Markdown headings inside the details
(so do not start a line with `#`).

## Release Version Selection

The release PR and snapshot target generators inspect merged PRs since the latest `v*` release tag and skip generated
`release`/`snapshot` PRs. They also skip dependency bumps except JavaParser.

Version selection:

1. Breaking change: next minor while the release line is `0.x`, otherwise next major.
2. `feat` or `feature`: next minor.
3. Everything else: next patch.

The computed version is compared with the current `*-SNAPSHOT` version in `gradle.properties`; the release PR uses
whichever is higher, then removes `-SNAPSHOT`.

On every `main` push, `snapshot-target-pr.yml` also checks whether merged feature or breaking-change PRs require a
higher snapshot target. If `gradle.properties` is still on a lower `*-SNAPSHOT`, it opens or updates a signed `snapshot`
PR that bumps the snapshot version and documented snapshot consumption versions. Manual dispatch may also pass an
explicit `snapshot_version` such as `0.3.0-SNAPSHOT` to set the target directly. Automatic snapshot target bumps are
calculated from the latest release tag, so multiple feature PRs before the next release still require only one minor
snapshot bump.

## Workflow Overview

- `pr-title.yml`: enforces the PR title schema.
- `release-pr.yml`: on every `main` push, regenerates `automation/release`, updates `CHANGELOG.md` and
  `README.md`, updates `gradle.properties`, updates the JBake site version in `site/src/jbake/jbake.properties`, and
  opens or updates a `release` PR.
- `snapshot-target-pr.yml`: on every `main` push, raises the snapshot target when merged feature or breaking-change PRs
  require a higher release line and updates snapshot consumption snippets; manual dispatch can set a specific snapshot
  target. `release.yml` also calls it as a reusable workflow to open the post-release next-snapshot PR.
- `snapshot-version-guard.yml`: fails PRs whose merged result has a non-`-SNAPSHOT` version unless the PR is labeled
  `release`.
- `corpus.yml`: runs on generated `release` PRs and by manual dispatch, so the release PR carries the corpus check
  before merge without rerunning after publication.
- `release.yml`: runs when `gradle.properties` changes on `main`; if the version is final, it builds native archives,
  publishes GitHub/Maven Central, publishes the Gradle plugin, publishes Homebrew, and calls the reusable snapshot PR
  workflow.

Automation-created PRs use `peter-evans/create-pull-request` with `sign-commits: true` and a GitHub App token so
regular PR workflows run without the approval prompt that applies to PRs created with the repository `GITHUB_TOKEN`.
