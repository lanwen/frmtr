import java.time.Instant
import java.util.Locale
import org.gradle.api.tasks.bundling.Zip

plugins {
    application
    jacoco
    alias(libs.plugins.graalvm.native)
}

application {
    mainClass = "dev.lanwen.frmtr.cli.Main"
}

val runtimeJavaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}
val nativeImageJavaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    nativeImageCapable.set(true)
}
val nativeDistributionClassifier = providers.gradleProperty("frmtr.native.classifier").orElse(providers.provider {
    defaultNativeClassifier()
})
val nativeExecutableName = providers.provider {
    if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) {
        "frmtr.exe"
    } else {
        "frmtr"
    }
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
                    "-Adisable.proxy.config",
                    "-Xlint:-processing"))
}

tasks.named<JavaExec>("run") {
    javaLauncher = runtimeJavaLauncher
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        configureEach {
            javaLauncher.set(nativeImageJavaLauncher)
        }

        named("main") {
            imageName = "frmtr"
            mainClass = "dev.lanwen.frmtr.cli.Main"
        }
    }
}

tasks.register<Zip>("nativeDistributionZip") {
    group = "distribution"
    description = "Packages the native executable as a JReleaser binary distribution."
    dependsOn("nativeCompile")

    archiveBaseName.set("frmtr")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set(nativeDistributionClassifier)
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("distributions"))

    into("frmtr-${project.version}") {
        from(rootProject.layout.projectDirectory.file("LICENSE"))
        from(rootProject.layout.projectDirectory.file("README.md")) {
            rename { "README" }
        }
        into("bin") {
            from(providers.provider {
                layout.buildDirectory
                    .file("native/nativeCompile/${nativeExecutableName.get()}")
                    .get()
                    .asFile
            }) {
                filePermissions {
                    unix("755")
                }
            }
        }
    }
}

fun javaString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

fun defaultNativeClassifier(): String {
    val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
    val os =
            when {
                osName.contains("mac") -> "osx"
                osName.contains("windows") -> "windows"
                osName.contains("linux") -> "linux"
                else -> osName.replace(Regex("[^a-z0-9]+"), "_").trim('_')
            }
    val archName = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val arch =
            when (archName) {
                "aarch64", "arm64" -> "aarch_64"
                "amd64", "x86_64" -> "x86_64"
                else -> archName.replace(Regex("[^a-z0-9]+"), "_").trim('_')
            }
    return "$os-$arch"
}
