package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import dev.lanwen.frmtr.FixtureInput;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.ResourceFixtureSource;
import dev.lanwen.frmtr.SourceShapePerturbation;
import dev.lanwen.frmtr.SourceShapePerturbation.Shape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Layer 2 of the semantic-preservation safety net (roadmap B3,
 * {@code docs/proposals/semantic-preservation-safety-net.md}): a property test that broadens correctness checking
 * <em>beyond</em> the hand-authored golden fixtures.
 *
 * <p><strong>Why this adds coverage the existing checks do not.</strong> {@code FrmtrTest} already asserts per-fixture
 * idempotence and (with verify mode on) AST-equivalence — but only over inputs a human curated <em>as golden
 * outputs</em>. That proves the formatter is stable on a small set of already-well-shaped inputs. This test adds two
 * sources of breadth:
 *
 * <ul>
 *   <li><em>Mechanically perturbed fixture inputs.</em> Each golden {@code input.java} is re-shaped by two
 *       parse-preserving whitespace perturbations: <em>collapse</em> (every whitespace run shrunk to the minimum) and
 *       <em>expand</em> (every run padded with extra spaces and blank lines). The perturbation rebuilds the source from
 *       JavaParser's own token stream and rewrites <em>only</em> whitespace tokens, emitting every identifier, keyword,
 *       <strong>literal</strong> (string and text-block included), separator, operator, and <strong>comment</strong>
 *       token verbatim. The lexed token sequence is therefore identical except for whitespace amounts, so the perturbed
 *       source parses to the same AST and no literal or comment content is altered. This exercises the formatter against
 *       arbitrary-shaped valid input — input no fixture author wrote — which neither existing per-fixture check covers.
 *   <li><em>Diverse hand-written snippets not in the golden set.</em> Generics, lambdas, switch expressions, records,
 *       annotations, text blocks, varargs, enums with and without a trailing separator, sealed hierarchies, and
 *       comment-dense members — the constructs history flagged as fragile.
 * </ul>
 *
 * <p><strong>The properties asserted (and the one deliberately not).</strong> frmtr is idempotent and
 * semantics-preserving, but intentionally <em>not</em> convergent <em>to the formatting of the original</em> — it
 * preserves intentional source shape (blank lines between members, multiline-vs-single-line call shape). So this test
 * never asserts {@code format(perturbed(x)).equals(format(x))}: two differently-shaped but equivalent inputs can
 * legitimately format to different outputs, and asserting otherwise would be wrong. It <em>does</em> assert that
 * repeated formatting reaches a <em>fixed point</em> (see {@link #perturbedInputsAreSemanticsPreservingAndParseStable}).
 * Instead:
 *
 * <ul>
 *   <li>{@link #idempotentAndSemanticsPreserving} asserts <strong>strict one-pass idempotence</strong>
 *       ({@code format(format(x)).equals(format(x))}) <em>and</em> <strong>semantic preservation</strong>
 *       ({@code AstEquivalence.equivalent(parse(x), parse(format(x)))}) over the inputs a developer would actually feed
 *       the formatter: every verbatim golden fixture input and every hand-written snippet.
 *   <li>{@link #perturbedInputsAreSemanticsPreservingAndParseStable} asserts <strong>semantic preservation</strong>,
 *       <strong>parse-stability</strong> ({@code format(x)} re-parses), and <strong>eventual convergence</strong> (a
 *       fixed point within {@value #CONVERGENCE_PASSES} passes) over the perturbed inputs. It deliberately does
 *       <em>not</em> assert one-pass idempotence on perturbed inputs, because the formatter genuinely is not one-pass
 *       idempotent on arbitrarily-reshaped source: it can take a second pass (occasionally a third) to settle a
 *       width-driven wrap. Empirically most reshaped inputs reach a fixed point within a couple of passes; a few do not,
 *       and those are recorded in {@link #EXCLUDED_AS_FINDINGS} (including one non-terminating case whose output grows
 *       on every pass) rather than asserted away.
 * </ul>
 *
 * <p>Semantic preservation is asserted explicitly here (against the input's own parse tree) so the property reads as a
 * property rather than a side effect of verify mode; it is <em>also</em> enforced inside {@code Frmtr.format} by the
 * {@code dev.lanwen.frmtr.debug.verify} mode the test suite turns on, so both the explicit check and the runtime hook
 * must agree.
 *
 * <p>Generation is deterministic: the perturbations are mechanical token rewrites with no randomness, so the corpus is
 * identical on every run.
 *
 * <p><strong>Findings this property surfaced — all fixed and regression-guarded.</strong> The first run of this
 * property excluded a handful of perturbed fixture shapes because formatting them exposed a real formatter defect
 * (non-reparseable output, dropped program elements, or failure to reach a fixed point) rather than a perturbation
 * artifact. Each was a genuine bug; they have all since been fixed and {@link #EXCLUDED_AS_FINDINGS} is now empty —
 * every perturbed input is in the asserted corpus. The fixes, each pinned by a dedicated golden fixture:
 *
 * <ul>
 *   <li><em>Enum-separator / parameter-comment data loss (non-reparseable).</em> {@code correctness-data-loss},
 *       {@code enum-declaration-layout}, {@code comment-preservation-block-end-comments} (collapsed): a trailing line
 *       comment on the last enum constant swallowed the list-terminating {@code ;}/{@code ,}; an enum constant comment
 *       sharing the enclosing class's opening-brace line was mis-attributed to the brace (dropping the constant's
 *       comma); and a parameter's leading block comment was misread as empty parentheses (dropping the parens). Guarded
 *       by {@code enum-constant-trailing-comment-before-semicolon}, {@code enum-constant-comment-on-brace-line}, and
 *       {@code parameter-leading-block-comment-collapsed}.
 *   <li><em>Module directive data loss / malformed module.</em> {@code comment-preservation-module-declaration}
 *       (collapsed and expanded): the commented-module reconstruction split the body by source line, dropping every
 *       directive when collapsed onto one line and duplicating the {@code module} keyword when expanded. It now splits
 *       by directive {@code ;}. Guarded by {@code comment-preservation-module-single-line}.
 *   <li><em>Non-terminating reformat loop.</em> {@code comment-preservation-method-chain-segments} (collapsed and
 *       expanded): blank-line-separated leading line comments wrongly routed a method through the raw signature
 *       fallback, which re-indented preserved blank lines one level deeper on every pass. Guarded by
 *       {@code method-leading-comments-blank-separated}.
 *   <li><em>Oscillation into malformed output.</em> {@code block-lambda-arrow-parens-always} /
 *       {@code block-lambda-arrow-parens-avoid} (collapsed): a source-multiline method-chain statement whose final
 *       segment carried a trailing line comment had its {@code ;} appended after the {@code //} comment on a later pass,
 *       commenting the semicolon out. The {@code ;} is now threaded before the comment. Guarded by
 *       {@code method-chain-final-segment-trailing-comment}.
 * </ul>
 *
 * <p><strong>Not a finding (formerly mis-labeled): {@code formatter-pragma-spacing}.</strong> Earlier this was excluded
 * for "collapsing whitespace moves a line-based {@code // @formatter:on} onto a shared line." That was a
 * <em>perturbation artifact</em>, not a formatter bug: a line-significant pragma defines the protected region by its
 * line, so sliding it onto another line legitimately changes what the user asked to protect. The perturbation now keeps
 * such pragma / ignore markers on their own line (see {@link SourceShapePerturbation#perturb}), the same way it leaves
 * string interiors untouched, so this fixture is perturbed without changing meaning and is back in the green corpus.
 */
final class IdempotencePropertyTest {

    private static final FormatterOptions OPTIONS = FormatterOptions.defaults();

    /**
     * Perturbed inputs whose formatting exposes a genuine formatter defect — non-reparseable output, dropped program
     * elements, or failure to reach a fixed point within {@value #CONVERGENCE_PASSES} passes — that should be kept out
     * of the asserted corpus and reported rather than masked. Each entry is a corpus display name.
     *
     * <p>This set is currently <strong>empty</strong>: every finding the property originally surfaced has been fixed in
     * the formatter and is now regression-guarded by a dedicated golden fixture (see the class Javadoc). The hook is
     * kept so a future genuine non-termination/data-loss finding can be parked here with an honest diagnosis instead of
     * silently breaking the suite.
     */
    private static final Set<String> EXCLUDED_AS_FINDINGS = Set.of();

    /**
     * Golden fixture inputs that are AST-equivalence- and convergence-safe but whose <em>one-pass</em> idempotence is
     * deferred to a D3-flip follow-up: pass-1 is a legitimate layout the formatter does not yet reproduce as a one-pass
     * fixed point. Keyed by {@link FixtureInput#name()} (the fixture directory name, no {@code @ variant} suffix). These
     * still assert semantic preservation below; only the {@code format(format(x)) == format(x)} sub-assertion is skipped.
     * Each mirrors a {@code FrmtrTest.KNOWN_NON_IDEMPOTENT} entry and is a tracked follow-up (see ARCHITECTURE.md
     * "residual follow-ups").
     */
    private static final Set<String> KNOWN_NON_IDEMPOTENT = Set.of(
        // Single-argument lambda-hug tail (`assertThatThrownBy(() -> …)` / `probe.withVirtualTime(() -> …)`) whose
        // body-call argument list collapses flat on one pass and breaks on the next. The nested block-lambda body that
        // used to flatten with stray ` .` joins is fixed (PR #279 lambda-body multi-line render). tracked: D3 flip
        // follow-ups.
        "lambda-expression-argument-opener",
        // Object-creation chain initializer: break-after-`=` + sourceFirstLineKeepsChainAfterRoot. tracked: D3 flip
        // follow-ups.
        "source-multiline-object-chain-initializer"
    );

    /**
     * Over the inputs a developer actually feeds the formatter — every verbatim golden fixture input and every
     * hand-written snippet — formatting is a one-pass fixed point and preserves program meaning.
     *
     * <p>Idempotence is asserted at the {@link Frmtr#format} level; semantic preservation is asserted explicitly against
     * the input's own parse tree via {@link AstEquivalence}. Both must hold for every such input, except that one-pass
     * idempotence is deferred (not semantic preservation) for the {@link #KNOWN_NON_IDEMPOTENT} D3-flip follow-ups.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("wellShapedCorpus")
    void idempotentAndSemanticsPreserving(String name, String source) {
        String formatted = Frmtr.format(source, OPTIONS);

        if (!KNOWN_NON_IDEMPOTENT.contains(name)) {
            assertThat(Frmtr.format(formatted, OPTIONS))
                    .as("formatting is not idempotent for input `%s` (format(format(x)) != format(x))", name)
                    .isEqualTo(formatted);
        }

        assertSemanticsPreserved(name, source, formatted);
    }

    /**
     * Number of formatting passes within which a perturbed input must reach a fixed point. Chosen small: most reshaped
     * inputs settle in one or two passes, so anything still moving after five passes is reformatting in a loop rather
     * than converging.
     */
    private static final int CONVERGENCE_PASSES = 5;

    /**
     * Over mechanically perturbed (re-whitespaced) fixture inputs, formatting preserves program meaning, produces output
     * that re-parses, and reaches a fixed point within {@value #CONVERGENCE_PASSES} passes.
     *
     * <p>This is the breadth the golden suite cannot give: arbitrary-shaped valid input that no fixture author wrote.
     * One-pass idempotence is deliberately <em>not</em> asserted here. The formatter is not one-pass idempotent on
     * arbitrarily-reshaped source — e.g. a {@code return} expression collapsed onto one over-long line first wraps into
     * a parenthesized group with the binary chain flat, and only a second pass breaks the chain across lines — so
     * {@code format(format(x)) != format(x)} for many perturbed inputs.
     *
     * <p>What <em>is</em> asserted, beyond semantic preservation and parse-stability, is <strong>eventual
     * convergence</strong>: re-running {@code format} must reach a fixed point ({@code format(f^k(x)) == f^k(x)}) within a
     * small number of passes. Empirically most reshaped inputs settle within one or two passes; a couple need a third.
     * Any input that never stabilizes (its output grows or oscillates every pass) is a genuine non-termination finding,
     * not a property to assert away — those are recorded in {@link #EXCLUDED_AS_FINDINGS} (e.g. the method-chain-segments
     * shapes, whose output grows ~12-16 chars on every pass, a non-terminating reformat loop) rather than masked.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("perturbedCorpus")
    void perturbedInputsAreSemanticsPreservingAndParseStable(String name, String source) {
        String formatted = Frmtr.format(source, OPTIONS);

        assertThat(parseResult(formatted).isSuccessful())
                .as("formatting perturbed input `%s` produced output that does not re-parse", name)
                .isTrue();

        assertSemanticsPreserved(name, source, formatted);
        assertConvergesWithin(name, formatted);
    }

    /**
     * Asserts that repeatedly formatting reaches a fixed point within {@link #CONVERGENCE_PASSES} passes, starting from
     * {@code formatted} (which is already {@code format(source)}). The fixed point is the first {@code k} for which
     * {@code format(f^k(x)) == f^k(x)}; the failure message names the input and shows the per-pass output lengths so a
     * growing (non-terminating) or oscillating loop is visible.
     */
    private static void assertConvergesWithin(String name, String formatted) {
        List<Integer> lengths = new ArrayList<>();
        lengths.add(formatted.length());
        String current = formatted;
        for (int pass = 1; pass <= CONVERGENCE_PASSES; pass++) {
            String next = Frmtr.format(current, OPTIONS);
            lengths.add(next.length());
            if (next.equals(current)) {
                return;
            }
            current = next;
        }
        throw new AssertionError(
            String.format(
                "perturbed input `%s` did not reach a fixed point within %d passes (output keeps changing — growing or "
                    + "oscillating, not converging); per-pass output lengths: %s",
                name,
                CONVERGENCE_PASSES,
                lengths
            )
        );
    }

    private static void assertSemanticsPreserved(String name, String source, String formatted) {
        // Parse each side once and reuse the trees for both the equivalence decision and the failure description, rather
        // than re-parsing source/formatted a second time inside describeDifference.
        CompilationUnit inputTree = parse(source);
        CompilationUnit outputTree = parse(formatted);
        assertThat(AstEquivalence.equivalent(inputTree, outputTree))
                .as(
                    "formatting changed program meaning for input `%s`: %s",
                    name,
                    AstEquivalence.describeDifference(inputTree, outputTree).orElse("<equivalent>")
                )
                .isTrue();
    }

    // --- corpora ------------------------------------------------------------------------------------------------

    /** Verbatim golden fixture inputs plus the hand-written snippets. */
    static Stream<Arguments> wellShapedCorpus() {
        List<Arguments> arguments = new ArrayList<>();
        for (FixtureInput fixture : fixtureInputs()) {
            addIfParses(arguments, fixture.name(), fixture.source());
        }
        for (int index = 0; index < HAND_WRITTEN_SNIPPETS.size(); index++) {
            addIfParses(arguments, "snippet-" + index, HAND_WRITTEN_SNIPPETS.get(index));
        }
        return arguments.stream();
    }

    /** Two parse-preserving whitespace perturbations of each golden fixture input. */
    static Stream<Arguments> perturbedCorpus() {
        List<Arguments> arguments = new ArrayList<>();
        for (FixtureInput fixture : fixtureInputs()) {
            addIfParses(
                arguments,
                fixture.name() + " @ collapsed-whitespace",
                SourceShapePerturbation.perturb(fixture.source(), Shape.COLLAPSE)
            );
            addIfParses(
                arguments,
                fixture.name() + " @ expanded-whitespace",
                SourceShapePerturbation.perturb(fixture.source(), Shape.EXPAND)
            );
        }
        return arguments.stream();
    }

    private static void addIfParses(List<Arguments> arguments, String name, String source) {
        if (source != null && !EXCLUDED_AS_FINDINGS.contains(name) && parseResult(source).isSuccessful()) {
            arguments.add(Arguments.of(name, source));
        }
    }

    /** Smallest corpus we expect after legitimate skips; a sharp drop below this means coverage silently eroded. */
    private static final int MINIMUM_WELL_SHAPED_CORPUS = 40;

    /**
     * Surfaces the corpus shrinkage that {@link #addIfParses} would otherwise hide.
     *
     * <p>{@code addIfParses} silently drops fixture inputs that do not parse cleanly as a {@code COMPILATION_UNIT}
     * (unnamed-class / unnamed-pattern / parse-error-recovery fixtures and the like). The drop is principled —
     * AST-equivalence is ill-defined on a {@code RECOVER}-only tree, so those inputs are intentionally out of scope — but
     * invisible: a regression that started dropping half the corpus would still show a green suite. This logs the skipped
     * fixture names and count, and asserts a minimum corpus size, so coverage erosion cannot pass unnoticed.
     */
    @BeforeAll
    static void reportSkippedFixtures() {
        List<String> skipped = new ArrayList<>();
        int parsing = 0;
        for (FixtureInput fixture : fixtureInputs()) {
            if (parseResult(fixture.source()).isSuccessful()) {
                parsing++;
            } else {
                skipped.add(fixture.name());
            }
        }
        skipped.sort(Comparator.naturalOrder());
        System.out.printf(
            "[IdempotencePropertyTest] well-shaped fixture corpus: %d cleanly-parsing, %d skipped"
                + " (RECOVER-only, AST-equivalence out of scope)%n",
            parsing,
            skipped.size()
        );
        for (String name : skipped) {
            System.out.println("  skipped (does not parse as COMPILATION_UNIT): " + name);
        }
        int wellShaped = parsing + HAND_WRITTEN_SNIPPETS.size();
        assertThat(wellShaped)
                .as(
                    "well-shaped corpus shrank to %d inputs (%d cleanly-parsing fixtures + %d snippets); a drop below"
                        + " %d means fixtures are being silently skipped — investigate before lowering this floor",
                    wellShaped,
                    parsing,
                    HAND_WRITTEN_SNIPPETS.size(),
                    MINIMUM_WELL_SHAPED_CORPUS
                )
                .isGreaterThanOrEqualTo(MINIMUM_WELL_SHAPED_CORPUS);
    }

    // --- corpus sources -----------------------------------------------------------------------------------------

    private static List<FixtureInput> fixtureInputs() {
        // Reuse ResourceFixtureSource's input discovery (the input-only mode that requires no output companion) so the
        // glob walk and fixture-name convention live in one place. This test only consumes inputs — it perturbs them and
        // asserts input-derived invariants — so it stays on @MethodSource and calls the discovery directly.
        return ResourceFixtureSource.Provider.inputs("format/**/input.java");
    }

    /**
     * Diverse constructs not present as golden outputs — generics, lambdas, switch expressions, records, annotations,
     * text blocks, varargs, enums with and without a trailing separator, sealed hierarchies, and comment-dense members —
     * so the property is not limited to the shapes someone happened to write a fixture for.
     */
    private static final List<String> HAND_WRITTEN_SNIPPETS = List.of(
            """
            import java.util.List;
            import java.util.Map;
            final class Generics<T extends Comparable<? super T>> {
                <R> Map<String, List<R>> transform(List<? extends R> input) {
                    return Map.of("k", List.copyOf(input));
                }
            }
            """,
            """
            import java.util.function.Function;
            class Lambdas {
                Function<Integer, Integer> twice = x -> x * 2;
                Runnable block = () -> { System.out.println("hi"); };
                Function<String, Integer> len = (String s) -> s.length();
            }
            """,
            """
            class SwitchExpressions {
                int classify(int day) {
                    return switch (day) {
                        case 1, 2, 3, 4, 5 -> 0;
                        case 6, 7 -> 1;
                        default -> {
                            yield -1;
                        }
                    };
                }
            }
            """,
            """
            record Point(int x, int y) {
                Point {
                    if (x < 0 || y < 0) throw new IllegalArgumentException();
                }
                static Point origin() { return new Point(0, 0); }
            }
            """,
            """
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            @Retention(RetentionPolicy.RUNTIME)
            @interface Marker {
                String value() default "x";
                int[] codes() default {1, 2, 3};
            }
            """,
            """
            class TextBlocks {
                String json = ""\"
                    {"a":   1,
                     "b": [2, 3]}
                    ""\";
                String oneLiner = "a\\tb\\nc";
            }
            """,
            """
            class Varargs {
                @SafeVarargs
                static <T> int count(T... items) { return items.length; }
                void use() { count("a", "b", "c"); }
            }
            """,
            """
            enum WithTrailingComma {
                RED,
                GREEN,
                BLUE,
                ;
                int code() { return ordinal(); }
            }
            """,
            """
            enum NoTrailingComma { ALPHA, BETA, GAMMA }
            """,
            """
            class Comments {
                // leading line comment
                int field; // trailing comment
                /* block */ void method() {
                    /** odd javadoc-ish inside body */
                    int x = 1; // tail
                }
            }
            """,
            """
            class NestedTernaryAndBinary {
                int pick(int a, int b, int c) {
                    return a > b ? (a > c ? a : c) : (b > c ? b : c);
                }
                boolean flags(boolean p, boolean q, boolean r) {
                    return p && q || r && !p;
                }
            }
            """,
            """
            sealed interface Shape permits Circle, Square {}
            record Circle(double radius) implements Shape {}
            record Square(double side) implements Shape {}
            """);

    // --- parsing helpers ----------------------------------------------------------------------------------------

    private static ParseResult<CompilationUnit> parseResult(String source) {
        return newParser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
    }

    private static CompilationUnit parse(String source) {
        return parseResult(source).getResult().orElseThrow();
    }

    private static JavaParser newParser() {
        // Match the language level the default-options formatter uses (FormatterOptions.LATEST_AVAILABLE maps to
        // BLEEDING_EDGE in JavaFormatter) so the AST-equivalence comparison is on the same footing. This parser does NOT
        // accept everything the formatter accepts: the formatter can RECOVER (best-effort parse) inputs this parser
        // rejects as a COMPILATION_UNIT — e.g. unnamed-class / unnamed-pattern fixtures. Only cleanly-parsing inputs are
        // in scope here; RECOVER-only inputs are intentionally skipped (AST-equivalence is ill-defined on a best-effort
        // tree, per the proposal's Risks section), and that skip is surfaced by reportSkippedFixtures().
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(true)
                .setAttributeComments(true));
    }
}
