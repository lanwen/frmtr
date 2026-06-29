plugins {
    base
}

val jbake by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    jbake(libs.jbake.core)
}

val jbakeSourceDir = layout.projectDirectory.dir("src/jbake")
val logbackConfigDir = layout.projectDirectory.dir("src/logback")
val jbakeOutputDir = layout.buildDirectory.dir("jbake")

val bake by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Builds the JBake static site."
    classpath(files(logbackConfigDir), jbake)
    mainClass = "org.jbake.launcher.Main"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args("-b", jbakeSourceDir.asFile.absolutePath, jbakeOutputDir.get().asFile.absolutePath)

    inputs.dir(jbakeSourceDir)
    inputs.dir(logbackConfigDir)
    outputs.dir(jbakeOutputDir)

    doFirst {
        delete(jbakeOutputDir.get().asFile)
    }
}

val serve by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Bakes the site, then serves it with live re-bake on source changes (Ctrl+C to stop)."
    classpath(files(logbackConfigDir), jbake)
    mainClass = "org.jbake.launcher.Main"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // --bake then --server; together they enable JBake's watch mode (re-bakes on change).
    args("--bake", "--server", jbakeSourceDir.asFile.absolutePath, jbakeOutputDir.get().asFile.absolutePath)

    // Long-running server: no outputs/up-to-date tracking, and it must not block other work.
    notCompatibleWithConfigurationCache("Runs a blocking dev server")
}

tasks.named("assemble") {
    dependsOn(bake)
}
