class FormatterPragmaSpacing {

    int before;
    // @formatter:off
    int   preserved   ;
    // @formatter:on
    int after;
    Object buildPipeline(Object pipeline, Object first, Object second) {
        // @formatter:off
        // prettier-ignore
        return pipeline
            .alpha(step -> {
                step.call(first);
            })
            .beta(second) // preserved beside the raw call
            .gamma()
            .done();
        // @formatter:on
    }
}
// @formatter:off
class RawTop { int   value; }
// @formatter:on
class FormattedTop {

    int value;
}
