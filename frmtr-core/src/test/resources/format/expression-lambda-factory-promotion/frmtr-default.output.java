class ExpressionLambdaFactoryPromotion {

    void intStreamIterateWithExpressionLambda() {
        int[] nonSequentialPartitions = IntStream.iterate(50, next -> next + 7)
                .limit(22)
                .toArray();
    }
}
