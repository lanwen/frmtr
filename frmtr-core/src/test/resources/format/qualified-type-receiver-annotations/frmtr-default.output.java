class QualifiedReceiverSamples {

    // Fix for https://github.com/jhipster/prettier-java/issues/453
    InteractionService.@Valid InteractionRecord interactionRecord = interactionService.getInteractions().get(0);

    // Fix for https://github.com/jhipster/prettier-java/issues/444
    void process(Map.@NonNull Entry<String, ProcessorConfiguration> entry, @NonNull Map<String, Object> context) {}
}

// Fix for https://github.com/jhipster/prettier-java/issues/607
class Currency {

    Currency(Currency this) {}

    Currency(Currency this, Currency other) {}

    Currency(@AnnotatedUsage Currency this, Currency other) {}

    Currency(@AnnotatedUsage Currency this, String currencyCode, String displayName) {}

    String getCode(Currency this) {}

    int compareTo(Currency this, Currency other) {}

    int compareTo(@AnnotatedUsage Currency this, Currency other) {}

    int compareTo(@AnnotatedUsage Currency this, String currencyCode, String displayName) {}

    class Inner {

        Inner(Currency Currency.this) {}

        String getCode(Currency Currency.this) {}
    }
}
