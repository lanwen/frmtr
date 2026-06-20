package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Output-level comment-presence diagnostic (investigation {@code inv/comment-presence}).
 *
 * <p><strong>Why this exists.</strong> {@code FormatterGuardrails.assertAllCommentsAccounted} flags a comment when it
 * was not <em>registered</em> in the formatter's claimed / raw-accounted sets. That registration is keyed on JavaParser
 * {@link Comment} object identity, so it is a <em>proxy</em> for "did the comment's text reach the output", not a
 * measurement of it: a comment rendered through a path that never records accounting, or recorded under a different
 * comment identity, is flagged even though its text is present in the formatted output. The guardrail therefore
 * <em>over-reports</em> data loss.
 *
 * <p>This diagnostic answers the real question directly: it parses the INPUT and the {@link Frmtr#format formatted
 * OUTPUT} with the same parser configuration the formatter uses, extracts every comment's normalized content as a
 * <em>multiset</em>, and reports any input comment content whose multiplicity in the output is lower than in the input.
 * Only a genuine multiplicity drop counts as real comment-data-loss; re-indentation, trailing-whitespace changes, and
 * block-comment re-shaping are normalized away so they never read as a drop.
 *
 * <p><strong>This test never fails the build.</strong> Its job is to enumerate and print, not to assert away findings.
 * The drop inventory is accumulated across the two parameterized passes (golden fixtures with their own options;
 * collapsed/expanded perturbations with default options, matching {@code IdempotencePropertyTest}) and printed by
 * {@link #printReport()}. The one assertion is a positive control on the comparator itself (see
 * {@link #comparatorReportsNoDropForKnownAccountingGap}); the per-case passes never assert on the drops they find.
 */
final class CommentPresenceDiagnosticTest {

    // Accumulators shared across the two parameterized passes; drained by printReport() in @AfterAll.
    private static final Map<String, List<Drop>> GOLDEN_DROPS = new TreeMap<>();
    private static final Map<String, List<Drop>> PERTURBED_DROPS = new TreeMap<>();
    private static final List<String> GOLDEN_EVALUATED = new ArrayList<>();
    private static final List<String> PERTURBED_EVALUATED = new ArrayList<>();

    // ---------------------------------------------------------------------------------------------------------------
    // Pass 1 — golden fixtures, each with its own variant options (default or sidecar), verbatim input.
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ResourceFixtureSource(glob = "format/**/input.java")
    void goldenFixtureCommentPresence(FormatFixture fixture) {
        // Sanity-check the comparator on a case known to be an accounting gap (text present): zero drops expected.
        if (fixture.name().equals("unnamed-variables-patterns @ default")) {
            assertThat(realDrops(fixture.source(), fixture.options()))
                    .as("comparator sanity: unnamed-variables-patterns @ default is a known accounting gap"
                        + " (comment text present in output), so the output-level comparator must report zero drops")
                    .isEmpty();
        }
        if (!parses(fixture.source(), fixture.options())) {
            return; // RECOVER-only inputs: AST/comment comparison is ill-defined, skip (surfaced by count below).
        }
        GOLDEN_EVALUATED.add(fixture.name());
        List<Drop> drops = safeRealDrops(fixture.name(), fixture.source(), fixture.options());
        if (!drops.isEmpty()) {
            GOLDEN_DROPS.put(fixture.name(), drops);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Pass 2 — collapsed / expanded perturbations, default options (mirrors IdempotencePropertyTest exactly).
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("perturbedCorpus")
    void perturbedFixtureCommentPresence(String name, String perturbedSource) {
        FormatterOptions options = FormatterOptions.defaults();
        PERTURBED_EVALUATED.add(name);
        List<Drop> drops = safeRealDrops(name, perturbedSource, options);
        if (!drops.isEmpty()) {
            PERTURBED_DROPS.put(name, drops);
        }
    }

    /**
     * Two parse-preserving whitespace perturbations of each golden fixture input, default options — the same corpus
     * {@code IdempotencePropertyTest.perturbedCorpus} feeds, so the cases here line up 1:1 with the ones the guardrail
     * flagged. A perturbation that fails to parse under default options is dropped (it is not a comparable case).
     */
    static Stream<Arguments> perturbedCorpus() {
        FormatterOptions defaults = FormatterOptions.defaults();
        List<Arguments> arguments = new ArrayList<>();
        for (FixtureInput fixture : ResourceFixtureSource.Provider.inputs("format/**/input.java")) {
            for (Shape shape : Shape.values()) {
                String perturbed = perturb(fixture.source(), shape);
                String shapeName = shape == Shape.COLLAPSE ? "collapsed" : "expanded";
                String name = fixture.name() + " @ " + shapeName;
                if (perturbed != null && parses(perturbed, defaults)) {
                    arguments.add(Arguments.of(name, perturbed));
                }
            }
        }
        return arguments.stream();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Report
    // ---------------------------------------------------------------------------------------------------------------

    @AfterAll
    static void printReport() {
        System.out.println("\n================ COMMENT-PRESENCE DIAGNOSTIC ================");
        System.out.println("golden cases evaluated:    " + GOLDEN_EVALUATED.size());
        System.out.println("perturbed cases evaluated: " + PERTURBED_EVALUATED.size());

        System.out.println("\n---- REAL DROPS: golden fixtures (verbatim input, fixture's own options) ----");
        if (GOLDEN_DROPS.isEmpty()) {
            System.out.println("  (none)");
        }
        GOLDEN_DROPS.forEach((name, drops) -> {
            System.out.println("  FIXTURE " + name);
            drops.forEach(drop -> System.out.println("      DROP " + drop));
        });

        System.out.println("\n---- REAL DROPS: perturbations (collapsed/expanded, default options) ----");
        if (PERTURBED_DROPS.isEmpty()) {
            System.out.println("  (none)");
        }
        PERTURBED_DROPS.forEach((name, drops) -> {
            System.out.println("  PERTURBED " + name);
            drops.forEach(drop -> System.out.println("      DROP " + drop));
        });

        System.out.println("\n---- SUMMARY (grep-friendly) ----");
        System.out.println("REALDROP-GOLDEN-COUNT " + GOLDEN_DROPS.size());
        System.out.println("REALDROP-PERTURBED-COUNT " + PERTURBED_DROPS.size());
        GOLDEN_DROPS.keySet().forEach(key -> System.out.println("REALDROP-GOLDEN " + key));
        PERTURBED_DROPS.keySet().forEach(key -> System.out.println("REALDROP-PERTURBED " + key));
        System.out.println("============== END COMMENT-PRESENCE DIAGNOSTIC ==============\n");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Comparator core
    // ---------------------------------------------------------------------------------------------------------------

    /** A single dropped comment content together with the input locations it appeared at. */
    record Drop(String content, int inputCount, int outputCount, List<String> inputLocations) {
        @Override
        public String toString() {
            String oneLine = content.replace("\n", "\\n");
            if (oneLine.length() > 140) {
                oneLine = oneLine.substring(0, 137) + "...";
            }
            return "[in=" + inputCount + " out=" + outputCount + " @" + inputLocations + "] \"" + oneLine + "\"";
        }
    }

    /**
     * Returns the comment contents whose multiplicity in the output is strictly lower than in the input — i.e. genuine
     * comment-data-loss. Empty when no content is lost. Normalization (see {@link #normalizeRawComment}) makes
     * re-indentation / trailing-whitespace / block re-shaping invisible, so only genuine absence counts.
     *
     * <p><strong>The lexer token stream is the ground truth for presence.</strong> The drop multiset is computed by
     * counting comment <em>tokens</em> directly from JavaParser's lexer ({@link #lexerCommentCounts}) on the input and
     * on the formatted output. A lexer count cannot phantom-drop or phantom-gain: it counts the actual {@code //} and
     * {@code /* *}{@code /} tokens present in the source bytes. This deliberately does <em>not</em> use
     * {@code getAllContainedComments()} as the primary signal, because that attributed-comment traversal has the same
     * weakness the guardrail does — it can list one {@code Comment} instance twice (manufacturing a phantom drop after
     * reflow) and can omit a comment that survives in the text but attaches where the traversal does not reach (e.g. a
     * leading comment between a {@code package} declaration and the first type, which is present in the output yet absent
     * from {@code getAllContainedComments()}). Both failure modes were observed and would corrupt an AST-primary count.
     *
     * <p>The AST is still parsed, but only to attach best-effort <em>input locations</em> to each dropped content for the
     * report; if the AST cannot locate a dropped content (the omitted-orphan case), the drop is still reported, with an
     * empty location list. A {@code [XCHECK]} line is printed whenever the AST-derived drop magnitude disagrees with the
     * authoritative lexer magnitude, so the divergence between the two witnesses stays visible rather than hidden.
     */
    static List<Drop> realDrops(String source, FormatterOptions options) {
        String formatted = Frmtr.format(source, options);

        Map<String, Integer> lexerInput = lexerCommentCounts(source);
        Map<String, Integer> lexerOutput = lexerCommentCounts(formatted);

        // Best-effort AST locations for reporting only; never used to decide whether something dropped.
        Map<String, List<String>> inputLocations = astLocationsByContent(source, options);
        Map<String, Integer> astInput = astCommentCounts(source, options);
        Map<String, Integer> astOutput = astCommentCounts(formatted, options);

        List<Drop> drops = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : lexerInput.entrySet()) {
            String content = entry.getKey();
            int in = entry.getValue();
            int out = lexerOutput.getOrDefault(content, 0);
            int astDrop = astInput.getOrDefault(content, 0) - astOutput.getOrDefault(content, 0);
            if (out < in) {
                drops.add(new Drop(content, in, out, inputLocations.getOrDefault(content, List.of())));
                if (astDrop != in - out) {
                    // AST UNDER-reports a real drop (e.g. an omitted file-leading orphan the traversal never reaches).
                    System.out.println("  [XCHECK] AST under-reports \"" + content.replace("\n", "\\n")
                        + "\": lexer-drop=" + (in - out) + " ast-drop=" + astDrop
                        + " — AST traversal artifact, lexer authoritative (this case IS a real drop)");
                }
            } else if (astDrop > 0) {
                // AST OVER-reports: it counts a drop the lexer (ground truth) refutes — the text is present. This is the
                // guardrail's own failure mode reproduced at the AST level; the case is correctly NOT recorded as a drop.
                System.out.println("  [XCHECK] AST over-reports (NOT a real drop) \"" + content.replace("\n", "\\n")
                    + "\": ast-drop=" + astDrop + " but lexer in=" + in + " out=" + out
                    + " (text present) — accounting gap, excluded from drops");
            }
        }
        return drops;
    }

    private static Map<String, Integer> astCommentCounts(String source, FormatterOptions options) {
        return multiset(commentEntries(parse(source, options)));
    }

    private static Map<String, List<String>> astLocationsByContent(String source, FormatterOptions options) {
        Map<String, List<String>> locations = new LinkedHashMap<>();
        for (CommentEntry entry : commentEntries(parse(source, options))) {
            locations.computeIfAbsent(entry.content(), key -> new ArrayList<>()).add(entry.location());
        }
        return locations;
    }

    /**
     * Counts each comment token's normalized content directly from the lexer stream, ignoring the attributed-comment
     * AST entirely. This is the authoritative presence witness used by {@link #realDrops}: a lexer count cannot
     * phantom-drop or phantom-gain, because it counts the actual comment tokens in the source bytes.
     */
    private static Map<String, Integer> lexerCommentCounts(String source) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        TokenRange tokens = parseResultBleedingEdge(source).getResult()
                .flatMap(CompilationUnit::getTokenRange)
                .orElse(null);
        if (tokens == null) {
            return counts;
        }
        for (JavaToken token : tokens) {
            if (token.getCategory().isComment()) {
                counts.merge(normalizeRawComment(token.getText()), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Normalizes a raw comment token's text the same way {@link #normalize} normalizes an attributed comment. */
    private static String normalizeRawComment(String raw) {
        String text = raw.strip();
        if (text.startsWith("//")) {
            return text.substring(2).strip();
        }
        String inner = text;
        if (inner.startsWith("/*")) {
            inner = inner.substring(2);
        }
        if (inner.endsWith("*/")) {
            inner = inner.substring(0, inner.length() - 2);
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : inner.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.startsWith("*")) {
                line = line.substring(1).strip();
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    private static List<Drop> safeRealDrops(String name, String source, FormatterOptions options) {
        try {
            return realDrops(source, options);
        } catch (RuntimeException exception) {
            System.out.println("  [WARN] could not evaluate " + name + ": " + exception.getMessage());
            return List.of();
        }
    }

    private record CommentEntry(String content, String location) {}

    private static List<CommentEntry> commentEntries(CompilationUnit unit) {
        List<CommentEntry> entries = new ArrayList<>();
        // Dedupe by JavaParser object identity. getAllContainedComments() can list the SAME Comment instance twice when
        // it is reachable through more than one traversal path (verified: an enum-constant trailing comment appears twice
        // in the input tree but once in the reflowed output tree). That double-listing is a parser-traversal artifact,
        // not a second copy of the text, so counting it would manufacture phantom drops. Two DISTINCT Comment instances
        // carrying the same text (e.g. two separate `/*dead code*/` comments) are kept separate — only reference-equal
        // duplicates collapse — so genuine multiplicity drops are preserved.
        Set<Comment> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Comment comment : unit.getAllContainedComments()) {
            if (!seen.add(comment)) {
                continue;
            }
            String location = comment.getRange()
                    .map(range -> "L" + range.begin.line + ":C" + range.begin.column)
                    .orElse("L?");
            entries.add(new CommentEntry(normalize(comment), location));
        }
        return entries;
    }

    private static Map<String, Integer> multiset(List<CommentEntry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CommentEntry entry : entries) {
            counts.merge(entry.content(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Normalizes a comment's content so legitimate formatter re-shaping does not read as a difference:
     *
     * <ul>
     *   <li>line comment: text after {@code //}, {@link String#strip stripped};
     *   <li>block / javadoc: inner text split into lines, each line's leading whitespace and a single optional leading
     *       {@code *} removed and trailing whitespace stripped, blank lines dropped, non-blank lines joined with
     *       {@code \n}. Dropping blank interior lines is deliberate — the formatter may add/remove blank lines inside a
     *       block body without losing author text, and we only want to detect genuine absence of words.
     * </ul>
     */
    private static String normalize(Comment comment) {
        String content = comment.getContent();
        if (comment.isLineComment()) {
            return content.strip();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.startsWith("*")) {
                line = line.substring(1).strip();
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Parsing — match the formatter's configuration (FormatterGuardrails / JavaFormatter).
    // ---------------------------------------------------------------------------------------------------------------

    private static CompilationUnit parse(String source, FormatterOptions options) {
        ParseResult<CompilationUnit> result = newParser(options)
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
        return result.getResult()
                .orElseThrow(() -> new IllegalStateException("diagnostic parse failed: " + result.getProblems()));
    }

    private static boolean parses(String source, FormatterOptions options) {
        return newParser(options).parse(ParseStart.COMPILATION_UNIT, Providers.provider(source)).isSuccessful();
    }

    private static JavaParser newParser(FormatterOptions options) {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(languageLevel(options))
                .setStoreTokens(true)
                .setAttributeComments(true));
    }

    /** Mirror JavaFormatter.javaParserLanguageLevel so the diagnostic parse matches the formatter's parse exactly. */
    private static ParserConfiguration.LanguageLevel languageLevel(FormatterOptions options) {
        return switch (options.javaLanguageLevel()) {
            case UNSET -> null;
            case LATEST_AVAILABLE -> ParserConfiguration.LanguageLevel.BLEEDING_EDGE;
            case JAVA_8 -> ParserConfiguration.LanguageLevel.JAVA_8;
            case JAVA_9 -> ParserConfiguration.LanguageLevel.JAVA_9;
            case JAVA_10 -> ParserConfiguration.LanguageLevel.JAVA_10;
            case JAVA_11 -> ParserConfiguration.LanguageLevel.JAVA_11;
            case JAVA_12 -> ParserConfiguration.LanguageLevel.JAVA_12;
            case JAVA_13 -> ParserConfiguration.LanguageLevel.JAVA_13;
            case JAVA_14 -> ParserConfiguration.LanguageLevel.JAVA_14;
            case JAVA_15 -> ParserConfiguration.LanguageLevel.JAVA_15;
            case JAVA_16 -> ParserConfiguration.LanguageLevel.JAVA_16;
            case JAVA_17 -> ParserConfiguration.LanguageLevel.JAVA_17;
            case JAVA_18 -> ParserConfiguration.LanguageLevel.JAVA_18;
            case JAVA_19 -> ParserConfiguration.LanguageLevel.JAVA_19;
            case JAVA_20 -> ParserConfiguration.LanguageLevel.JAVA_20;
            case JAVA_21 -> ParserConfiguration.LanguageLevel.JAVA_21;
            case JAVA_22 -> ParserConfiguration.LanguageLevel.JAVA_22;
            case JAVA_23 -> ParserConfiguration.LanguageLevel.JAVA_23;
            case JAVA_24 -> ParserConfiguration.LanguageLevel.JAVA_24;
            case JAVA_25 -> ParserConfiguration.LanguageLevel.JAVA_25;
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Parse-preserving whitespace perturbation — mirrors IdempotencePropertyTest.perturb so perturbed inputs match the
    // ones the guardrail flagged. The source of truth remains that test; this is a local copy for the diagnostic.
    // ---------------------------------------------------------------------------------------------------------------

    private enum Shape {
        COLLAPSE,
        EXPAND,
    }

    private static String perturb(String source, Shape shape) {
        TokenRange tokens = parseResultBleedingEdge(source).getResult()
                .flatMap(CompilationUnit::getTokenRange)
                .orElse(null);
        if (tokens == null) {
            return null;
        }
        List<JavaToken> tokenList = new ArrayList<>();
        for (JavaToken token : tokens) {
            tokenList.add(token);
        }
        StringBuilder builder = new StringBuilder(source.length() * 2);
        JavaToken previous = null;
        for (int index = 0; index < tokenList.size(); index++) {
            JavaToken token = tokenList.get(index);
            if (token.getCategory().isWhitespace()) {
                builder.append(whitespaceFor(shape, previous, nextNonWhitespace(tokenList, index)));
            } else {
                builder.append(token.getText());
                previous = token;
            }
        }
        return builder.toString();
    }

    private static ParseResult<CompilationUnit> parseResultBleedingEdge(String source) {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(true)
                .setAttributeComments(true))
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
    }

    private static JavaToken nextNonWhitespace(List<JavaToken> tokens, int from) {
        for (int index = from + 1; index < tokens.size(); index++) {
            JavaToken token = tokens.get(index);
            if (!token.getCategory().isWhitespace()) {
                return token;
            }
        }
        return null;
    }

    private static String whitespaceFor(Shape shape, JavaToken previous, JavaToken next) {
        boolean afterLineComment = previous != null
                && previous.getCategory().isComment()
                && previous.getText().startsWith("//");
        boolean beforePragmaComment = next != null && next.getCategory().isComment() && isLinePragma(next.getText());
        if (afterLineComment || beforePragmaComment) {
            return shape == Shape.EXPAND ? "\n\n" : "\n";
        }
        return shape == Shape.EXPAND ? "  \n  " : " ";
    }

    private static boolean isLinePragma(String commentText) {
        return commentText.contains("@formatter:off")
                || commentText.contains("@formatter:on")
                || commentText.contains("frmtr-ignore");
    }
}
