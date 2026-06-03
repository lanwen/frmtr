plugins {
    `java-gradle-plugin`
    jacoco
}

dependencies {
    implementation(project(":frmtr-core"))
    implementation(project(":frmtr-tooling"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("frmtr") {
            id = "dev.lanwen.frmtr"
            implementationClass = "dev.lanwen.frmtr.gradle.FrmtrGradlePlugin"
            displayName = "frmtr Gradle plugin"
            description = "Formats Java source with frmtr."
        }
    }
}
