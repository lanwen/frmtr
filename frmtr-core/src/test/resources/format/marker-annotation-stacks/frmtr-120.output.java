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
}
