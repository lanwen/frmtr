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

    // Roadmap B2 (comment-ownership consolidation, Stage 4): enforce the "each comment is claimed at most once"
    // invariant for every test. The candidate-ladder probes that used to double-claim comments are now claim-free —
    // discarded probes roll back their claims through CommentTracker.speculatively, and the few reused-Doc neighbor
    // offers are gated by ownership / skipped when already printed — so this invariant now holds across the whole
    // suite and is a CI gate. Tests that toggle this property directly save/restore it themselves.
    systemProperty("dev.lanwen.frmtr.debug.guardrails.strict-claims", "true")
}
