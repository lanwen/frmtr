package sample;

sealed interface WideSubject extends BaseSubject permits FirstSubject, SecondSubject, ThirdSubject, FourthSubject {
    String id();
}

final class WideImplementation extends BaseImplementation<FirstSubject, SecondSubject, ThirdSubject> implements FirstSubject, SecondSubject {
    String id() {
        return "wide";
    }
}

abstract class CompactGenericHeaderWithEnoughNameToPreferClauseBreakFallback<T extends Item> extends BaseHeaderProcessor<T> {}

class NestedHeaderFixtureSample {
    static class Runner extends FixtureProcessor<Command, NestedHeaderFixtureSample.Runner.Output, NestedHeaderFixtureSample.Runner.State> {
        String id() {
            return "nested";
        }
    }
}
