package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Locks in the source-shape consolidation roadmap (B1) by failing if a Java printer reintroduces one of the
 * source-coupling patterns that B1 collapsed into {@link SourceShapePolicy}.
 *
 * <p>B1 moved every "should the formatter respect the author's source shape here?" decision behind a single policy.
 * Two of those decisions had a copy-pasted spelling that this guard now forbids outside the policy and the
 * recovery/raw-output helpers that legitimately own raw text:
 *
 * <ul>
 *   <li>the raw multiline probe {@code rawSource....contains("\n")} — there is now one definition of "was multiline",
 *       {@link SourceShapePolicy#wasMultiline}, which keeps the range-first/raw-fallback logic in one place; and</li>
 *   <li>the deliberate-blank-line gap test — in both its addition spelling {@code previous.end.line + 1} and its
 *       subtraction spelling {@code next.begin.line - previous.end.line} — there is now one definition of "had a blank
 *       line between", {@link SourceShapePolicy#hadBlankLineBetween} / {@link SourceShapePolicy#hadBlankLineBefore}.</li>
 * </ul>
 *
 * <p>The guard matches exactly these literal spellings of the two patterns B1 drove to zero, so it stays green while
 * still catching a regression where a new printer hand-rolls one of them again instead of asking the policy. It is a
 * spelling-level tripwire, not a semantic analysis: the broader "no {@code getRange().*.line} layout arithmetic outside
 * the policy" rule remains the <em>documented review checklist</em> in {@code docs/java-formatter-internals.md} rather
 * than a test, because that arithmetic legitimately remains in {@link SourceShapePolicy} itself and in the
 * recovery/source helpers (for example the recovered-region planners) and a broad pattern match would be flaky.
 *
 * <p>The allowlist names the files that are allowed to spell these patterns: the policy itself ({@link SourceShapePolicy})
 * and the slicing / raw-output / compact / recovery helpers it delegates to. Those own raw text and source slicing on
 * purpose; the printers above them must go through the policy.
 */
final class SourceShapeCouplingGuardTest {

    /**
     * The package directory whose printers must not reintroduce the consolidated source-shape patterns.
     */
    private static final Path JAVA_FORMATTER_SOURCE_DIR = locateJavaFormatterSourceDir();

    /**
     * Files allowed to spell the consolidated patterns: the policy that now owns the decisions, plus the slicing,
     * raw-output, compact-text, and parse-recovery helpers it delegates to. These intentionally read raw source and
     * token ranges; the layout printers above them must consult {@link SourceShapePolicy} instead.
     */
    private static final Set<String> ALLOWLISTED_FILES = Set.of(
        "SourceShapePolicy.java",
        "SourceText.java",
        "RawSource.java",
        "RawPreservedSource.java",
        "CompactSourceText.java",
        "RecoveredListPlanner.java",
        "RecoveredSourceRegions.java",
        "RecoveredRawGapPrinter.java"
    );

    /**
     * The raw multiline probe B1 unified into {@link SourceShapePolicy#wasMultiline}: a {@code rawSource} read whose
     * result is tested for an embedded newline on the same line.
     */
    private static final Pattern RAW_NEWLINE_PROBE = Pattern.compile("rawSource\\b.*contains\\(\"\\\\n\"\\)");

    /**
     * The deliberate-blank-line gap test B1 centralized into {@link SourceShapePolicy#hadBlankLineBetween}, written as
     * the {@code <range>.end.line + 1} addition the member/statement/enum/module/record printers used to copy.
     */
    private static final Pattern BLANK_LINE_GAP_ARITHMETIC = Pattern.compile("\\.end\\.line\\s*\\+\\s*1\\b");

    /**
     * The same deliberate-blank-line gap test spelled as subtraction, {@code <next>.begin.line - <prev>.end.line} (the
     * form deleted from {@code RecordDeclarationPrinter}): {@code begin.line - … end.line > 1} also asks "was there a
     * blank line between these?" and belongs behind {@link SourceShapePolicy#hadBlankLineBetween}. This complements
     * {@link #BLANK_LINE_GAP_ARITHMETIC} so neither literal spelling of the gap can creep back into a printer.
     */
    private static final Pattern BLANK_LINE_GAP_SUBTRACTION =
        Pattern.compile("begin\\.line\\s*-\\s*[A-Za-z0-9_.]*end\\.line");

    @Test
    void noRawNewlineProbeOutsideTheSourceShapePolicy() {
        assertThat(matchesOutsideAllowlist(RAW_NEWLINE_PROBE))
                .as(
                    "rawSource ... contains(\"\\n\") multiline probes must ask SourceShapePolicy.wasMultiline instead; "
                    + "found re-introduced probes outside the policy/recovery allowlist"
                )
                .isEmpty();
    }

    @Test
    void noBlankLineGapArithmeticOutsideTheSourceShapePolicy() {
        assertThat(matchesOutsideAllowlist(BLANK_LINE_GAP_ARITHMETIC))
                .as(
                    "blank-line gap arithmetic (previous.end.line + 1) must ask SourceShapePolicy.hadBlankLineBetween "
                    + "instead; found re-introduced gap arithmetic outside the policy/recovery allowlist"
                )
                .isEmpty();
    }

    @Test
    void noBlankLineGapSubtractionOutsideTheSourceShapePolicy() {
        assertThat(matchesOutsideAllowlist(BLANK_LINE_GAP_SUBTRACTION))
                .as(
                    "blank-line gap arithmetic (next.begin.line - previous.end.line) must ask "
                    + "SourceShapePolicy.hadBlankLineBetween instead; found re-introduced gap subtraction outside the "
                    + "policy/recovery allowlist"
                )
                .isEmpty();
    }

    private static List<String> matchesOutsideAllowlist(Pattern pattern) {
        try (Stream<Path> sources = Files.walk(JAVA_FORMATTER_SOURCE_DIR)) {
            return sources
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !ALLOWLISTED_FILES.contains(path.getFileName().toString()))
                    .flatMap(path -> matchingLines(path, pattern))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not scan Java formatter sources for source-shape coupling", exception);
        }
    }

    private static Stream<String> matchingLines(Path path, Pattern pattern) {
        List<String> hits = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1);
            if (pattern.matcher(line).find()) {
                hits.add(path.getFileName() + ":" + lineNumber + " " + line.strip());
            }
        }
        return hits.stream();
    }

    /**
     * Resolves the {@code dev/lanwen/frmtr/java} main-source directory whether the test runs from the module directory
     * (Gradle's default working directory) or from the repository root.
     */
    private static Path locateJavaFormatterSourceDir() {
        Path relative = Path.of("src/main/java/dev/lanwen/frmtr/java");
        Path fromModule = Path.of("").toAbsolutePath().resolve(relative);
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRepoRoot = Path.of("").toAbsolutePath().resolve("frmtr-core").resolve(relative);
        if (Files.isDirectory(fromRepoRoot)) {
            return fromRepoRoot;
        }
        throw new IllegalStateException(
            "Could not locate frmtr-core Java formatter sources from working directory "
            + Path.of("").toAbsolutePath()
        );
    }
}
