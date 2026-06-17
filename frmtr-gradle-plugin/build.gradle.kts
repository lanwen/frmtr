import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    jacoco
    alias(libs.plugins.gradle.plugin.publish)
}

dependencies {
    implementation(project(":frmtr-core"))
    implementation(project(":frmtr-tooling"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://github.com/lanwen/frmtr"
    vcsUrl = "https://github.com/lanwen/frmtr.git"

    plugins {
        create("frmtr") {
            id = "dev.lanwen.frmtr"
            implementationClass = "dev.lanwen.frmtr.gradle.FrmtrGradlePlugin"
            displayName = "frmtr Gradle plugin"
            description = "Formats Java source with frmtr."
            tags = listOf("formatter", "formatting", "java")
            compatibility {
                features {
                    configurationCache = false
                }
            }
        }
    }
}
