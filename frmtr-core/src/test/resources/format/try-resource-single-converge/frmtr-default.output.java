class TryResourceSingleConverge {

    void sourceBrokenButFits(Catalog catalog) {
        try (var stream = catalog.listEntries(catalog.activeRegion())) {
            consume(stream);
        }
    }

    void sourceAttachedButFits(Catalog catalog) {
        try (var stream = catalog.listEntries(catalog.activeRegion())) {
            consume(stream);
        }
    }

    void leadingCommentBeforeTry(Catalog catalog) {
        if (catalog.ready()) {
            // Fallback: scan the active region for any matching entry
            try (var stream = catalog.listEntries(catalog.activeRegion())) {
                consume(stream);
            }
        }
    }

    void objectCreationResourceBrokenButFits(Catalog catalog) {
        try (var session = new RegionSession(catalog.activeRegion(), catalog.defaultRetryPolicy())) {
            consume(session);
        }
    }

    void genuinelyOverWidthStaysBroken(ResourceCatalog catalog, RouteContext context, AuditTrail auditTrail) {
        try (RouteLease lease = catalog.openManagedRouteLease(
                context.primaryTenant(),
                context.regionCatalog(),
                auditTrail.currentBatch(),
                context.ownerIdentity()
        )) {
            verify(lease);
        }
    }
}
