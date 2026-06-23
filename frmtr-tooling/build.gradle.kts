plugins {
    `java-library`
    jacoco
}

dependencies {
    api(project(":frmtr-core"))
    implementation(libs.jgit)
}

// --- B3 Layer-3 corpus correctness harness (opt-in) -------------------------------------------------------------
//
// The corpus harness lives in a dedicated `corpus` source set so it is fully isolated from `test`. It is run only by
// the `corpusCheck` task below, which is NOT wired into `check`/`build`/`test`. The harness formats a pinned
// real-world OSS codebase (testcontainers-java) and asserts parse-stability, one-pass idempotence, and
// AST-equivalence per file. It is gated opt-in via the `frmtr.corpus.enabled` system property so that even running
// the `corpus` test classes directly reports SKIPPED unless `-Pcorpus=true` is supplied.
val corpus: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named("corpusImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named("corpusRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val corpusEnabled = providers.gradleProperty("corpus").map { it.toBoolean() }.orElse(false)

tasks.register<Test>("corpusCheck") {
    group = "verification"
    description =
        "Opt-in Layer-3 corpus correctness harness: formats pinned testcontainers-java and asserts parse-stability, " +
            "idempotence, and AST-equivalence per file. Enable with -Pcorpus=true. Not wired into check/build/test."

    testClassesDirs = corpus.output.classesDirs
    classpath = corpus.runtimeClasspath
    useJUnitPlatform()

    // Gate the harness opt-in: without -Pcorpus=true the JUnit entry's assume-check reports SKIPPED and never fetches.
    systemProperty("frmtr.corpus.enabled", corpusEnabled.get().toString())
    systemProperty("frmtr.corpus.workDir", layout.buildDirectory.dir("corpus").get().asFile.absolutePath)

    // Always re-run; corpus outcomes depend on the fetched corpus and formatter behavior, not just task inputs.
    outputs.upToDateWhen { false }

    // The root build finalizes every Test task with jacocoTestReport (which dependsOn `test`). Left in place that
    // would drag the normal `test` task into a `corpusCheck` run, putting the harness back near the hot path. The
    // corpus harness is a correctness gate, not a coverage gate, so detach it from coverage entirely.
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
    setFinalizedBy(emptyList<Any>())

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
