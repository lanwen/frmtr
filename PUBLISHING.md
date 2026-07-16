# Publishing

## Local

Local publishing uses 1Password CLI to resolve maintainer credentials from env files in `publishing/`.

Dry-run locally first:

```bash
./gradlew clean publishToMavenLocal
./gradlew :frmtr-gradle-plugin:validatePlugins
```

### Snapshots

GitHub Actions publishes snapshots from `.github/workflows/snapshots.yml` on every push to `main` while the root version
ends in `-SNAPSHOT`. Configure the `snapshots` environment secrets `JRELEASER_MAVENCENTRAL_USERNAME` and
`JRELEASER_MAVENCENTRAL_PASSWORD`; the workflow runs `./gradlew check publishAllPublicationsToCentralPortalSnapshotsRepository`
before uploading artifacts to Central Portal snapshots.

Preview what would run:

```bash
op run --env-file ./publishing/.env.snapshot -- ./gradlew publishAllPublicationsToCentralPortalSnapshotsRepository --dry-run
```

Publish the current `-SNAPSHOT` artifacts:

```bash
op run --env-file ./publishing/.env.snapshot -- ./gradlew publishAllPublicationsToCentralPortalSnapshotsRepository
```

The snapshot version itself is maintained by `.github/workflows/snapshot-target-pr.yml`: it opens or updates a
`snapshot` PR when merged feature or breaking-change PRs require a higher target, and it can be dispatched manually with
an explicit `*-SNAPSHOT` version. See [Consuming Snapshots](#consuming-snapshots) for the Gradle plugin repository setup.

#### Consuming Snapshots

Add the Central snapshot repository to plugin resolution:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent {
                snapshotsOnly()
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Apply the snapshot plugin:

```kotlin
// build.gradle.kts
plugins {
    id("dev.lanwen.frmtr") version "0.3.0-SNAPSHOT"
}
```

After publishing a new snapshot with the same version, refresh Gradle's cached snapshot metadata:

```bash
./gradlew --refresh-dependencies frmtrCheck
```

### Release

JReleaser deploys `frmtr-core` and `frmtr-tooling` to Maven Central and publishes native CLI archives to a GitHub
release. The release workflow also publishes the Gradle plugin and delegates Homebrew formula publication to the
`Publish Homebrew` workflow in separate GitHub environments.

Release commits are normal protected-branch PRs. Automation never pushes commits directly to `main`; it creates or
updates signed PR branches with `peter-evans/create-pull-request` and a GitHub App token so PR workflows run normally.

1. Every push to `main` refreshes the `release` PR. The release PR updates `CHANGELOG.md`, `README.md`, changes
   `gradle.properties` from `*-SNAPSHOT` to the computed final version, updates the JBake site version in
   `site/src/jbake/jbake.properties`, and carries the corpus check before merge.
2. Feature or breaking-change merges to `main` can also open a `snapshot` PR that raises the current snapshot target
   and updates documented snapshot consumption versions; the same workflow can be dispatched manually with an explicit
   `*-SNAPSHOT` target.
3. Merging the release PR pushes a final version in `gradle.properties` to `main`. `.github/workflows/release.yml`
   detects that change and publishes the release.
4. After release, the workflow opens a `snapshot` PR that restores the next `*-SNAPSHOT` version.

The release workflow validates that `gradle.properties` is non-`-SNAPSHOT`, derives the tag as `v<version>`, builds
native distributions on Linux x64, macOS arm64, and Windows x64, creates the tag if it does not already exist, and
publishes GitHub release assets plus Maven Central. If the tag already exists, it must point at the same `main` commit.

Version bump and changelog rules are documented in [docs/release-automation.md](docs/release-automation.md). In short:
breaking changes bump minor while frmtr is pre-1.0 and major after that, `feat`/`feature` bumps minor, and everything
else bumps patch. Dependency bumps are skipped from release notes unless they mention JavaParser.

Configure the GitHub App client ID as a repository variable available to the automation workflows:

- `RELEASE_APP_CLIENT_ID`

Configure the GitHub App private key as a repository secret:

- `RELEASE_APP_PRIVATE_KEY`

Configure the `release` environment secrets for GitHub release and Maven Central publication:

- `JRELEASER_GPG_SECRET_KEY`
- `JRELEASER_GPG_PASSPHRASE`
- `JRELEASER_MAVENCENTRAL_USERNAME`
- `JRELEASER_MAVENCENTRAL_PASSWORD`

Configure the `gradle` environment secrets for Gradle Plugin Portal publication:

- `GRADLE_PUBLISH_KEY`
- `GRADLE_PUBLISH_SECRET`

The `brew` environment uses the GitHub App to mint a `JRELEASER_HOMEBREW_GITHUB_TOKEN` for `lanwen/homebrew-tap`; install
the app on that repository with contents read/write access before enabling the workflow. Homebrew publishing runs through
the reusable `Publish Homebrew` workflow and uses `jreleaser-brew.yml`, which omits Maven Central deploy and PGP signing
config so the brew job does not need Maven or GPG secrets.

Run release commands from the main checkout on `main`; JReleaser expects normal Git metadata and a GitHub `origin`.

Set a non-`-SNAPSHOT` root version in `gradle.properties`, then dry-run locally:

```bash
./gradlew clean stageCentralRelease
./gradlew :frmtr-cli:nativeDistributionZip
op run --env-file ./publishing/.env.release -- ./gradlew jreleaserConfig --dryrun --select-current-platform
op run --env-file ./publishing/.env.release -- ./gradlew jreleaserFullRelease --dryrun --select-current-platform
op run --env-file ./publishing/.env.gradle -- ./gradlew :frmtr-gradle-plugin:publishPlugins --validate-only
```

On a local machine, pass `--select-current-platform` to `jreleaserConfig` and `jreleaserFullRelease` if only the host
native archive exists. A full release dry-run needs all Linux, macOS, and Windows archives under `build/distributions`.
JReleaser also needs `JRELEASER_GPG_SECRET_KEY` and `JRELEASER_GPG_PASSPHRASE` in `publishing/.env.release`; local
GitHub release fallbacks also need `JRELEASER_GITHUB_TOKEN`, while GitHub Actions uses its repository `GITHUB_TOKEN`.

Publish the release by merging the generated `release` PR. The local fallback is:

```bash
op run --env-file ./publishing/.env.release -- ./gradlew stageCentralRelease jreleaserFullRelease
```

Publish the Gradle plugin release after Central artifacts are available:

```bash
op run --env-file ./publishing/.env.gradle -- ./gradlew :frmtr-gradle-plugin:publishPlugins
```

Retry only the Homebrew formula for an already-published GitHub release with the `Publish Homebrew` workflow dispatch,
passing the released version. The release workflow calls the same workflow after GitHub release publication succeeds. The
local equivalent expects the native release archives under `build/distributions`:

```bash
./gradlew -Pversion=0.1.0 -Pfrmtr.jreleaser.configFile=jreleaser-brew.yml jreleaserPublish --packager brew --distribution frmtr
```
