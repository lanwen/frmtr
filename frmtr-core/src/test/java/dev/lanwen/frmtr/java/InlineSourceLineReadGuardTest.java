package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Extends the closed-set governance of {@link SourceShapeException} / {@link SourceShapeExceptionGovernanceTest} down to
 * the <em>inline</em> tier of source-shape reads, enforcing the closed set declared in {@link InlineSourceShapeException}.
 *
 * <p>{@link SourceShapeExceptionGovernanceTest} pins the reads that go through {@link SourceShapePolicy}, but the D3 flip
 * uncovered a second tier that escaped that ratchet: printers that hand-roll an aesthetic "preserve the author's line
 * layout" decision inline, without ever touching the policy. Two mechanisms make such a read:
 *
 * <ul>
 *   <li><strong>line-compare</strong> — comparing two token {@code getRange()} line numbers:
 *       {@code range.begin.line < range.end.line} ("was this node multiline in the source?"), or
 *       {@code X.begin.line == / < / > Y.begin.line}/{@code .end.line} ("did Y start on the same / a later / an earlier
 *       line than X?"); and</li>
 *   <li><strong>raw-source shape</strong> (the hole this slice closes — previously excluded on purpose) — reading a
 *       node's raw source text and inspecting its line structure: piping {@code rawWithoutOwnComment(...)} into
 *       {@code .lines().findFirst()} to grab the author's first source line, or asking {@code ....contains("\n")} on
 *       non-compact text whether the author spread the node across lines.</li>
 * </ul>
 *
 * <p>Both are "preserve the author's line breaks" reads exactly like the catalogued {@link SourceShapePolicy} ones, just
 * as fragile under reprint-by-default. This guard scans the Java formatter sources for both spellings and fails if it
 * finds one whose {@code SimpleClassName#method} key is neither declared in {@link InlineSourceShapeException} nor an
 * excluded legitimate category. A NEW inline read then trips this test, forcing the author to catalogue and justify it
 * (or retire it) — the closed set is now enforced for inline reads too.
 *
 * <p>Legitimate, non-aesthetic reads are excluded so they are not mistaken for layout preservation:
 * <ul>
 *   <li><strong>comment placement</strong> — {@link CommentIndex}, {@link JavaCommentPlacementPolicy},
 *       {@code CommentedMethodSignaturePrinter}, and any method whose name mentions a comment
 *       ({@link #COMMENT_METHOD_MARKER}) decide where a comment lands relative to its owner, not how code wraps;</li>
 *   <li><strong>source-order sorts</strong> — {@code ControlConditionPrinter} / {@code DeclarationPrefixPrinter} /
 *       {@code CallableSignaturePrinter} compare line numbers to <em>order</em> nodes/comments deterministically, which
 *       reprint reproduces;</li>
 *   <li><strong>deliberate blank lines</strong> — a method whose name mentions a blank line
 *       ({@link #BLANK_LINE_METHOD_MARKER}) reads the one-blank-line gap already owned by
 *       {@link SourceShapePolicy#hadBlankLineBetween} (blank lines collapse to a fixpoint);</li>
 *   <li><strong>width-measurement reconstruction</strong> — a method whose name mentions width
 *       ({@link #WIDTH_METHOD_MARKER}) uses the range to reconstruct a column for a width probe, not to preserve a
 *       break;</li>
 *   <li><strong>compact-text newline checks</strong> — a {@code .contains("\n")} on {@code compact}-derived text (not on
 *       raw source) is a width/structure gate over stable compact text, not the author's source shape; and</li>
 *   <li><strong>verbatim single-line preservation</strong> — {@link #FIXPOINT_SAFE_SINGLE_LINE_READS} gate a
 *       reproduced-verbatim single-line source form (interior array spacing, a raw single-line switch entry). The output
 *       reproduces the form and a single line re-reads as a single line, so the read round-trips to a fixpoint; it is not
 *       a line-break aesthetic and is not a retirement target.</li>
 * </ul>
 *
 * <p>This is a documented, tuned heuristic, not a parser: it matches the aesthetic spellings and attributes each hit to
 * its nearest enclosing method declaration.
 */
final class InlineSourceLineReadGuardTest {

    private static final Path JAVA_FORMATTER_SOURCE_DIR = locateJavaFormatterSourceDir();

    /**
     * Whole files excluded from the scan because they legitimately own raw source / range reads that are not aesthetic
     * line-break preservation: the policy and its slicing/recovery helpers, the comment-placement subsystem, and the
     * source-order-sort helpers ({@code ControlConditionPrinter}, {@code DeclarationPrefixPrinter}) the task documents as
     * legitimate. Their line reads either round-trip to a fixpoint or order nodes deterministically.
     */
    private static final Set<String> EXCLUDED_FILES = Set.of(
        // policy + raw/slice/recovery helpers that own source reads on purpose
        "SourceShapePolicy.java",
        "SourceText.java",
        "RawSource.java",
        "RawPreservedSource.java",
        "CompactSourceText.java",
        "RecoveredListPlanner.java",
        "RecoveredSourceRegions.java",
        "RecoveredRawGapPrinter.java",
        // comment-placement subsystem: line reads decide comment ownership/placement, not code wrapping
        "CommentIndex.java",
        "JavaCommentPlacementPolicy.java",
        "CommentedMethodSignaturePrinter.java",
        // source-order sorts + comment-relative ordering (Integer.compare(...begin.line...) and comment/annotation gaps)
        "ControlConditionPrinter.java",
        "DeclarationPrefixPrinter.java"
    );

    /**
     * The closed set of residual inline aesthetic source-shape reads — the single registry is {@link
     * InlineSourceShapeException}, keyed by {@code SimpleClassName#method} so entries survive line moves. Each is a
     * "preserve the author's line breaks" read deferred on the enclosing-column / {@code leftEdgePrefix} foundation and
     * tracked as the G3 retirement slice.
     */
    private static final Set<String> ALLOWLISTED_AESTHETIC_READS = Arrays.stream(InlineSourceShapeException.values())
        .map(InlineSourceShapeException::key)
        .collect(Collectors.toUnmodifiableSet());

    /**
     * Reads that gate a <em>reproduced-verbatim single-line source form</em> on {@code .contains("\n")}: interior array
     * spacing ({@code { x }} vs {@code {x}}) and the raw single-line switch-entry escape hatch. The formatter reproduces
     * the single-line form verbatim and a single line re-reads as a single line, so the read round-trips to a fixpoint —
     * a spacing/syntax preservation like {@link SourceShapeException}'s {@code FIXPOINT_SAFE} rows, not the author's
     * line-break layout. Excluded from the retirement-target set, not catalogued in {@link InlineSourceShapeException}.
     */
    private static final Set<String> FIXPOINT_SAFE_SINGLE_LINE_READS = Set.of(
        "ArrayExpressionPrinter#compactArrayInitializerUsesInteriorSpacing",
        "SwitchPrinter#rawSingleLineSwitchEntry"
    );

    /** A method name mentioning a comment: its line read is comment placement, not code-wrap preservation. */
    private static final Pattern COMMENT_METHOD_MARKER = Pattern.compile("[Cc]omment");

    /** A method name mentioning a blank line: its line read is the deliberate-blank-line gap (fixpoint-safe). */
    private static final Pattern BLANK_LINE_METHOD_MARKER = Pattern.compile("[Bb]lankLine");

    /** A method name mentioning width: its line read reconstructs a column for a width probe, not a break decision. */
    private static final Pattern WIDTH_METHOD_MARKER = Pattern.compile("[Ww]idth");

    /**
     * The "was this node multiline in source?" probe: two line reads off the same range compared with {@code <}
     * ({@code range.begin.line < range.end.line}).
     */
    private static final Pattern WAS_MULTILINE =
        Pattern.compile("\\bbegin\\.line\\s*<\\s*[A-Za-z0-9_]*\\.?end\\.line");

    /**
     * The "did Y start on the same / a later / an earlier line than X?" probe: a line read off one range compared with
     * {@code ==}/{@code <}/{@code >} to a line read off another range ({@code X.begin.line == Y.begin.line}, etc.).
     */
    private static final Pattern CROSS_RANGE_LINE_COMPARE =
        Pattern.compile("(?:begin|end)\\.line\\s*(?:==|<|>)\\s*[A-Za-z0-9_]+\\.(?:begin|end)\\.line");

    /**
     * The same probe against a captured {@code *Line} int local (e.g. {@code range.begin.line > firstAnnotationLine}),
     * the record-component annotation form that derives the reference line into a variable first.
     */
    private static final Pattern RANGE_LINE_VS_LINE_LOCAL =
        Pattern.compile("(?:begin|end)\\.line\\s*(?:==|<|>)\\s*[a-z][A-Za-z0-9_]*Line\\b");

    /** A {@code .contains("\n")} newline probe — the "did the author spread this node across source lines?" spelling. */
    private static final Pattern CONTAINS_NEWLINE = Pattern.compile("\\.contains\\(\"\\\\n\"\\)");

    /** A raw-source producer token: a node's un-normalized source text fed into a line-structure probe. */
    private static final Pattern RAW_SOURCE_PRODUCER = Pattern.compile("\\brawWithoutOwnComment\\b");

    /**
     * A method-declaration line: begins (after indentation) with optional annotations/modifiers/generics, a return type,
     * then {@code name(}. Excludes chained calls, control keywords, and continuation lines so a hit inside a
     * {@code .map(range -> ...)} lambda is attributed to the real enclosing method, not the {@code map} call.
     */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
        "^(?:@\\w+\\s+)*"
        + "(?:(?:private|protected|public|static|final|abstract|default|synchronized)\\s+)*"
        + "(?:<[^>]*>\\s+)?"
        + "[A-Za-z_][A-Za-z0-9_.<>,?\\[\\] ]*\\s+"
        + "([a-zA-Z0-9_]+)\\s*\\("
    );

    private static final Set<String> CONTROL_KEYWORDS =
        Set.of("if", "for", "while", "switch", "catch", "return", "new", "synchronized");

    @Test
    void everyInlineAestheticSourceShapeReadIsCataloguedOrAnExcludedLegitimateCategory() {
        Map<String, List<String>> foundOutsideAllowlist = scanAestheticReads();
        assertThat(foundOutsideAllowlist)
            .as(
                "inline aesthetic source-shape reads (getRange().begin.line/.end.line comparisons, or "
                + "rawWithoutOwnComment(...).lines().findFirst()/.contains(\"\\n\") on raw source that decide a line "
                + "break) must be catalogued: either add the read behind a structural rule and retire it, or — if it is "
                + "a deferred residual — add a value to InlineSourceShapeException with its SimpleClassName#method key and "
                + "deferral cause. Reprint-by-default keeps this closed set enforced for inline reads too. "
                + "Uncatalogued reads found: %s",
                foundOutsideAllowlist
            )
            .isEmpty();
    }

    @Test
    void everyCataloguedResidualStillExistsInTheSources() {
        Set<String> present = scanAllAestheticReads().keySet();
        assertThat(present)
            .as(
                "every InlineSourceShapeException value must still name a live inline read; a stale entry means the read "
                + "was retired and its enum value should be deleted (progress!)"
            )
            .containsAll(ALLOWLISTED_AESTHETIC_READS);
    }

    @Test
    void everyCataloguedResidualDeclaresADeferralCauseAndTracking() {
        for (InlineSourceShapeException read : InlineSourceShapeException.values()) {
            assertThat(read.deferralCause())
                .as("InlineSourceShapeException.%s deferral cause", read)
                .isNotBlank();
            assertThat(read.tracking())
                .as("InlineSourceShapeException.%s tracking note", read)
                .isNotBlank();
        }
    }

    /** Aesthetic reads whose {@code SimpleClassName#method} key is neither catalogued nor an excluded category. */
    private static Map<String, List<String>> scanAestheticReads() {
        Map<String, List<String>> hits = new TreeMap<>();
        scanAllAestheticReads().forEach((key, lines) -> {
            String method = key.substring(key.indexOf('#') + 1);
            if (ALLOWLISTED_AESTHETIC_READS.contains(key)
                || FIXPOINT_SAFE_SINGLE_LINE_READS.contains(key)
                || COMMENT_METHOD_MARKER.matcher(method).find()
                || BLANK_LINE_METHOD_MARKER.matcher(method).find()
                || WIDTH_METHOD_MARKER.matcher(method).find()) {
                return;
            }
            hits.put(key, lines);
        });
        return hits;
    }

    /** All aesthetic inline reads keyed by {@code SimpleClassName#method}, before allow/exclude filtering. */
    private static Map<String, List<String>> scanAllAestheticReads() {
        try (Stream<Path> sources = Files.walk(JAVA_FORMATTER_SOURCE_DIR)) {
            Map<String, List<String>> hits = new TreeMap<>();
            sources
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(path -> !EXCLUDED_FILES.contains(path.getFileName().toString()))
                .forEach(path -> collectHits(path, hits));
            return hits;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not scan Java formatter sources for inline source-shape reads", exception);
        }
    }

    private static void collectHits(Path path, Map<String, List<String>> hits) {
        String className = path.getFileName().toString().replace(".java", "");
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
        // Accumulate each method's code lines so the multi-line raw-source-first-line probe (rawWithoutOwnComment(...)
        // spread over .strip()/.lines()/.findFirst() continuation lines) can be recognized at method granularity.
        Map<String, List<String>> methodBodies = new TreeMap<>();
        String enclosingMethod = "<unknown>";
        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            String raw = lines.get(lineNumber - 1);
            String stripped = raw.strip();
            if (isCommentOrBlank(stripped)) {
                continue;
            }
            // Track the enclosing method only from lines that begin a declaration — never from continuation lines (a
            // chained call / operator) or statements, so an aesthetic read inside a `.map(range -> ...)` lambda is
            // attributed to the real method, not to `map`.
            if (!isContinuationLine(stripped) && looksLikeMethodDeclarationStart(stripped)) {
                Matcher declaration = METHOD_DECLARATION.matcher(stripped);
                if (declaration.find() && !CONTROL_KEYWORDS.contains(declaration.group(1))) {
                    enclosingMethod = declaration.group(1);
                }
            }
            String key = className + "#" + enclosingMethod;
            methodBodies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stripped);
            // The line-compare and newline probes each live on a single (usually continuation) line, so they are checked
            // per line regardless of continuation.
            if (isAestheticLineCompare(raw)) {
                recordHit(hits, key, lineNumber, stripped);
            }
            if (isSourceMultilineNewlineProbe(raw)) {
                recordHit(hits, key, lineNumber, stripped);
            }
        }
        recordRawSourceFirstLineProbes(hits, methodBodies);
    }

    private static void recordHit(Map<String, List<String>> hits, String key, int lineNumber, String stripped) {
        hits.computeIfAbsent(key, ignored -> new ArrayList<>()).add(lineNumber + ": " + stripped);
    }

    /**
     * Flags a method whose body pipes a raw-source read into a first-source-line probe:
     * {@code rawWithoutOwnComment(...)} reaching {@code .lines()} and {@code .findFirst()}. This is the "keep the
     * author's first source line" idiom. A {@code .lines().count()} (line-count for placement, no {@code .findFirst()})
     * is deliberately not matched — it measures a position, it does not preserve a break.
     */
    private static void recordRawSourceFirstLineProbes(Map<String, List<String>> hits, Map<String, List<String>> methodBodies) {
        methodBodies.forEach((key, body) -> {
            String joined = String.join("\n", body);
            if (RAW_SOURCE_PRODUCER.matcher(joined).find()
                && joined.contains(".lines()")
                && joined.contains(".findFirst()")) {
                hits.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add("raw-source first-line probe: rawWithoutOwnComment(...).lines().findFirst()");
            }
        });
    }

    /**
     * A statement keyword that opens a line the method-declaration regex would otherwise misread as a signature (e.g.
     * {@code return simpleHelper(...)} looks like a {@code simpleHelper} declaration if not gated out first).
     */
    private static final Pattern STATEMENT_START =
        Pattern.compile("^(return|if|for|while|switch|catch|throw|else|do|try|assert|yield|break|continue|new)\\b");

    /**
     * Whether a code line begins as a method declaration rather than a statement. Gates the declaration regex so a
     * {@code return name(...)} / {@code if (...)} / {@code new Type(...)} line is not mistaken for the enclosing method.
     */
    private static boolean looksLikeMethodDeclarationStart(String stripped) {
        return !STATEMENT_START.matcher(stripped).find();
    }

    private static boolean isAestheticLineCompare(String line) {
        return WAS_MULTILINE.matcher(line).find()
            || CROSS_RANGE_LINE_COMPARE.matcher(line).find()
            || RANGE_LINE_VS_LINE_LOCAL.matcher(line).find();
    }

    /**
     * A {@code .contains("\n")} newline probe over non-compact text — "did the author spread this across source lines?".
     * A {@code .contains("\n")} on {@code compact}-derived text is a width/structure gate over stable compact text (not
     * the author's source shape) and is not a preservation read, so it is not matched.
     */
    private static boolean isSourceMultilineNewlineProbe(String line) {
        if (!CONTAINS_NEWLINE.matcher(line).find()) {
            return false;
        }
        String beforeProbe = line.substring(0, line.indexOf(".contains(\"\\n\")"));
        return !beforeProbe.contains("compact");
    }

    /** Blank lines and comment/javadoc lines carry no code and are skipped entirely. */
    private static boolean isCommentOrBlank(String stripped) {
        return stripped.isEmpty()
            || stripped.startsWith("*")
            || stripped.startsWith("//")
            || stripped.startsWith("/*");
    }

    /**
     * A continuation line that starts a chained call or an operator/closer, so it is never mistaken for a method
     * declaration when tracking the enclosing method — but is still scanned for the aesthetic read, which typically
     * lives on exactly such a line.
     */
    private static boolean isContinuationLine(String stripped) {
        return stripped.startsWith(".")
            || stripped.startsWith("&&")
            || stripped.startsWith("||")
            || stripped.startsWith("?")
            || stripped.startsWith(":")
            || stripped.startsWith(")")
            || stripped.startsWith("}");
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
