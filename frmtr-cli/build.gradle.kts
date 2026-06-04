import java.time.Instant

plugins {
    application
    jacoco
    alias(libs.plugins.graalvm.native)
}

application {
    mainClass = "dev.lanwen.frmtr.cli.Main"
}

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/build-info/java")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    inputs.property("projectVersion", project.version.toString())

    doLast {
        val commitSha = providers.exec {
                    commandLine("git", "rev-parse", "--short=12", "HEAD")
                }
                .standardOutput
                .asText
                .get()
                .trim()
        val buildTimestamp = Instant.now().toString()
        val buildInfo = outputDir
                .get()
                .file("dev/lanwen/frmtr/cli/BuildInfo.java")
                .asFile
        buildInfo.parentFile.mkdirs()
        buildInfo.writeText(
                """
                package dev.lanwen.frmtr.cli;

                final class BuildInfo {
                    static final String VERSION = "${javaString(project.version.toString())}";
                    static final String COMMIT_SHA = "${javaString(commitSha)}";
                    static final String BUILD_TIMESTAMP = "${javaString(buildTimestamp)}";

                    private BuildInfo() {}
                }
                """.trimIndent())
    }
}

sourceSets {
    main {
        java.srcDir(generateBuildInfo)
    }
}

dependencies {
    implementation(project(":frmtr-core"))
    implementation(project(":frmtr-tooling"))
    implementation(libs.jgit)
    implementation(libs.picocli)
    runtimeOnly(libs.slf4j.nop)
    annotationProcessor(libs.picocli.codegen)
    nativeImageCompileOnly(project(":frmtr-native-image-support"))
    nativeImageTestCompileOnly(project(":frmtr-native-image-support"))
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(
            listOf(
                    "-processor",
                    "picocli.codegen.aot.graalvm.processor.NativeImageConfigGeneratorProcessor",
                    "-Aproject=dev.lanwen.frmtr/frmtr-cli",
                    "-Xlint:-processing"))
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("frmtr")
            mainClass.set("dev.lanwen.frmtr.cli.Main")
        }
    }
}

fun javaString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
