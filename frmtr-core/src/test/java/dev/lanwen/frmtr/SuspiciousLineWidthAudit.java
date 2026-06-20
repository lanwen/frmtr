package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Audits formatted fixture output for over-width lines that still contain obvious breakable Java syntax.
 *
 * <p>The formatter's line width is a target rather than a hard cap: comments, text blocks, formatter-off regions, and
 * unbreakable literals can legitimately exceed it. This helper keeps that policy out of {@link FrmtrTest} while still
 * making fixture regressions visible when a breakable construct collapses past the fixture variant's configured width.
 */
final class SuspiciousLineWidthAudit {

    private static final String ALLOWLIST_RESOURCE = "format/suspicious-line-width-allowlist.tsv";

    private static final Pattern CALL_CHAIN = Pattern.compile(
        "(?:\\.[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(|\\)\\s*\\.[A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern CAST_OR_LAMBDA = Pattern.compile(
        "(?:->|\\([A-Za-z_$][A-Za-z0-9_$]*(?:\\s*&\\s*[A-Za-z_$][A-Za-z0-9_$]*)+\\)\\s*[A-Za-z_$])"
    );
    private static final Pattern LINE_HASH = Pattern.compile("[0-9a-f]{64}");

    private SuspiciousLineWidthAudit() {}

    static void assertNoUnexpectedFindings(FormatFixture fixture, String formatted) {
        assertNoUnexpectedFindings(fixture, formatted, Allowlist.load());
    }

    static void assertNoUnexpectedFindings(FormatFixture fixture, String formatted, Allowlist allowlist) {
        AuditResult result = audit(fixture, formatted, allowlist);

        assertThat(result.failures())
                .as("unexpected suspicious over-width formatted fixture lines")
                .isEmpty();
    }

    static AuditResult audit(FormatFixture fixture, String formatted, Allowlist allowlist) {
        List<Finding> actualFindings = findings(fixture, formatted);
        Set<AllowedLine> actualKeys = actualFindings.stream()
                .map(Finding::allowedLine)
                .collect(Collectors.toUnmodifiableSet());
        List<Finding> unexpected = actualFindings
                .stream()
                .filter(finding -> !allowlist.contains(finding.allowedLine()))
                .toList();
        List<AllowlistEntry> stale = allowlist.entriesFor(fixture.outputResource())
                .stream()
                .filter(entry -> !actualKeys.contains(entry.allowedLine()))
                .toList();

        return new AuditResult(unexpected, stale);
    }

    static Set<String> unknownOutputResources(Allowlist allowlist, Collection<String> knownOutputResources) {
        Set<String> known = Set.copyOf(knownOutputResources);
        return allowlist.outputResources()
                .stream()
                .filter(resource -> !known.contains(resource))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<Finding> findings(FormatFixture fixture, String formatted) {
        ScanState state = new ScanState();
        String[] lines = formatted.split("\\R", -1);
        return java.util.stream.IntStream.range(0, lines.length)
                .mapToObj(index -> finding(fixture, index + 1, lines[index], state))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static java.util.Optional<Finding> finding(
            FormatFixture fixture,
            int lineNumber,
            String line,
            ScanState state
    ) {
        boolean alreadySkipped = state.skippingRawRegion();
        LineScan scan = scanLine(line, state);
        boolean pragmaSkip = state.applyPragmas(scan.pragmas());
        boolean skipLine = alreadySkipped || pragmaSkip;
        if (skipLine || scan.code().stripTrailing().length() <= fixture.options().lineWidth()) {
            return java.util.Optional.empty();
        }

        if (!suspiciousBreakableSyntax(scan.code())) {
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(
            new Finding(
                fixture.name(),
                fixture.outputResource(),
                lineNumber,
                line.length(),
                fixture.options().lineWidth(),
                line
            )
        );
    }

    private static LineScan scanLine(String line, ScanState state) {
        StringBuilder code = new StringBuilder(line.length());
        Pragmas pragmas = new Pragmas();
        int index = 0;
        while (index < line.length()) {
            if (state.inTextBlock()) {
                int delimiter = textBlockDelimiter(line, index);
                if (delimiter == -1) {
                    return new LineScan(code.toString(), pragmas);
                }
                code.append("\"\"");
                state.inTextBlock(false);
                index = delimiter + 3;
                continue;
            }
            if (state.inBlockComment()) {
                int end = line.indexOf("*/", index);
                if (end == -1) {
                    pragmas.record(line.substring(index));
                    return new LineScan(code.toString(), pragmas);
                }
                pragmas.record(line.substring(index, end));
                state.inBlockComment(false);
                index = end + 2;
                continue;
            }

            char current = line.charAt(index);
            if (current == '/' && index + 1 < line.length()) {
                char next = line.charAt(index + 1);
                if (next == '/') {
                    pragmas.record(line.substring(index + 2));
                    break;
                }
                if (next == '*') {
                    state.inBlockComment(true);
                    index += 2;
                    continue;
                }
            }
            if (line.startsWith("\"\"\"", index)) {
                code.append("\"\"");
                state.inTextBlock(true);
                index += 3;
                continue;
            }
            if (current == '"' || current == '\'') {
                code.append(current).append(current);
                index = skipLiteral(line, index + 1, current);
                continue;
            }

            code.append(current);
            index++;
        }
        return new LineScan(code.toString(), pragmas);
    }

    private static int textBlockDelimiter(String line, int index) {
        while (index < line.length()) {
            int delimiter = line.indexOf("\"\"\"", index);
            if (delimiter == -1) {
                return -1;
            }
            if (!escaped(line, delimiter)) {
                return delimiter;
            }
            index = delimiter + 1;
        }
        return -1;
    }

    private static boolean escaped(String line, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && line.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static int skipLiteral(String line, int index, char delimiter) {
        boolean escaped = false;
        while (index < line.length()) {
            char current = line.charAt(index++);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == delimiter) {
                return index;
            }
        }
        return index;
    }

    private static boolean suspiciousBreakableSyntax(String code) {
        String compact = code.strip();
        return containsBinaryOrTernary(compact)
            || commaHeavyCallOrDeclaration(compact)
            || CALL_CHAIN.matcher(compact).find()
            || throwsList(compact)
            || CAST_OR_LAMBDA.matcher(compact).find();
    }

    private static boolean containsBinaryOrTernary(String code) {
        return code.contains(" + ")
            || code.contains(" && ")
            || code.contains(" || ")
            || code.contains(" ? ") && code.contains(" : ");
    }

    private static boolean commaHeavyCallOrDeclaration(String code) {
        return count(code, ',') >= 2 && code.contains("(") && code.contains(")");
    }

    private static boolean throwsList(String code) {
        return code.contains(" throws ") && code.contains(",");
    }

    private static int count(String value, char needle) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == needle) {
                count++;
            }
        }
        return count;
    }

