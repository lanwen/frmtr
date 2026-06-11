plugins {
    base
}

group = "dev.lanwen.frmtr"
version = "0.1.0-SNAPSHOT"

val frmtrCli = project(":frmtr-cli")
val frmtrCliRuntimeClasspath = frmtrCli.provider {
    frmtrCli.extensions
        .getByType<SourceSetContainer>()
        .named("main")
        .get()
        .runtimeClasspath
}

val frmtrSelfFixtureCorpus = "frmtr-core/src/test/resources/format"

fun TaskContainer.registerFrmtrCliTask(name: String, configure: JavaExec.() -> Unit) = register<JavaExec>(name) {
    group = "formatting"
    mainClass.set("dev.lanwen.frmtr.cli.Main")
    workingDir = rootProject.projectDir
    classpath(frmtrCliRuntimeClasspath)
    configure()
}

tasks.registerFrmtrCliTask("frmtrSelfCheck") {
    description = "Verifies formatting with frmtr CLI."
    args("--check", "--diff", "--exclude", frmtrSelfFixtureCorpus, ".")
}

tasks.registerFrmtrCliTask("frmtrSelfFormat") {
    description = "Formats code with frmtr CLI."
    args("--write", "--exclude", frmtrSelfFixtureCorpus, ".")
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("java") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
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
