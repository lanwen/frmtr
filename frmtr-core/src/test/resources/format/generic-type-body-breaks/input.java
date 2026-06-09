class Demo {
    void method() {
        var adapter = (RedactedFormatterHarness<
            AlphaEnvelope.FirstPayload,
            BetaEnvelope.SecondPayload,
            GammaEnvelope.ThirdPayload
        >) (RedactedFormatterHarness<?, ?, ?>) create();
    }

    void longName() {
        var adapterIdentifierWithNoRoomForCastOpenerWhenTheGenericTypeNameNeedsToStartAfterEqualsSign = (RedactedFormatterHarness<
            AlphaEnvelope.FirstPayload,
            BetaEnvelope.SecondPayload,
            GammaEnvelope.ThirdPayload
        >) (RedactedFormatterHarness<?, ?, ?>) create();
    }

    record Capture(
        Context context,
        RedactedFormatterHarness<
            AlphaEnvelope.FirstPayload,
            BetaEnvelope.SecondPayload,
            GammaEnvelope.ThirdPayload
        > adapter
    ) {}
}