    record AuditResult(List<Finding> unexpectedFindings, List<AllowlistEntry> staleAllowlistEntries) {

        List<String> failures() {
            return java.util.stream.Stream.concat(
                    unexpectedFindings.stream().map(Finding::toString),
                    staleAllowlistEntries.stream().map(AllowlistEntry::staleMessage)
                )
                    .toList();
        }
    }

    record Finding(
            String fixtureName,
            String outputResource,
            int lineNumber,
            int length,
            int lineWidth,
            String line
    ) {

        AllowedLine allowedLine() {
            return new AllowedLine(outputResource, lineNumber, hash(line));
        }

        @Override
        public String toString() {
            return "%s (%s:%d) length %d > configured width %d%n%s"
                    .formatted(fixtureName, outputResource, lineNumber, length, lineWidth, line);
        }
    }

    record AllowlistEntry(AllowedLine allowedLine, String reason) {

        String staleMessage() {
            return "stale suspicious line-width allowlist entry for %s:%d hash %s%nreason: %s"
                    .formatted(
                        allowedLine.outputResource(),
                        allowedLine.lineNumber(),
                        allowedLine.lineHash(),
                        reason
                    );
        }
    }

    record AllowedLine(String outputResource, int lineNumber, String lineHash) {}

    private record LineScan(String code, Pragmas pragmas) {}

    private static final class Pragmas {

        private boolean formatterOff;
        private boolean formatterOn;
        private boolean frmtrIgnore;
        private boolean frmtrIgnoreStart;
        private boolean frmtrIgnoreEnd;

        void record(String comment) {
            if (comment.contains("@formatter:off")) {
                formatterOff = true;
            }
            if (comment.contains("@formatter:on")) {
                formatterOn = true;
            }
            if (comment.contains("frmtr-ignore-start")) {
                frmtrIgnoreStart = true;
            } else if (comment.contains("frmtr-ignore-end")) {
                frmtrIgnoreEnd = true;
            } else if (comment.contains("frmtr-ignore")) {
                frmtrIgnore = true;
            }
        }

        boolean formatterOff() {
            return formatterOff;
        }

        boolean formatterOn() {
            return formatterOn;
        }

        boolean frmtrIgnore() {
            return frmtrIgnore;
        }

        boolean frmtrIgnoreStart() {
            return frmtrIgnoreStart;
        }

        boolean frmtrIgnoreEnd() {
            return frmtrIgnoreEnd;
        }
    }

    private static final class ScanState {

        private boolean inFormatterOff;
        private boolean inFrmtrIgnoreRange;
        private boolean inTextBlock;
        private boolean inBlockComment;

