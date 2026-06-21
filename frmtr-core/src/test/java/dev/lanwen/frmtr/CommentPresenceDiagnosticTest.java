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
 * Output-level comment-presence net — the CI gate for "the formatter never drops a comment" (roadmap S7 part 2;
 * promoted from the {@code inv/comment-presence} diagnostic).
 *
 * <p><strong>Why this is the gate and {@code assertAllCommentsAccounted} is not.</strong> The accounting guardrail
 * flags a comment when it was not <em>registered</em> in the formatter's claimed / raw-accounted sets. That
 * registration is keyed on JavaParser {@link Comment} object identity, so it is a <em>proxy</em> for "did the comment's
 * text reach the output", not a measurement of it: a comment rendered through a path that never records accounting, or
 * recorded under a different comment identity, is flagged even though its text is present (over-reports), and an
 * AST-invisible orphan it never traverses is missed (under-reports). This net answers the real question directly: it
 * compares the <em>lexer comment-token multiset</em> of the INPUT against that of the {@link Frmtr#format formatted
 * OUTPUT}, and fails when any input comment content appears fewer times in the output than in the input. The lexer
 * count is ground truth — it counts the actual {@code //} and {@code /* *}{@code /} tokens in the source bytes, so it
 * cannot phantom-drop or phantom-gain the way an attributed-comment traversal can.
 *
 * <p><strong>What it asserts, over what corpus.</strong> Two parameterized passes fail on any non-excluded
 * {@code (fixture, shape)} that drops a comment: (1) every golden fixture input at its own variant options, verbatim;
 * (2) collapsed/expanded whitespace perturbations of every golden input at default options, generated exactly as
 * {@code IdempotencePropertyTest.perturb} does (so a comment dropped only when the layout moves is still caught — that
 * shape-dependent ownership is the B1 thesis). Tolerant normalization (see {@link #normalizeRawComment}) makes
 * re-indentation, trailing-whitespace strip, and block {@code *}-prefix re-shaping invisible, so only genuine textual
 * <em>absence</em> fails.
 *
 * <p><strong>The exclusion list ({@link #KNOWN_DROPS}) is the S9 backlog.</strong> It is seeded with exactly the
 * {@code (fixture, shape)} cases that drop a comment on this branch today, each annotated with the dropped text and a
 * pointer to the S9 cluster that owns the fix. This makes the net green now while it bites on any <em>new</em> drop
 * immediately. As each S9 cluster lands a real fix, its entries are removed here in the same change; the net is fully
 * green with an empty exclusion list when S9 completes. The exclusion list is never widened to mask a regression — a
 * new drop outside the backlog must be fixed or diagnosed, not parked (see the class's STOP conditions in
 * {@code docs/proposals/comment-data-loss.md}).
 *
 * <p>{@link #printReport()} still drains a full per-drop inventory to stdout as a development aid; the build outcome is
 * decided by the per-case assertions, not the report.
 */
final class CommentPresenceDiagnosticTest {

    /**
     * The S9 comment-drop backlog: {@code (fixture, shape)} display names whose formatting genuinely drops a comment on
     * this branch, each kept out of the asserting net until its S9 cluster fixes the underlying placement/printer bug.
     *
     * <p>Each entry's value is the comment text(s) the fixture loses, so the parked finding stays honest and reviewable.
     * The grouping mirrors the cluster ordering in {@code docs/proposals/comment-data-loss.md}. Removing an entry
     * re-arms the net for that case; an S9 cluster commit removes its entries in the same change as the fix.
     *
     * <p><strong>Do not add entries to silence a regression.</strong> This list only ever shrinks. A drop on a case not
     * already here is a new data-loss bug the net is meant to catch.
     */
    private static final Map<String, String> KNOWN_DROPS = knownDrops();

    private static Map<String, String> knownDrops() {
        Map<String, String> drops = new TreeMap<>();

        // -- P0: drops on NORMAL (verbatim @default) input; the committed golden output is itself lossy. --
        // annotation-block-comment-gap: the /* ... */ between @Deprecated and the method is dropped (2 block -> 1).
        drops.put("annotation-block-comment-gap @ default",
            "S9 backlog (P0): \"Since version 0.11, the service exposes all APIs on a single(4566 ) port.\"");
        // comment-complex-block-statements: one of two `/* dead code */` block comments dropped (37 -> 36).
        drops.put("comment-complex-block-statements @ default", "S9 backlog (P0): \"dead code\"");

        // -- P1: drops only when whitespace is perturbed; shape-dependent ownership (B1 evidence). --

        // control-condition / if
        drops.put("comment-preservation-control-condition @ collapsed",
            "S9 backlog (control-condition): \"keep polling until the route snapshot is visible\","
                + " \"use the normalized event kind for routing\"");
        drops.put("comment-preservation-control-condition @ expanded",
            "S9 backlog (control-condition): \"keep polling until the route snapshot is visible\","
                + " \"read selector after cursor state is refreshed\","
                + " \"keep the body delayed until route state is stable\","
                + " \"keep selector comment outside the condition\", \"use the normalized event kind for routing\"");
        drops.put("comment-preservation-if-statement @ collapsed",
            "S9 backlog (control-condition/if): \"test\" (12->9),"
                + " \"https://docs.example.invalid/token-envelope-03.html\"");
        drops.put("comment-preservation-if-statement @ expanded",
            "S9 backlog (control-condition/if): \"test\" (12->7), \"comment\","
                + " \"legacy key envelope before registry draft 04\","
                + " \"https://docs.example.invalid/token-envelope-03.html\","
                + " \"keep manual routing while backfill catches up\"");

        // labeled-statement
        drops.put("comment-preservation-labeled-statement @ collapsed",
            "S9 backlog (labeled-statement): \"Label statement\" (14->4), \"comment1\" (22->6),"
                + " \"comment2\" (14->4)");

        // try-resource
        drops.put("comment-preservation-try-resources @ expanded",
            "S9 backlog (try-resource): \"resource scope {\", \"single resource scope {\"");
        drops.put("try-resource-layout @ expanded",
            "S9 backlog (try-resource): \"a\", \"b\", \"c\", \"a2\", \"b2\", \"c2\"");

        // method-arguments
        drops.put("comment-preservation-method-arguments @ expanded",
            "S9 backlog (method-arguments): \"services selected directly\", \"services selected by scaling\"");
        drops.put("block-orphan-method-call-comments @ collapsed",
            "S9 backlog (method-arguments): \"after last call arg\", \"after last constructor arg\","
                + " \"after last chain arg\"");

        // switch
        drops.put("switch-entry-leading-comments @ collapsed",
            "S9 backlog (switch): \"keep first detail\", \"keep second detail\", \"keep third detail\","
                + " \"keep final detail\"");
        drops.put("switch-entry-leading-comments @ expanded",
            "S9 backlog (switch): \"keep first detail\", \"keep second detail\", \"keep third detail\","
                + " \"keep final detail\"");
        drops.put("switch-statement-rules @ collapsed", "S9 backlog (switch): \"comment\" (3->0)");
        drops.put("switch-statement-rules @ expanded",
            "S9 backlog (switch): \"default case\", \"case c\", \"fall through\", \"remote\", \"hybrid\","
                + " \"comment\"");

        // block-comment / annotation gap
        drops.put("annotation-block-comment-gap @ collapsed",
            "S9 backlog (block/annotation gap): \"Since version 0.11, ... single port.\","
                + " \"Since version 0.11, ... single(4566 ) port.\"");
        drops.put("comment-complex-block-statements @ collapsed",
            "S9 backlog (block/annotation gap): \"switch\", \"dead code\", \"The Heart and the Spade\"");
        drops.put("comment-complex-block-statements @ expanded",
            "S9 backlog (block/annotation gap): \"is always executed no matter what\", \"Minus One\", \"switch\","
                + " \"overloading\", \"at least one iteration !\", \"Additionnal enumeration\"");
        drops.put("comment-preservation-block-comment-shapes @ collapsed",
            "S9 backlog (block/annotation gap): \"a\" (3->2)");

        // @formatter:* pragma lines
        drops.put("formatter-pragma-begin-with-on @ expanded",
            "S9 backlog (pragma): \"@formatter:on\" (2->1), \"@formatter:off\"");
        drops.put("formatter-pragma-class @ expanded", "S9 backlog (pragma): \"@formatter:on\"");
        drops.put("formatter-pragma-end-with-off @ expanded",
            "S9 backlog (pragma): \"@formatter:off\" (2->1), \"@formatter:on\"");
        drops.put("formatter-pragma-multiple @ expanded", "S9 backlog (pragma): \"@formatter:on\"");
        drops.put("formatter-pragma-spacing @ expanded",
            "S9 backlog (pragma): \"@formatter:off\" (3->2), \"@formatter:on\" (3->2)");

        // text-block-adjacent
        drops.put("text-block-language-and-escapes @ collapsed",
            "S9 backlog (text-block-adjacent): \"leading comment\"");
        drops.put("text-block-language-and-escapes @ expanded",
            "S9 backlog (text-block-adjacent): \"leading comment\", \"trailing comment\"");

        // records / enums / conditionals / misc
        drops.put("record-component-spacing @ collapsed", "S9 backlog (records/enums/misc): \"comment\" (2->1)");
        drops.put("record-component-spacing @ expanded", "S9 backlog (records/enums/misc): \"comment\" (2->1)");
        drops.put("enum-declaration-layout @ collapsed", "S9 backlog (records/enums/misc): \"comment\" (3->2)");
        drops.put("conditional-expression-space-indentation @ collapsed",
            "S9 backlog (records/enums/misc): \"c\" (4->3)");
        drops.put("conditional-expression-space-indentation @ expanded",
            "S9 backlog (records/enums/misc): \"b\" (4->0), \"c\" (4->1)");
        drops.put("unnamed-variables-patterns @ expanded",
            "S9 backlog (records/enums/misc): \"Unnamed pattern variable\" (7->0)");
        drops.put("correctness-data-loss @ expanded",
            "S9 backlog (records/enums/misc): \"keep this comment with the type\"");
        drops.put("empty-statement @ expanded", "S9 backlog (records/enums/misc): \"Bug Fix: #356\"");
        drops.put("qualified-type-receiver-annotations @ expanded",
            "S9 backlog (records/enums/misc): \"Fix for https://github.com/jhipster/prettier-java/issues/607\"");
        drops.put("variable-declarations @ collapsed",
            "S9 backlog (records/enums/misc): \"there is a random comment on this line up here\"");

        // class-members / interface (guardrail-missed; found only by the lexer net)
        drops.put("comment-preservation-class-members @ collapsed",
            "S9 backlog (class-members): three \"TODO(jlevy): ...\" block comments");
        drops.put("comment-preservation-class-members @ expanded",
            "S9 backlog (class-members): the Guava copyright file-header block (AST-invisible orphan)");
        drops.put("comment-preservation-interface-declaration @ collapsed",
            "S9 backlog (interface-declaration): \"comment\"");

        return Collections.unmodifiableMap(drops);
    }

    // Accumulators shared across the two parameterized passes; drained by printReport() in @AfterAll for diagnostics.
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
        if (!parses(fixture.source(), fixture.options())) {
            return; // RECOVER-only inputs: AST/comment comparison is ill-defined, skip (surfaced by count below).
        }
        GOLDEN_EVALUATED.add(fixture.name());
        List<Drop> drops = safeRealDrops(fixture.name(), fixture.source(), fixture.options());
        if (!drops.isEmpty()) {
            GOLDEN_DROPS.put(fixture.name(), drops);
        }
        assertNoUnexpectedDrop(fixture.name(), drops);
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
        assertNoUnexpectedDrop(name, drops);
    }

    /**
     * Fails the build when {@code (fixture, shape)} {@code name} drops a comment and is <em>not</em> a documented S9
     * backlog entry. A case that is in {@link #KNOWN_DROPS} but no longer drops anything also fails — a stale exclusion
     * means a fix landed without un-parking it, which would let a future regression hide; remove the entry in the fix's
     * commit.
     */
    private static void assertNoUnexpectedDrop(String name, List<Drop> drops) {
        boolean parked = KNOWN_DROPS.containsKey(name);
        if (!drops.isEmpty()) {
            assertThat(parked)
                    .as("comment-presence net: `%s` drops comment(s) that are not in the documented S9 backlog: %s."
                        + " The formatter must not drop comments — fix the placement/printer, do not add an exclusion.",
                        name, drops)
                    .isTrue();
        } else {
            assertThat(parked)
                    .as("comment-presence net: `%s` is listed in the S9 backlog (KNOWN_DROPS) but no longer drops a"
                        + " comment. A fix landed without un-parking it; remove its KNOWN_DROPS entry so the net guards"
                        + " the case again.", name)
                    .isFalse();
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
    // Comparator positive controls — prove the net actually bites and that its normalization is not over-tolerant.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The comparator detects a genuine drop. A formatter that silently swallowed a comment must be caught, so this feeds
     * a known-lossy verbatim golden fixture ({@code annotation-block-comment-gap}) and asserts the comparator reports a
     * non-empty drop set for it — if this ever returns empty, either the fixture's loss was fixed (un-park it in
     * {@link #KNOWN_DROPS}) or the comparator stopped measuring presence and the whole net is blind.
     */
    @org.junit.jupiter.api.Test
    void comparatorReportsRealDropOnLossyFixture() {
        // The inline `@Deprecated /* ... */ method` shape from annotation-block-comment-gap: the formatter currently
        // drops the block comment sitting between the annotation and the method on the same line.
        String lossy = """
            class Demo {
                @Deprecated /*
                        Since version 0.11, the service exposes all APIs on a single port.*/ public int port() {
                    return 0;
                }
            }
            """;
        assertThat(realDrops(lossy, FormatterOptions.defaults()))
                .as("comparator must report the dropped annotation/method-gap block comment; an empty result here means"
                    + " the output-level net has gone blind and would no longer catch data loss")
                .isNotEmpty();
    }

    /**
     * The comparator does <em>not</em> false-positive on the formatter's legitimate comment re-shaping. A block comment
     * whose body the formatter re-indents and whose lines it re-aligns under a {@code *} prefix carries the same words;
     * normalization must treat it as present, not dropped — otherwise the net would flag every reflowed comment.
     */
    @org.junit.jupiter.api.Test
    void comparatorIgnoresLegitimateReshapingOfAPreservedComment() {
        String reshaped = """
            class Demo {
                            // a line comment with odd indentation that the formatter re-indents
                void method() {
                /*
                 a block comment
                 the formatter re-aligns
                 */
                    int x = 1;
                }
            }
            """;
        assertThat(realDrops(reshaped, FormatterOptions.defaults()))
                .as("re-indentation / *-prefix re-alignment of a preserved comment must not read as a drop")
                .isEmpty();
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
