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

    /**
     * Collects findings by delegating the entire over-width policy — literal/comment masking, cross-line text-block /
     * block-comment state, and formatter-off / {@code frmtr-ignore} pragma suppression — to a single shared
     * {@link OverWidthLines.Scanner}. The gate adds only its allowlist and SHA hashing on top; pragma policy is no
     * longer duplicated here, so the CLI warning and this audit agree by construction.
     */
    private static List<Finding> findings(FormatFixture fixture, String formatted) {
        OverWidthLines.Scanner scanner = new OverWidthLines.Scanner();
        String[] lines = formatted.split("\\R", -1);
        int lineWidth = fixture.options().lineWidth();
        return java.util.stream.IntStream.range(0, lines.length)
                .mapToObj(index -> scanner.suspicious(lines[index], index + 1, lineWidth)
                        .map(overWidth -> new Finding(
                            fixture.name(),
                            fixture.outputResource(),
                            overWidth.lineNumber(),
                            overWidth.width(),
                            overWidth.lineWidth(),
                            overWidth.line()
                        )))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    record AuditResult(List<Finding> unexpectedFindings, List<AllowlistEntry> staleAllowlistEntries) {
        List<String> failures() {
            return java.util.stream.Stream.concat(
                unexpectedFindings.stream().map(Finding::toString),
                staleAllowlistEntries.stream().map(AllowlistEntry::staleMessage)
            ).toList();
        }
    }

    record Finding(String fixtureName, String outputResource, int lineNumber, int length, int lineWidth, String line) {
        AllowedLine allowedLine() {
            return new AllowedLine(outputResource, lineNumber, hash(line));
        }

        @Override
        public String toString() {
            return "%s (%s:%d) length %d > configured width %d%n%s".formatted(
                fixtureName,
                outputResource,
                lineNumber,
                length,
                lineWidth,
                line
            );
        }
    }

    record AllowlistEntry(AllowedLine allowedLine, String reason) {
        String staleMessage() {
            return "stale suspicious line-width allowlist entry for %s:%d hash %s%nreason: %s".formatted(
                allowedLine.outputResource(),
                allowedLine.lineNumber(),
                allowedLine.lineHash(),
                reason
            );
        }
    }

    record AllowedLine(String outputResource, int lineNumber, String lineHash) {}

    static final class Allowlist {

        private final Map<String, Set<AllowlistEntry>> entriesByResource;

        private Allowlist(Collection<AllowlistEntry> entries) {
            this.entriesByResource = entries.stream()
                    .collect(
                        Collectors.groupingBy(
                            entry -> entry.allowedLine().outputResource(),
                            Collectors.toUnmodifiableSet()
                        )
                    );
        }

        static Allowlist empty() {
            return new Allowlist(List.of());
        }

        static Allowlist load() {
            try (var input = SuspiciousLineWidthAudit.class.getClassLoader().getResourceAsStream(
                    ALLOWLIST_RESOURCE
            )) {
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
            return new Allowlist(
                lines.stream()
                        .filter(line -> !line.isBlank())
                        .filter(line -> !line.startsWith("#"))
                        .map(Allowlist::entries)
                        .flatMap(Collection::stream)
                        .toList()
            );
        }

        boolean contains(AllowedLine allowedLine) {
            return entriesFor(allowedLine.outputResource())
                    .stream()
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
