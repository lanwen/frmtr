package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class LineCommentTailMatrixTest {

    private static final FormatterOptions OPTIONS = FormatterOptions.defaults();
    private static final String SENTINEL_PREFIX = "LC_TAIL_";
    private static final Pattern SENTINEL_SWALLOWED_PUNCTUATION =
        Pattern.compile(".*//\\s*" + SENTINEL_PREFIX + "\\S*\\s*[;,]\\s*$");
    private static final List<String> EXPECTED_CASE_NAMES = List.of(
        "expression-statement / simple-call",
        "expression-statement / flat-final-call",
        "expression-statement / source-multiline-final-call",
        "assignment-statement / simple-call",
        "assignment-statement / flat-final-call",
        "assignment-statement / source-multiline-final-call",
        "local-initializer / simple-call",
        "local-initializer / flat-final-call",
        "local-initializer / source-multiline-final-call",
        "return-value / simple-call",
        "return-value / flat-final-call",
        "return-value / source-multiline-final-call",
        "field-initializer / simple-call",
        "field-initializer / flat-final-call",
        "field-initializer / source-multiline-final-call",
        "throw-value / simple-call",
        "throw-value / flat-final-call",
        "throw-value / source-multiline-final-call",
        "yield-value / simple-call",
        "yield-value / flat-final-call",
        "yield-value / source-multiline-final-call",
        "switch-rule-expression / simple-call",
        "switch-rule-expression / flat-final-call",
        "switch-rule-expression / source-multiline-final-call",
        "method-call-argument / simple-call",
        "method-call-argument / flat-final-call",
        "method-call-argument / source-multiline-final-call",
        "constructor-argument / simple-call",
        "constructor-argument / flat-final-call",
        "constructor-argument / source-multiline-final-call",
        "array-initializer / simple-call",
        "array-initializer / flat-final-call",
        "array-initializer / source-multiline-final-call",
        "enum-constant-comma",
        "enum-constant-semicolon"
    );

    private static final List<ExpressionContext> EXPRESSION_CONTEXTS = List.of(
        new ExpressionContext(
            "expression-statement",
            expression -> classWithRunMethod(expression + "\n            ;")
        ),
        new ExpressionContext(
            "assignment-statement",
            expression -> classWithRunMethod("target = " + expression + "\n            ;")
        ),
        new ExpressionContext(
            "local-initializer",
            expression -> classWithRunMethod("Object value = " + expression + "\n            ;")
        ),
        new ExpressionContext(
            "return-value",
            expression -> """
                class Demo {
                    Object run(Subject subject) {
                        return %s
                        ;
                    }
                }
                """
                .formatted(expression)
        ),
        new ExpressionContext(
            "field-initializer",
            expression -> """
                class Demo {
                    Object value = %s
                    ;
                }
                """
                .formatted(expression)
        ),
        new ExpressionContext(
            "throw-value",
            expression -> classWithRunMethod("throw " + expression + "\n            ;")
        ),
        new ExpressionContext(
            "yield-value",
            expression -> classWithRunMethod("""
                Object value = switch (subject.kind()) {
                    default -> {
                        yield %s
                        ;
                    }
                };
                """
                .formatted(expression))
        ),
        new ExpressionContext(
            "switch-rule-expression",
            expression -> classWithRunMethod("""
                Object value = switch (subject.kind()) {
                    default -> %s
                    ;
                };
                """
                .formatted(expression))
        )
    );

    private static final List<ExpressionContext> COMMA_CONTEXTS = List.of(
        new ExpressionContext(
            "method-call-argument",
            expression -> classWithRunMethod("""
                sink(
                    %s
                    ,
                    fallback()
                );
                """
                .formatted(expression))
        ),
        new ExpressionContext(
            "constructor-argument",
            expression -> classWithRunMethod("""
                new Pair(
                    %s
                    ,
                    fallback()
                );
                """
                .formatted(expression))
        ),
        new ExpressionContext(
            "array-initializer",
            expression -> classWithRunMethod("""
                Object[] values = {
                    %s
                    ,
                    fallback()
                };
                """
                .formatted(expression))
        )
    );

    private static final List<ChainShape> CHAIN_SHAPES = List.of(
        new ChainShape("simple-call", (receiver, sentinel) -> "call() // " + sentinel),
        new ChainShape("flat-final-call", (receiver, sentinel) -> receiver + ".first().last() // " + sentinel),
        new ChainShape(
            "source-multiline-final-call",
            (receiver, sentinel) -> receiver + "\n                .first()\n                .last() // " + sentinel
        )
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("tailMatrix")
    void lineCommentTailDoesNotSwallowSuffixOrSeparator(String name, String source) {
        assertParses("generated source `" + name + "`", source);

        String formatted = format(name, source);

        CompilationUnit formattedTree = assertParses("formatted output for `" + name + "`", formatted);
        assertThat(Frmtr.format(formatted, OPTIONS))
                .as("formatted output for `%s` should be a clean fixed point", name)
                .isEqualTo(formatted);
        assertThat(AstEquivalence.equivalent(parse(source), formattedTree))
                .as(
                    "formatting changed program meaning for `%s`: %s",
                    name,
                    AstEquivalence.describeDifference(parse(source), formattedTree).orElse("<equivalent>")
                )
                .isTrue();
        assertThat(sentinelLines(formatted))
                .as("formatted output for `%s` should keep sentinel comments visible", name)
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .as("line comment swallowed a suffix/separator in `%s`: %s", name, line)
                        .doesNotMatch(SENTINEL_SWALLOWED_PUNCTUATION));
    }

    @Test
    void ordinaryDeclarationTrailingCommentDoesNotForceMethodCallInitializerTail() {
        String source = """
            class Demo {
                Object value = call(alpha, beta); // trailing
            }
            """;

        String formatted = Frmtr.format(source, OPTIONS);

        assertThat(formatted)
                .contains("Object value = call(alpha, beta); // trailing")
                .doesNotContain("Object value =\n");
        assertThat(Frmtr.format(formatted, OPTIONS)).isEqualTo(formatted);
    }

    static Stream<Arguments> tailMatrix() {
        List<Arguments> arguments = new ArrayList<>();
        addExpressionCases(arguments, EXPRESSION_CONTEXTS, "subject()");
        addExpressionCases(arguments, COMMA_CONTEXTS, "subject()");
        addEnumConstantTailCases(arguments);
        assertThat(arguments.stream().map(argument -> (String) argument.get()[0]).toList())
                .containsExactlyElementsOf(EXPECTED_CASE_NAMES);
        return arguments.stream();
    }

    private static void addExpressionCases(
        List<Arguments> arguments,
        List<ExpressionContext> contexts,
        String receiver
    ) {
        for (ExpressionContext context : contexts) {
            for (ChainShape shape : CHAIN_SHAPES) {
                String name = context.name() + " / " + shape.name();
                String sentinel = SENTINEL_PREFIX + name.replaceAll("[^A-Za-z0-9]+", "_");
                arguments.add(Arguments.of(name, context.source().apply(shape.expression().apply(receiver, sentinel))));
            }
        }
    }

    private static void addEnumConstantTailCases(List<Arguments> arguments) {
        arguments.add(Arguments.of(
            "enum-constant-comma",
            """
            enum Demo {
                FIRST // %s
                ,
                SECOND
            }
            """
                .formatted(SENTINEL_PREFIX + "enum_constant_comma")
        ));
        arguments.add(Arguments.of(
            "enum-constant-semicolon",
            """
            enum Demo {
                FIRST // %s
                ;

                int value() {
                    return 1;
                }
            }
            """
                .formatted(SENTINEL_PREFIX + "enum_constant_semicolon")
        ));
    }

    private static String classWithRunMethod(String body) {
        return """
            class Demo {
                void run(Subject subject) {
                    %s
                }
            }
            """
            .formatted(body.indent(8).stripLeading());
    }

    private static List<String> sentinelLines(String formatted) {
        return formatted.lines().filter(line -> line.contains("// " + SENTINEL_PREFIX)).toList();
    }

    private static String format(String name, String source) {
        try {
            return Frmtr.format(source, OPTIONS);
        } catch (AssertionError | RuntimeException failure) {
            throw new AssertionError(
                "formatting `" + name + "` failed for generated source:" + System.lineSeparator() + source,
                failure
            );
        }
    }

    private static CompilationUnit assertParses(String label, String source) {
        ParseResult<CompilationUnit> result = parseResult(source);
        assertThat(result.isSuccessful())
                .as("%s should parse cleanly; problems: %s%n%s", label, result.getProblems(), source)
                .isTrue();
        return result.getResult().orElseThrow();
    }

    private static CompilationUnit parse(String source) {
        return assertParses("source", source);
    }

    private static ParseResult<CompilationUnit> parseResult(String source) {
        return newParser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
    }

    private static JavaParser newParser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(true)
                .setAttributeComments(true));
    }

    private record ExpressionContext(String name, Function<String, String> source) {}

    private record ChainShape(String name, BiFunction<String, String, String> expression) {}
}
