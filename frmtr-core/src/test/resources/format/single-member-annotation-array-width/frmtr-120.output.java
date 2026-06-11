package sample;

@BindingRegistry(
    { @BindingRegistry.Node(value = HiddenCarrier.PayloadVariant.class, name = "HiddenCarrier.PayloadVariant") }
)
interface ExampleSubject {}

@ProbeHarness(
    webEnvironment = ProbeHarness.WebEnvironment.NONE,
    classes = ProbeConfiguration.class,
    properties = {
        "sample.security.tokens.audiences=" +
            TokenCatalog.PRIMARY_AUDIENCE +
            "," +
            TokenCatalog.SECONDARY_AUDIENCE +
            "," +
            TokenCatalog.WORKER_AUDIENCE +
            "," +
            TokenCatalog.CONTROL_AUDIENCE,
        "jwt.iss=https://client-health.example.testcontainers.local/",
        "spring.main.web-application-type=none",
    }
)
class ProbeSubject {}
