package dev.example.binding;

public record ServiceBindingConfig(
    @NotNull String serviceId,
    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = StorageBackendType.PlatformDefaultFilter.class) StorageBackendType backend,
    @JsonProperty("replication_endpoint") @JsonInclude(JsonInclude.Include.NON_ABSENT) ReplicationEndpoint replicationEndpoint
) implements Serializable {}
