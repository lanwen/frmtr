import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

class LogSegmentRetentionAudit {

    void recordExpiry(LogSegmentProvenance provenance) {
        ZonedDateTime zonedDateTime = Instant.ofEpochMilli(provenance.lastContainedLogTimeMs()).atZone(ZoneId.of("UTC"));
        expiredAt.set(zonedDateTime);
    }
}
