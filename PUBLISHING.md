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
