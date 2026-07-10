package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Extends the closed-set governance of {@link SourceShapeException} / {@link SourceShapeExceptionGovernanceTest} down to
 * the <em>inline</em> tier of source-shape reads.
 *
 * <p>{@link SourceShapeExceptionGovernanceTest} pins the reads that go through {@link SourceShapePolicy}, but the D3 flip
 * uncovered a second tier that escaped that ratchet: printers that hand-roll an aesthetic line-break decision inline by
 * comparing two token {@code getRange()} line numbers — {@code range.begin.line < range.end.line} ("was this node
 * multiline in the source?"), or {@code X.begin.line == / < / > Y.begin.line}/{@code .end.line} ("did Y start on the same
 * / a later / an earlier line than X?"). These are "preserve the author's line breaks" reads exactly like the catalogued
 * {@link SourceShapePolicy} ones, and are just as fragile under reprint-by-default, but because they never touch the
 * policy they were invisible to the governance ratchet.
 *
 * <p>This guard closes that gap. It scans the Java formatter sources for the inline line-comparison spelling and fails if
 * it finds one that is not in the {@link #ALLOWLISTED_AESTHETIC_READS} residual set, keyed by
 * {@code SimpleClassName#method} so the entries survive line moves. The seeded allowlist is the set of aesthetic inline
 * reads still live after the D3 flip follow-ups; each is deferred on the same foundation (the enclosing-column /
 * {@code leftEdgePrefix} gap) that blocks the remaining {@link SourceShapePolicy} retirements, and is tracked as a D3
 * flip follow-up to retire when that foundation lands. A NEW inline read that is neither allowlisted nor one of the
 * excluded legitimate categories trips this test, forcing the author to catalogue and justify it (or retire it) — the
 * closed set is now enforced for inline reads too.
 *
 * <p>Legitimate, non-aesthetic line reads are excluded so they are not mistaken for layout preservation:
 * <ul>
 *   <li><strong>comment placement</strong> — {@link CommentIndex}, {@link JavaCommentPlacementPolicy},
 *       {@code CommentedMethodSignaturePrinter}, and any method whose name mentions a comment
 *       ({@link #COMMENT_METHOD_MARKER}) decide where a comment lands relative to its owner, not how code wraps;</li>
 *   <li><strong>source-order sorts</strong> — {@code ControlConditionPrinter} / {@code DeclarationPrefixPrinter} /
 *       {@code CallableSignaturePrinter} compare line numbers to <em>order</em> nodes/comments deterministically, which
 *       reprint reproduces;</li>
 *   <li><strong>deliberate blank lines</strong> — a method whose name mentions a blank line
 *       ({@link #BLANK_LINE_METHOD_MARKER}) reads the one-blank-line gap already owned by
 *       {@link SourceShapePolicy#hadBlankLineBetween} (blank lines collapse to a fixpoint); and</li>
 *   <li><strong>width-measurement reconstruction</strong> — a method whose name mentions width
 *       ({@link #WIDTH_METHOD_MARKER}) uses the range to reconstruct a column for a width probe, not to preserve a
 *       break.</li>
 * </ul>
 *
 * <p>This is a documented, tuned heuristic, not a parser: it matches the two aesthetic line-comparison spellings and
 * attributes each hit to its nearest enclosing method declaration. The rawSource first-line prefix probe
 * ({@code VariableInitializerLayout#sourceFirstLineKeepsChainAfterRoot}) is a sibling residual that reads shape through a
 * different mechanism ({@code rawSource...lines()}, not {@code getRange().line}); it is out of this guard's line-compare
 * scope and is tracked with the same D3 flip follow-ups.
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
     * The residual inline aesthetic source-line reads still live after the D3 flip follow-ups, keyed by
     * {@code SimpleClassName#method}. Each is a "preserve the author's line breaks" read deferred on the enclosing-column
     * / {@code leftEdgePrefix} gap. Tracked: D3 flip follow-ups — retire when the leftEdgePrefix foundation lands.
     */
    private static final Set<String> ALLOWLISTED_AESTHETIC_READS = Set.of(
        // "did the initializer start on a continuation line after the name?" (initializer.begin.line > name.end.line)
        // deferred: leftEdgePrefix gap; tracked: D3 flip follow-ups — retire when the leftEdgePrefix foundation lands.
        "VariableInitializerLayout#initializerStartsOnContinuationLine",
        // "does the method-call scope end on the name line?" (scope.end.line == name.begin.line)
        // deferred: leftEdgePrefix gap; tracked: D3 flip follow-ups — retire when the leftEdgePrefix foundation lands.
        "VariableInitializerLayout#methodCallScopeEndsOnNameLine",
        // "does the call close stay on the lambda body line?" (parent.end.line == body.end.line)
        // deferred: leftEdgePrefix gap; tracked: D3 flip follow-ups — retire when the leftEdgePrefix foundation lands.
        "ExpressionLambdaClosingLayout#callClosingStaysOnLambdaBodyLine"
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
    void everyInlineAestheticSourceLineReadIsAllowlistedOrAnExcludedLegitimateCategory() {
        Map<String, List<String>> foundOutsideAllowlist = scanAestheticReads();
        assertThat(foundOutsideAllowlist)
            .as(
                "inline aesthetic source-line reads (getRange().begin.line/.end.line comparisons that decide a line "
                + "break) must be catalogued: either add the read behind a structural rule and retire it, or — if it is "
                + "a deferred residual — add its SimpleClassName#method key to ALLOWLISTED_AESTHETIC_READS with the "
                + "deferred cause. Reprint-by-default keeps this closed set enforced for inline reads too. "
                + "Uncatalogued reads found: %s",
                foundOutsideAllowlist
            )
            .isEmpty();
    }

    @Test
    void everyAllowlistedResidualStillExistsInTheSources() {
        Set<String> present = scanAllAestheticReads().keySet();
        assertThat(present)
            .as(
                "every ALLOWLISTED_AESTHETIC_READS entry must still name a live inline read; a stale entry means the "
                + "read was retired and its allowlist line should be deleted (progress!)"
            )
            .containsAll(ALLOWLISTED_AESTHETIC_READS);
    }

    /** Aesthetic reads whose {@code SimpleClassName#method} key is neither allowlisted nor an excluded category. */
    private static Map<String, List<String>> scanAestheticReads() {
        Map<String, List<String>> hits = new TreeMap<>();
        scanAllAestheticReads().forEach((key, lines) -> {
            String method = key.substring(key.indexOf('#') + 1);
            if (ALLOWLISTED_AESTHETIC_READS.contains(key)
                || COMMENT_METHOD_MARKER.matcher(method).find()
                || BLANK_LINE_METHOD_MARKER.matcher(method).find()
                || WIDTH_METHOD_MARKER.matcher(method).find()) {
                return;
            }
            hits.put(key, lines);
        });
        return hits;
    }

    /** All aesthetic inline line-compare reads keyed by {@code SimpleClassName#method}, before allow/exclude filtering. */
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
            throw new UncheckedIOException("Could not scan Java formatter sources for inline source-line reads", exception);
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
        String enclosingMethod = "<unknown>";
        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            String raw = lines.get(lineNumber - 1);
            String stripped = raw.strip();
            if (isCommentOrBlank(stripped)) {
                continue;
            }
            // Track the enclosing method only from lines that begin a declaration — never from continuation lines (a
            // chained call / operator) or statements, so an aesthetic compare inside a `.map(range -> ...)` lambda is
            // attributed to the real method, not to `map`.
            if (!isContinuationLine(stripped) && looksLikeMethodDeclarationStart(stripped)) {
                Matcher declaration = METHOD_DECLARATION.matcher(stripped);
                if (declaration.find() && !CONTROL_KEYWORDS.contains(declaration.group(1))) {
                    enclosingMethod = declaration.group(1);
                }
            }
            // The aesthetic compare itself usually LIVES on a continuation line (`.map(range -> begin.line < ...)`), so
            // it is checked on every non-comment line regardless of continuation.
            if (isAestheticLineCompare(raw)) {
                hits.computeIfAbsent(className + "#" + enclosingMethod, key -> new java.util.ArrayList<>())
                    .add(lineNumber + ": " + stripped);
            }
        }
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

    /** Blank lines and comment/javadoc lines carry no code and are skipped entirely. */
    private static boolean isCommentOrBlank(String stripped) {
        return stripped.isEmpty()
            || stripped.startsWith("*")
            || stripped.startsWith("//")
            || stripped.startsWith("/*");
    }

    /**
     * A continuation line that starts a chained call or an operator/closer, so it is never mistaken for a method
     * declaration when tracking the enclosing method — but is still scanned for the aesthetic compare, which typically
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
