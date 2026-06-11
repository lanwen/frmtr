package sample;

@Routes(
    {
        @Route(type = AlphaSignal.class, name = RouteNames.ALPHA),
        @Route(type = BetaSignal.class, name = RouteNames.BETA),
        @Route(
            type = GammaSignal.class,
            name = RouteNames.GAMMA
        ),
    }
)
sealed interface RoutedSignal permits AlphaSignal, BetaSignal, GammaSignal {}

final class AlphaSignal implements RoutedSignal {}

final class BetaSignal implements RoutedSignal {}

final class GammaSignal implements RoutedSignal {}

final class RouteNames {

    static final String ALPHA = "alpha-signal";

    static final String BETA = "beta-signal";

    static final String GAMMA = "gamma-signal";
}

@interface Routes {
    Route[] value();
}

@interface Route {
    Class<?> type();

    String name();
}
