module com.example.telemetry.pipeline {
    exports com.example.telemetry.ingest to com.example.telemetry.consumer.metrics, com.example.telemetry.consumer.audit;

    exports com.example.telemetry.export to com.example.telemetry.consumer.dashboard.rendering, com.example.telemetry.consumer.reporting.metrics.aggregation;
}
