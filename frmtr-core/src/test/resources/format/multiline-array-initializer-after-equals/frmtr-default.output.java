package sample;

final class MultilineArrayInitializerAfterEquals {

    static final String[] PUBLIC_HOSTS = {
        "api.primary.example",
        "api.secondary.example",
        "internal.primary.example",
        "internal.secondary.example",
    };

    static final String[] CONTROL_PATHS = {
        "/internal/widgets/**",
        "/internal/members/**",
        "/settings",
        "/reports",
    };
}
