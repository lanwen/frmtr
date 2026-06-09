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
    }
}
