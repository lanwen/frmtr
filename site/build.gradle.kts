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
    mainClass.set("org.jbake.launcher.Main")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args("-b", jbakeSourceDir.asFile.absolutePath, jbakeOutputDir.get().asFile.absolutePath)

    inputs.dir(jbakeSourceDir)
    inputs.dir(logbackConfigDir)
    outputs.dir(jbakeOutputDir)

    doFirst {
        delete(jbakeOutputDir.get().asFile)
    }
}

tasks.named("assemble") {
    dependsOn(bake)
}
