plugins {
    `java-library`
    jacoco
}

dependencies {
    implementation(libs.javaparser.core)
    compileOnly(libs.graalvm.nativeimage)
}
