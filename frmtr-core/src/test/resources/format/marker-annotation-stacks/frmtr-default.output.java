package test;

@SingleMemberAnnotation2(name = "Quarterly reconciliation job", date = "01/01/2018")
@SingleMemberAnnotation1(name = "Thorben von Hacht", date = "01/01/2018")
@NormalAnnotation("scheduled")
@MarkerAnnotation
public class MarkerAnnotations {

    @SingleMemberAnnotation2(name = "Quarterly reconciliation job", date = "01/01/2018")
    @SingleMemberAnnotation1(name = "Thorben von Hacht", date = "01/01/2018")
    @NormalAnnotation("scheduled")
    @MarkerAnnotation
    SomeService service;

    @CatalogFixture("field cache")
    // Field cache remains visible to generated binders.
    @SuppressWarnings("FixtureFieldName")
    RoutingService routingService;

    @SingleMemberAnnotation2(name = "Quarterly reconciliation job", date = "01/01/2018")
    @SingleMemberAnnotation1(name = "Thorben von Hacht", date = "01/01/2018")
    @NormalAnnotation("scheduled")
    @MarkerAnnotation
    public void postConstruct() {
        System.out.println("post construct");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @SuppressWarnings2({ "rawtypes", "unchecked", "legacyAdapters", "rawPayloads", "migrationBridge", "auditFallback" })
    public void elementValueArrayInitializer() {
        System.out.println("element value array initializer");
    }

    @ArrayInitializersWithKey(
        key = { "draft", "review" },
        key2 = { "approved", "rejected" },
        key3 = { "archived", "restored" }
    )
    public void arrayInitializerWithKey() {
        System.out.println("element value array initializer with key");
    }

    @CatalogFixture("batch verifier")
    // Batch verifier keeps adapter metadata for archived schedules.
    @SuppressWarnings("FixtureLegacyName")
    void scheduledOnly() {}
}
