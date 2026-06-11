package sample;

@Routes({
    @Route(type = AlphaSignal.class, name = RouteNames.ALPHA),
    @Route(type = BetaSignal.class, name = RouteNames.BETA),
    @Route(type = GammaSignal.class, name = RouteNames.GAMMA),
})
sealed interface RoutedSignal permits AlphaSignal, BetaSignal, GammaSignal {}

@Bindings({
    @Bindings.Type(
        value = SlowMovingEnvelopeWithLongName.class,
        name = BindingNames.SLOW_MOVING_ENVELOPE_WITH_LONG_NAME
    ),
    @Bindings.Type(
        value = FallbackEnvelopeWithLongName.class,
        name = BindingNames.FALLBACK_ENVELOPE_WITH_LONG_NAME
    ),
})
sealed interface BoundSignal permits SlowMovingEnvelopeWithLongName, FallbackEnvelopeWithLongName {}

final class AlphaSignal implements RoutedSignal {}

final class BetaSignal implements RoutedSignal {}

final class GammaSignal implements RoutedSignal {}

final class SlowMovingEnvelopeWithLongName implements BoundSignal {}

final class FallbackEnvelopeWithLongName implements BoundSignal {}

final class RouteNames {

    static final String ALPHA = "alpha-signal";

    static final String BETA = "beta-signal";

    static final String GAMMA = "gamma-signal";
}

final class BindingNames {

    static final String SLOW_MOVING_ENVELOPE_WITH_LONG_NAME = "slow-moving-envelope-with-long-name";

    static final String FALLBACK_ENVELOPE_WITH_LONG_NAME = "fallback-envelope-with-long-name";
}

@interface Routes {
    Route[] value();
}

@interface Route {
    Class<?> type();

    String name();
}

@interface Bindings {
    Type[] value();

    @interface Type {
        Class<?> value();

        String name();
    }
}
