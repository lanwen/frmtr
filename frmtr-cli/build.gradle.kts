plugins {
    application
    jacoco
    alias(libs.plugins.graalvm.native)
}

application {
    mainClass = "dev.lanwen.frmtr.cli.Main"
}

dependencies {
    implementation(project(":frmtr-core"))
    implementation(libs.jgit)
    implementation(libs.picocli)
    runtimeOnly(libs.slf4j.nop)
    annotationProcessor(libs.picocli.codegen)
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(
            listOf(
                    "-processor",
                    "picocli.codegen.aot.graalvm.processor.NativeImageConfigGeneratorProcessor",
                    "-Aproject=dev.lanwen.frmtr/frmtr-cli",
                    "-Xlint:-processing"))
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("frmtr")
            mainClass.set("dev.lanwen.frmtr.cli.Main")
        }
    }
}
