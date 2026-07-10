package sample;

import java.io.File;

final class MethodChainTrailingEmptyCallComment {

    private static final int WORKER_PORT = 6379;

    @Managed
    private static RelaySubject relaySubject = new RelaySubject<>(ImageName.parse("relay:4.4")).withoutAuthentication(); // disable credentials

    @Managed
    public ComposeHarness environment = new ComposeHarness(
        ImageName.parse("runner:25.0.5"),
        new File("src/test/resources/scaled-plan.yml")
    )
            .withScaledService("worker", 3)
            .withExposedService("worker", WORKER_PORT) // implicit route
            .withExposedService("worker-2", WORKER_PORT) // explicit route
            .withExposedService("worker", 3, WORKER_PORT); // explicit route via parameter

    private RoutedHarnessCatalog<
        PrimaryRoutePlan,
        SecondaryWorkerPlan,
        ArchiveFallbackPlan,
        DeferredAuditPlan
    > fieldTypeBreaksBeforeChainWithWideDeclarationPrefix = new RelaySubject<>(ImageName.parse("relay:4.4"))
            .withoutAuthentication(); // disable credentials after type break

    void choose() {
        var selected = use(
            SubjectFactory.create() // primary subject
                    .named("first")
                    .enabled()
        );
        sink(selected);
    }

    void verify() {
        verifyResult(source())
                // expected branch
                .expect(IllegalStateException.class)
                .complete();
    }
}
