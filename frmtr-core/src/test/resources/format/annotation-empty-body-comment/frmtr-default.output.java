public @interface NoAttributeMarker {
    // intentionally has no elements
}

public @interface BlockCommentMarker {
    /* reserved for future elements */
}

public @interface DocumentedElements {
    String channel() default "default";
}

public class EmptyHolder {
    // populated by the runtime
}
