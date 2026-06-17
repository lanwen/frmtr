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
    id("dev.lanwen.frmtr") version "0.1.0-SNAPSHOT"
}
```

After publishing a new snapshot with the same version, refresh Gradle's cached snapshot metadata:

```bash
./gradlew --refresh-dependencies frmtrCheck
```

### Release

JReleaser deploys only `frmtr-core` and `frmtr-tooling` to Maven Central. The Gradle plugin release goes to the Gradle
Plugin Portal after those artifacts are available from Central.

Run release commands from the main checkout on `main`; JReleaser expects normal Git metadata and a GitHub `origin`.

Set a non-`-SNAPSHOT` root version in `gradle.properties`, then dry-run locally:

```bash
./gradlew clean stageCentralRelease
op run --env-file ./publishing/.env.release -- ./gradlew jreleaserConfig --dryrun
op run --env-file ./publishing/.env.release -- ./gradlew jreleaserDeploy --dryrun
op run --env-file ./publishing/.env.gradle -- ./gradlew :frmtr-gradle-plugin:publishPlugins --validate-only
```

JReleaser also needs `JRELEASER_GPG_SECRET_KEY` and `JRELEASER_GPG_PASSPHRASE` in `publishing/.env.release`.

Publish the Central release:

```bash
op run --env-file ./publishing/.env.release -- ./gradlew jreleaserDeploy
```

Publish the Gradle plugin release:

```bash
op run --env-file ./publishing/.env.gradle -- ./gradlew :frmtr-gradle-plugin:publishPlugins
```
