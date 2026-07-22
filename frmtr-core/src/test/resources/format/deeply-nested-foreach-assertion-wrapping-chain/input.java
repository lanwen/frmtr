package dev.example.verification;

class DeeplyNestedForeachAssertionChain {

    void everyScenarioStepAssertionHolds(ExpressionEvaluator evaluator) {
        scenarios.forEach(scenario -> scenario.steps().forEach(step -> step.assertions().forEach(assertion -> assertThat(evaluator.evaluate(assertion.expression(), scenario.context())).describedAs(step.description()).isEqualTo(assertion.expectedOutcome()))));
    }
}
