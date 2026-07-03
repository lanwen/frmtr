package sample;

final class LambdaBodyChainDottedFan {

    void fansOverWidthBareRootChainByDots(RouteVerifier verifier) {
        verifier.assertEachRoute(handler -> assertThat(handler).extracting(HandlerConfig::identifier).containsOnly("primaryValue"));
    }

    void keepsFittingBareRootChainFlat(RouteVerifier verifier) {
        verifier.each(route -> assertThat(route).extracting(Route::id).containsOnly("green"));
    }

    void keepsScopedRootChainOutOfTheBareRootFan(RouteVerifier verifier) {
        verifier.assertEachRoute(handler -> journalWriter.atInfo().addValue("handler", handler.identifier()).log("checked handler"));
    }
}
