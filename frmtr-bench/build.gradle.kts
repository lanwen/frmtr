plugins {
    java
}

dependencies {
    implementation(project(":frmtr-core"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator.annprocess)
}

// Runs the JMH harness against the compiled benchmarks. Pass a benchmark-name filter and JMH flags through
// -PjmhArgs, e.g. ./gradlew :frmtr-bench:jmh -PjmhArgs="RawSource -prof gc".
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs JMH microbenchmarks; filter/profile with -PjmhArgs=\"<pattern> <jmh-flags>\"."
    mainClass = "org.openjdk.jmh.Main"
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    val jmhArgs = (project.findProperty("jmhArgs") as String?).orEmpty()
    args(jmhArgs.split(" ").filter { it.isNotBlank() })
}
