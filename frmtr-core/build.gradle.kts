plugins {
    `java-library`
    jacoco
}

dependencies {
    api(libs.javaparser.core)
}

tasks.withType<Test>().configureEach {
    // Roadmap B3, layer 1: enable AST-equivalence verification for every formatter test (notably the golden fixture
    // suite), so each fixture is also re-parsed and checked for semantic equivalence to its input. This catches a
    // meaning-changing printer bug in any covered construct without anyone having to hand-write a fixture for it.
    // Off by default outside tests; tests that exercise the toggle directly save/restore the property themselves.
    systemProperty("dev.lanwen.frmtr.debug.verify", "true")
}
