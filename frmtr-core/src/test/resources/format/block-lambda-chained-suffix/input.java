package dev.example;
class Demo {
void method() {
verifyFailure(() -> {
Builder.create().withFirst(firstValue).withSecond(secondValue).withThird(thirdValue).withFourth(fourthValue).withFifth(fifthValue).finish();
}).isProblem(ExpectedProblem.class);
Flow.from(() -> {
// keep comment inside lambda body
return source.read().orElseGet(() -> fallback.create());
}).scheduleOn(worker);
Pipeline.defer(() -> {
if (attempts.incrementAndGet() <= 4) {
return Pipeline.failed(new RuntimeException("not ready"));
}

return Pipeline.just("connected");
}).retryWhen(
RetryPlan.create(
new Resource("resource-1", Resource.endpoint("localhost", 22)),
targetEndpoint,
4
).toRetry()
);
}
}
