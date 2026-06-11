package sample;

@BindingRegistry(
    { @BindingRegistry.Node(value = HiddenCarrier.PayloadVariant.class, name = "HiddenCarrier.PayloadVariant") }
)
interface ExampleSubject {}

@ProbeHarness(
    webEnvironment = ProbeHarness.WebEnvironment.NONE,
    classes = ProbeConfiguration.class,
    properties = {
        "jwt.iss=https://client-health.example.testcontainers.local/",
        "spring.main.web-application-type=none",
    }
)
class ProbeSubject {}