        boolean skippingRawRegion() {
            return inFormatterOff || inFrmtrIgnoreRange;
        }

        boolean applyPragmas(Pragmas pragmas) {
            boolean skipLine = pragmas.frmtrIgnore() || pragmas.frmtrIgnoreStart() || pragmas.frmtrIgnoreEnd()
                || pragmas.formatterOff()
                || pragmas.formatterOn();

            if (pragmas.frmtrIgnoreStart()) {
                inFrmtrIgnoreRange = true;
            }
            if (pragmas.frmtrIgnoreEnd()) {
                inFrmtrIgnoreRange = false;
            }
            if (pragmas.formatterOff()) {
                inFormatterOff = true;
            }
            if (pragmas.formatterOn()) {
                inFormatterOff = false;
            }
            return skipLine;
        }

        boolean inTextBlock() {
            return inTextBlock;
        }

        void inTextBlock(boolean inTextBlock) {
            this.inTextBlock = inTextBlock;
        }

        boolean inBlockComment() {
            return inBlockComment;
        }

        void inBlockComment(boolean inBlockComment) {
            this.inBlockComment = inBlockComment;
        }
    }

    static final class Allowlist {

        private final Map<String, Set<AllowlistEntry>> entriesByResource;

        private Allowlist(Collection<AllowlistEntry> entries) {
            this.entriesByResource = entries.stream()
                    .collect(Collectors.groupingBy(
                        entry -> entry.allowedLine().outputResource(),
                        Collectors.toUnmodifiableSet()
                    ));
        }

        static Allowlist empty() {
            return new Allowlist(List.of());
        }

        static Allowlist load() {
            try (var input = SuspiciousLineWidthAudit.class.getClassLoader()
                    .getResourceAsStream(ALLOWLIST_RESOURCE)) {
                if (input == null) {
                    return empty();
                }
                try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    return parse(reader.lines().toList());
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read " + ALLOWLIST_RESOURCE, exception);
            }
        }

        static Allowlist parse(List<String> lines) {
            return new Allowlist(lines.stream()
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("#"))
                    .map(Allowlist::entries)
                    .flatMap(Collection::stream)
                    .toList());
        }

        boolean contains(AllowedLine allowedLine) {
            return entriesFor(allowedLine.outputResource()).stream()
                    .map(AllowlistEntry::allowedLine)
                    .anyMatch(allowedLine::equals);
        }

        Set<AllowlistEntry> entriesFor(String outputResource) {
            return entriesByResource.getOrDefault(outputResource, Set.of());
        }

        Set<String> outputResources() {
            return Set.copyOf(entriesByResource.keySet());
        }

        private static List<AllowlistEntry> entries(String line) {
            String[] parts = line.split("\\t", 4);
            if (parts.length != 4 || parts[3].isBlank()) {
                throw new IllegalStateException(
                    "Invalid suspicious line-width allowlist entry. Expected outputResource<TAB>lineRange<TAB>"
                        + "sha256[,sha256...]<TAB>reason, got: "
                        + line
                );
            }
            List<Integer> lineNumbers = lineNumbers(line, parts[1]);
            List<String> lineHashes = lineHashes(line, parts[2], lineNumbers.size());
            return java.util.stream.IntStream.range(0, lineNumbers.size())
                    .mapToObj(index -> new AllowlistEntry(
                        new AllowedLine(parts[0], lineNumbers.get(index), lineHashes.get(index)),
                        parts[3]
                    ))
                    .toList();
        }

        private static List<Integer> lineNumbers(String line, String value) {
            if (!value.matches("\\d+(?:-\\d+)?")) {
                lineNumber(line, value);
            }
            String[] range = value.split("-", -1);
            if (range.length == 1) {
                return List.of(lineNumber(line, value));
            }
            int start = lineNumber(line, range[0]);
            int end = lineNumber(line, range[1]);
            if (start > end) {
                throw new IllegalStateException("Invalid suspicious line-width allowlist line range in: " + line);
            }
            return java.util.stream.IntStream.rangeClosed(start, end)
                    .boxed()
                    .toList();
        }

        private static List<String> lineHashes(String line, String value, int expectedCount) {
            List<String> hashes = List.of(value.split(",", -1));
            if (hashes.size() != expectedCount) {
                throw new IllegalStateException("Invalid suspicious line-width allowlist hash count in: " + line);
            }
            hashes.forEach(hash -> {
                if (!LINE_HASH.matcher(hash).matches()) {
                    throw new IllegalStateException("Invalid suspicious line-width allowlist SHA-256 in: " + line);
                }
            });
            return hashes;
        }

        private static int lineNumber(String line, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid suspicious line-width allowlist line number in: " + line);
            }
        }
    }

    static String hash(String line) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(line.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }
}
