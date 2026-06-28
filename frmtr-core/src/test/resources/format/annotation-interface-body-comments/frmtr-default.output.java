public @interface RouteContract { // brace-line note kept on the opening line
    String region() default "default";

    // own-line note between annotation members
    int retries() default 3;

    String channel();
    // trailing note before the closing brace
}

@interface CatalogContract {
    String tier();

    boolean archived() default false;
}
