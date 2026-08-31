class ReturnObjectCreationWidthSample {

    Result build() {
        return new AlphaBetaGammaDeltaEpsilonZeta.EtaThetaIotaKappaLambdaRhoSigmaTau(
            "sample-id",
            new MuNuXiOmicronPi()
        );
    }

    Result compactNestedFactoryResult(ResultEntry entry, List<ResultEntry> entries, ResultTail tail) {
        return new ResultEntryList(resultEntryList(entry, entries, tail), false, tail.trailingComment());
    }
}
