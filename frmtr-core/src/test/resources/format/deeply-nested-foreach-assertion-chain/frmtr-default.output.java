package dev.example.verification;

class DeeplyNestedForeachAssertionChain {

    void everyScenarioStepAssertionHolds(ExpressionEvaluator evaluator) {
        scenarios.forEach(scenario -> scenario.steps().forEach(
                step -> step.assertions().forEach(
                    assertion -> evaluator.verifyAssertionHolds(
                        assertion.expression(),
                        scenario.context(),
                        step.description(),
                        assertion.expectedOutcome()
                    )
                )
        ));
    }
}
