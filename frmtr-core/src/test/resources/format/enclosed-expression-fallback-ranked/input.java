class ParenthesizedCallSumBoundary {

    void sumFitsFlat() {
        var result = (computeFirstValueForBoundaryTest(alpha, beta) + computeSecondValueForBoundaryTest(gamma, delta));
    }

    void sumNeedsBreak() {
        var result = (computeFirstValueForBoundaryTestExtension(alpha, beta) + computeSecondValueForBoundaryTestExtension(gamma, delta));
    }
}
