public class RouteMatcher {

    boolean matchesAny(RouteContext context, String needle) {
        return context.enabled() && (context.primaryRoute() /* fallback route */
                .normalizedName(Locale.ROOT)
                .contains(needle));
    }
}
