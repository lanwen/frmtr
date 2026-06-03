plugins {
    `java-library`
    jacoco
}

dependencies {
    api(libs.javaparser.core)
    implementation(libs.jgit)
}
