import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    base
}

group = "dev.lanwen.frmtr"
version = "0.1.0-SNAPSHOT"

val projectUrl = "https://github.com/lanwen/frmtr"
val projectDescriptions =
    mapOf(
        ":frmtr-core" to "Java formatter library and engine for frmtr.",
        ":frmtr-tooling" to "File-oriented formatter runner and diagnostic rendering shared by frmtr adapters.",
        ":frmtr-cli" to "Command-line Java formatter powered by frmtr.",
        ":frmtr-gradle-plugin" to "Gradle plugin that checks and formats Java source with frmtr.",
        ":frmtr-native-image-support" to "GraalVM native-image support metadata for frmtr.",
        ":site" to "Static onboarding site for frmtr.")

val frmtrCli = project(":frmtr-cli")
val frmtrCliRuntimeClasspath = frmtrCli.provider {
    frmtrCli.extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()
        .runtimeClasspath
}

val frmtrSelfFixtureCorpora =
    listOf("frmtr-core/src/test/resources/format", "frmtr-core/src/test/resources/unsupported")

fun TaskContainer.registerFrmtrCliTask(name: String, configure: JavaExec.() -> Unit) = register<JavaExec>(name) {
    group = "formatting"
    mainClass.set("dev.lanwen.frmtr.cli.Main")
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
    description = "Formats code with frmtr CLI."
    args("--write")
    frmtrSelfFixtureCorpora.forEach { args("--exclude", it) }
    args(".")
}

tasks.register("siteBuild") {
    group = "documentation"
    description = "Builds the static onboarding site with JBake."
    dependsOn(":site:bake")
}

subprojects {
    group = rootProject.group
    version = rootProject.version
    description = projectDescriptions[path]

    pluginManager.withPlugin("java") {
        pluginManager.apply("maven-publish")

        configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
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
            options.compilerArgs.add("-Xlint:all")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        configure<PublishingExtension> {
            publications {
                if (project.path != ":frmtr-gradle-plugin") {
                    register<MavenPublication>("mavenJava") {
                        from(components["java"])
                    }
                }

                withType<MavenPublication>().configureEach {
                    pom {
                        name.set("frmtr ${project.name.removePrefix("frmtr-")}")
                        description.set(project.description)
                        url.set(projectUrl)
                        inceptionYear.set("2026")

                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/license/mit/")
                                distribution.set("repo")
                            }
                        }

                        developers {
                            developer {
                                id.set("lanwen")
                                name.set("lanwen")
                                url.set("https://github.com/lanwen")
                            }
                        }

                        scm {
                            connection.set("scm:git:https://github.com/lanwen/frmtr.git")
                            developerConnection.set("scm:git:ssh://git@github.com/lanwen/frmtr.git")
                            url.set(projectUrl)
                        }

                        issueManagement {
                            system.set("GitHub Issues")
                            url.set("$projectUrl/issues")
                        }
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
