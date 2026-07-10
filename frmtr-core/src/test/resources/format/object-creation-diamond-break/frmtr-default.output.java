package sample;

final class DiamondBreak {

    private final Registry<Subject> registry = new Registry<>();

    private static final SubjectLimit ACTIVE_LIMIT = new SubjectLimit(factory.createSubjectToken().toString(), 1000);

    private final Registry<Subject> registryWithArguments = new Registry<>(
        initialSubjects(),
        initialMode(),
        initialOwner()
    );

    void createAnonymousStrategy(Clock clock, Backoff minBackoff, Backoff maxBackoff, Duration maxDuration) {
        FixturePlan plan = new FixtureAnonymousStrategy(clock, minBackoff, maxBackoff, maxDuration) {
            @Override
            public void reset() {
                super.reset();
                // keep reset status comment
                status.markReady();
            }

            @Override
            public boolean shouldRetry(Throwable failure) {
                // keep retry status comment
                return super.shouldRetry(failure);
            }
        };
    }
}
