plugins {
    base
}

group = "dev.lanwen.frmtr"
version = "0.1.0-SNAPSHOT"

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
