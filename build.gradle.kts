import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    base
    `java-base`
    alias(libs.plugins.jreleaser)
}

group = "dev.lanwen.frmtr"

val runtimeJavaVersion = JavaLanguageVersion.of(21)
val projectUrl = "https://github.com/lanwen/frmtr"
val projectDescriptions =
    mapOf(
        ":frmtr-core" to "Java formatter library and engine for frmtr.",
        ":frmtr-tooling" to "File-oriented formatter runner and diagnostic rendering shared by frmtr adapters.",
        ":frmtr-cli" to "Command-line Java formatter powered by frmtr.",
        ":frmtr-gradle-plugin" to "Gradle plugin that checks and formats Java source with frmtr.",
        ":frmtr-native-image-support" to "GraalVM native-image support metadata for frmtr.",
        ":frmtr-bench" to "JMH microbenchmarks for frmtr formatter hot paths.",
        ":site" to "Static onboarding site for frmtr.")
val centralMavenJavaProjects = setOf(":frmtr-core", ":frmtr-tooling")
val centralSnapshotProjects = centralMavenJavaProjects + ":frmtr-gradle-plugin"
val isSnapshotVersion = version.toString().endsWith("-SNAPSHOT")
val jreleaserStagingRepositoryName = "jreleaserStaging"
val jreleaserConfigFile = providers.gradleProperty("frmtr.jreleaser.configFile").orElse("jreleaser.yml")

val frmtrCli = project(":frmtr-cli")
val frmtrCliRuntimeClasspath = frmtrCli.provider {
    frmtrCli.extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()
        .runtimeClasspath
}
val runtimeJavaLauncher = javaToolchains.launcherFor {
    languageVersion = runtimeJavaVersion
}

val frmtrSelfFixtureCorpora =
    listOf("frmtr-core/src/test/resources/format", "frmtr-core/src/test/resources/unsupported")

fun TaskContainer.registerFrmtrCliTask(name: String, configure: JavaExec.() -> Unit) = register<JavaExec>(name) {
    group = "formatting"
    mainClass = "dev.lanwen.frmtr.cli.Main"
    javaLauncher = runtimeJavaLauncher
    workingDir = rootProject.projectDir
    classpath(frmtrCliRuntimeClasspath)
    configure()
}

tasks.registerFrmtrCliTask("frmtrSelfCheck") {
    description = "Verifies formatting with frmtr CLI."
    args("--check", "--diff")
    frmtrSelfFixtureCorpora.forEach { args("--exclude", it) }
    args(".")
}

tasks.registerFrmtrCliTask("frmtrSelfFormat") {
    description = "Formats code with frmtr CLI and verifies AST equivalence before each write."
    args("--write", "--verify")
    frmtrSelfFixtureCorpora.forEach { args("--exclude", it) }
    args(".")
}

tasks.register("siteBuild") {
    group = "documentation"
    description = "Builds the static onboarding site with JBake."
    dependsOn(":site:bake")
}

tasks.register("stageCentralRelease") {
    group = "publishing"
    description = "Stages Maven Central release artifacts for JReleaser."
    dependsOn(
        centralMavenJavaProjects.map {
            "$it:publishMavenJavaPublicationToJreleaserStagingRepository"
        })
}

jreleaser {
    configFile = layout.projectDirectory.file(jreleaserConfigFile.get())
    dependsOnAssemble = false
    gitRootSearch = true
}

subprojects {
    group = rootProject.group
    version = rootProject.version
    description = projectDescriptions[path]

    pluginManager.withPlugin("java") {
        val publishesMavenJavaPublication = project.path in centralMavenJavaProjects
        val publishesCentralSnapshots = project.path in centralSnapshotProjects

        pluginManager.apply("maven-publish")

        configure<JavaPluginExtension> {
            toolchain {
                languageVersion = runtimeJavaVersion
            }

            withSourcesJar()
            withJavadocJar()
        }

        tasks.withType<Jar>().configureEach {
            from(rootProject.layout.projectDirectory.file("LICENSE")) {
                into("META-INF")
            }
        }

        dependencies {
            add("testImplementation", libs.assertj.core)
            add("testImplementation", libs.junit.jupiter)
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release = 21
            options.compilerArgs.add("-Xlint:all")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        configure<PublishingExtension> {
            publications {
                if (publishesMavenJavaPublication) {
                    register<MavenPublication>("mavenJava") {
                        from(components["java"])
                    }
                }

                withType<MavenPublication>().configureEach {
                    pom {
                        name = "frmtr ${project.name.removePrefix("frmtr-")}"
                        description = project.description
                        url = projectUrl
                        inceptionYear = "2026"

                        licenses {
                            license {
                                name = "MIT License"
                                url = "https://opensource.org/license/mit/"
                                distribution = "repo"
                            }
                        }

                        developers {
                            developer {
                                id = "lanwen"
                                name = "lanwen"
                                url = "https://github.com/lanwen"
                            }
                        }

                        scm {
                            connection = "scm:git:https://github.com/lanwen/frmtr.git"
                            developerConnection = "scm:git:ssh://git@github.com/lanwen/frmtr.git"
                            url = projectUrl
                        }

                        issueManagement {
                            system = "GitHub Issues"
                            url = "$projectUrl/issues"
                        }
                    }
                }
            }

            if (publishesCentralSnapshots && isSnapshotVersion) {
                repositories {
                    maven {
                        name = "centralPortalSnapshots"
                        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                        credentials {
                            username =
                                providers
                                    .gradleProperty("centralPortalUsername")
                                    .orElse(providers.environmentVariable("JRELEASER_MAVENCENTRAL_USERNAME"))
                                    .orNull
                            password =
                                providers
                                    .gradleProperty("centralPortalPassword")
                                    .orElse(providers.environmentVariable("JRELEASER_MAVENCENTRAL_PASSWORD"))
                                    .orNull
                        }
                    }
                }
            }

            if (publishesMavenJavaPublication) {
                repositories {
                    maven {
                        name = jreleaserStagingRepositoryName
                        url = rootProject.layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("jacoco") {
        tasks.named("jacocoTestReport") {
            dependsOn(tasks.named("test"))
        }

        tasks.withType<Test>().configureEach {
            finalizedBy(tasks.named("jacocoTestReport"))
        }
    }
}
