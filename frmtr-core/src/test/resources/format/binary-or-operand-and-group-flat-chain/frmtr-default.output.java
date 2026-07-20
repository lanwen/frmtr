class ComponentCatalogSearch {

    // A ||-wrapped predicate whose last operand is an && group ending in a fluent chain that fits
    // within 120 columns; the group must stay flat instead of fanning the chain and oscillating.
    List<CatalogEntry> matching(List<CatalogEntry> entries, String target) {
        return entries.stream()
                .filter(entry -> entry.getName().contains(target)
                        || entry.getName().equalsIgnoreCase(target)
                        || entry.getDescription().toLowerCase(Locale.ROOT).contains(target)
                        || (entry.getGroupId() != null && entry.getGroupId().toLowerCase(Locale.ROOT).contains(target))
                )
                .collect(Collectors.toList());
    }
}
