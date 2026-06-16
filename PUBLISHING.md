# Publishing

## Local

Local publishing uses 1Password CLI to resolve maintainer credentials from `publish.env`.

Dry-run locally first:

```bash
./gradlew clean publishToMavenLocal
./gradlew :frmtr-gradle-plugin:validatePlugins
```

Preview what would run:

```bash
op run --env-file ./publish.env -- ./gradlew publish --dry-run
```

Publish the current `-SNAPSHOT` artifacts:

```bash
op run --env-file ./publish.env -- ./gradlew publish
```

## Consuming Snapshots

Add the Central snapshot repository to plugin resolution:

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
