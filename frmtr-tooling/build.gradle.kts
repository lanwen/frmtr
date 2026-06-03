plugins {
    `java-library`
    jacoco
}

dependencies {
    api(project(":frmtr-core"))
    implementation(libs.jgit)
}
