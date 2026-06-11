class SwitchMultipleUnnamedPatternsSample {
    sealed interface Token permits AlphaToken, BetaToken, GammaToken, DeltaToken {}

    record AlphaToken() implements Token {}

    record BetaToken() implements Token {}

    record GammaToken() implements Token {}

    record DeltaToken() implements Token {}

    enum Result {
        FIRST,
        SECOND
    }

    Result route(Token token) {
        return switch (token) {
            case AlphaToken _, BetaToken _ -> Result.FIRST;
            case GammaToken _, DeltaToken _ -> Result.SECOND;
        };
    }
}
