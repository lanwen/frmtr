class RecordComponentInlineAnnotations {

    record EnvelopeWithStackedInlineAnnotationsAndSeveralComponents(
        @First @Second String token,
        @First @Second List<String> values,
        @First @Second Optional<String> fallback
    ) {}
}
