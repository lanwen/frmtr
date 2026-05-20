plugins {
    application
    jacoco
}

application {
    mainClass = "dev.lanwen.frmtr.cli.Main"
}

dependencies {
    implementation(project(":frmtr-core"))
    implementation(libs.picocli)
}
