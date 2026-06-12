public class ObjectCreationSamples {

    public void instantiation() {
        new BatchRequest("few", "arguments");

        new BatchRequest(
            "tenant",
            "region",
            "priority",
            "quantity",
            "of",
            "arguments",
            new NestedPayload("that", "carry", "nested", new NestedPayload("object creation"), "everywhere", "!"),
            "should",
            "wrap"
        );

        new ResponseBuilder().aLongEnoughMethodNameToExtendPastPrintWidth();
    }
}
