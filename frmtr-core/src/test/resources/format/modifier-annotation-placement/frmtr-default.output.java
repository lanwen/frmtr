@StableApi
@AuditVisible
@LocalizedText
public interface EndpointContract {
    @StableApi
    public static final String DEFAULT_MESSAGE = "abc";

    @StableApi
    @AuditVisible
    public default @LocalizedText String defaultMessage() {
        return DEFAULT_MESSAGE;
    }

    @StableApi
    public static @AuditVisible String staticMessage() {
        return DEFAULT_MESSAGE;
    }

    public @StableApi @AuditVisible void publishAnnotatedEvent();

    @StableApi
    void markerMethod();
}

@StableApi
@AuditVisible
public abstract class AbstractMetricSource {

    @CachedValue
    private static volatile String cachedMetric;

    @StableApi
    @AuditVisible
    protected abstract @LocalizedText String loadMetric();

    public @StableApi @AuditVisible void publishAnnotatedEvent() {}

    @StableApi
    void markerMethod() {}
}

@StableApi
@AuditVisible
public final class MetricSource {

    @StableApi
    @AuditVisible
    private static final transient String PRIMARY_METRIC = "abc";

    @StableApi
    @AuditVisible
    protected static final @LocalizedText String FALLBACK_METRIC = "123";

    @StableApi
    public static @AuditVisible String sharedMetric;

    public @StableApi @AuditVisible String annotatedMetric;

    @StableApi
    String markedMetric;

    @StableApi
    @AuditVisible
    protected static final synchronized @LocalizedText String computeMetric() {
        return PRIMARY_METRIC;
    }

    public @StableApi @AuditVisible void publishAnnotatedEvent() {}

    @StableApi
    void markerMethod() {}
}
