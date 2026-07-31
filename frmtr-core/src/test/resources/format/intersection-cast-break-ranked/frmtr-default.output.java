class ResourceCastBoundary {

    void castFitsFlat() {
        Object castResult = (ReadableResource & AuditableResource & VersionedResource) candidate;
    }

    void castNeedsOnePerLine() {
        Object castResult = (ReadableResource
                & AuditableResource
                & VersionedResource
        ) candidateWithLongerNameForBoundary;
    }
}
