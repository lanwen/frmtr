class AnnotatedGenericTypeWidth {

    record Sample(
        @Marker("id")
        @Limit(max = 128, message = "Sample id must stay within an intentionally long fixture-only limit")
        String id,
        @Limit(max = 32, message = "Sample items must stay below the fixture-only size")
        @Marker("items")
        Map<
            @NotEmpty @Limit(
                max = 128,
                message = "Sample item key must stay within an intentionally long fixture-only limit"
            ) String,
            @Limit(
                max = 16384,
                message = "Sample item value must stay within an intentionally long fixture-only limit"
            ) String
        > items
    ) {}
}
