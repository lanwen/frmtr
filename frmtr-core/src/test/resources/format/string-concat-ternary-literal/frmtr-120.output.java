package sample;

final class StringConcatTernaryLiteral {

    String path(boolean dryRun, String only) {
        return "/items?dryRun=" + dryRun + (only == null ? "" : "&only=" + only);
    }
}
