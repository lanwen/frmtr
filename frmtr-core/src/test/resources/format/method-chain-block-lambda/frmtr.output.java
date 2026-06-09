package dev.example;

class Demo {

    void method() {
        await().untilAsserted(() -> {
            assertThat(something.isSuccess()).as("success").isTrue();
            something.getValue().tell(new Command.Check(firstValue, secondValue, responseTarget.getRef()));
        });
    }
}
