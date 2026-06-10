package dev.example;

class Demo {

    void method() {
        verifyFailure(() -> {
            Builder.create()
                .withFirst(firstValue)
                .withSecond(secondValue)
                .withThird(thirdValue)
                .withFourth(fourthValue)
                .withFifth(fifthValue)
                .finish();
        }).isProblem(ExpectedProblem.class);
        Flow.from(() -> {
            // keep comment inside lambda body
            return source.read().orElseGet(() -> fallback.create());
        }).scheduleOn(worker);
    }
}
