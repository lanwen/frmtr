package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class RecoveredListPlannerTest {

    @Test
    void allValidSiblingsProduceOnlyValidEntries() {
        String source = """
                class Demo {
                  int first;
                  int second;
                }
                """;
        CompilationUnit unit = parse(source);
        ClassOrInterfaceDeclaration type = onlyType(unit);
        SourceText sourceText = new SourceText(source);

        RecoveredListPlanner.Plan<BodyDeclaration<?>> plan = new RecoveredListPlanner(sourceText).plan(
            type,
            classBodyRegion(source),
            type.getMembers(),
            member -> true
        );

        assertThat(plan.isSafe()).isTrue();
        assertThat(plan.unsafe()).isEmpty();
        assertThat(rawGaps(plan)).isEmpty();
        assertThat(validSiblingSlices(plan, sourceText)).containsExactly("int first;", "int second;");
    }

    @Test
    void emitsPrefixBetweenAndSuffixRawGapsAroundValidSiblings() {
        String source = """
                class Demo {
                  int prefix;
                  int first;
                  int between;
                  int second;
                  int suffix;
                }
                """;
        CompilationUnit unit = parse(source);
        ClassOrInterfaceDeclaration type = onlyType(unit);
        SourceText sourceText = new SourceText(source);

        RecoveredListPlanner.Plan<BodyDeclaration<?>> plan = new RecoveredListPlanner(sourceText)
                .plan(type, classBodyRegion(source), type.getMembers(), RecoveredListPlannerTest::isBoundaryField);

        assertThat(plan.isSafe()).isTrue();
        assertThat(rawGaps(plan).stream().map(RecoveredListPlanner.RawGap::kind)).containsExactly(
            RecoveredListPlanner.RawGapKind.PREFIX,
            RecoveredListPlanner.RawGapKind.BETWEEN,
            RecoveredListPlanner.RawGapKind.SUFFIX
        );
        assertThat(rawGaps(plan).stream().map(gap -> sourceText.slice(gap.region()))).containsExactly(
            "\n  int prefix;\n  ",
            "\n  int between;\n  ",
            "\n  int suffix;\n"
        );
        assertThat(validSiblingSlices(plan, sourceText)).containsExactly("int first;", "int second;");
    }

    @Test
    void treatsParsedSiblingWithUnparsedDescendantAsUnsafe() {
        String source = """
                class Demo {
                  void before() {}
                  void broken() { call(); }
                  void after() {}
                }
                """;
        CompilationUnit unit = parse(source);
        ClassOrInterfaceDeclaration type = onlyType(unit);
        MethodDeclaration broken = method(type, "broken");
        Statement descendant = broken.getBody().orElseThrow().getStatements().get(0);
        descendant.setParsed(Node.Parsedness.UNPARSABLE);
        SourceText sourceText = new SourceText(source);

        RecoveredListPlanner.Plan<BodyDeclaration<?>> plan = new RecoveredListPlanner(sourceText).plan(
            type,
            classBodyRegion(source),
            type.getMembers(),
            member -> true
        );

        assertThat(broken.getParsed()).isEqualTo(Node.Parsedness.PARSED);
        assertThat(descendant.getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(plan.isSafe()).isTrue();
        assertThat(rawGaps(plan))
                .singleElement()
                .satisfies(gap -> {
                    assertThat(gap.kind()).isEqualTo(RecoveredListPlanner.RawGapKind.BETWEEN);
                    assertThat(sourceText.slice(gap.region())).contains("void broken() { call(); }");
                });
        assertThat(validSiblingSlices(plan, sourceText)).containsExactly("void before() {}", "void after() {}");
    }

    @Test
    void rawPreservesWholeListWhenUnparseableOwnerHasEmptyRecoveredSiblings() {
        String source = """
                class Demo {
                  void broken() {
                    int value = 1;
                  }
                }
                """;
        CompilationUnit unit = parse(source);
        MethodDeclaration broken = method(onlyType(unit), "broken");
        BlockStmt body = broken.getBody().orElseThrow();
        body.setParsed(Node.Parsedness.UNPARSABLE);
        SourceText sourceText = new SourceText(source);
        SourceRegion bodyInterior = blockInteriorRegion(source, body);

        RecoveredListPlanner.Plan<Statement> plan = new RecoveredListPlanner(sourceText).plan(
            body,
            bodyInterior,
            List.of(),
            statement -> true
        );

        assertThat(body.getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(bodyInterior.endOffset()).isGreaterThan(bodyInterior.beginOffset());
        assertThat(plan.isSafe()).isTrue();
        assertThat(plan.unsafe()).isEmpty();
        assertThat(validSiblings(plan)).isEmpty();
        assertThat(rawGaps(plan))
                .singleElement()
                .satisfies(gap -> {
                    assertThat(gap.kind()).isEqualTo(RecoveredListPlanner.RawGapKind.PREFIX);
                    assertThat(gap.region()).isEqualTo(bodyInterior);
                    assertThat(sourceText.slice(gap.region())).contains("int value = 1;");
                });
    }

    @Test
    void returnsUnsafePlanWhenSiblingRangeIsMissing() {
        String source = "class Demo {}\n";
        SourceText sourceText = new SourceText(source);
        CompilationUnit unit = parse(source);
        RecoveredListPlanner planner = new RecoveredListPlanner(sourceText);

        RecoveredListPlanner.Plan<Statement> plan = planner.plan(
            unit,
            sourceText.region(0, source.length()),
            List.of(new EmptyStmt()),
            statement -> true
        );

        assertThat(plan.isSafe()).isFalse();
        assertThat(plan.entries()).isEmpty();
        assertThat(plan.unsafe()).hasValueSatisfying(
            unsafe -> assertThat(unsafe.reason()).contains("missing a source range")
        );
    }

    @Test
    void returnsUnsafePlanWhenSiblingRangeFallsOutsideListBoundary() {
        String source = """
                class Demo {
                  int value;
                }
                """;
        CompilationUnit unit = parse(source);
        ClassOrInterfaceDeclaration type = onlyType(unit);
        FieldDeclaration field = type.getMembers().get(0).asFieldDeclaration();
        SourceText sourceText = new SourceText(source);
        int valueStart = source.indexOf("value");
        SourceRegion boundaryInsideFieldName = sourceText.region(valueStart, valueStart + "value".length());

        RecoveredListPlanner.Plan<FieldDeclaration> plan = new RecoveredListPlanner(sourceText).plan(
            type,
            boundaryInsideFieldName,
            List.of(field),
            ignored -> true
        );

        assertThat(plan.isSafe()).isFalse();
        assertThat(plan.entries()).isEmpty();
        assertThat(plan.unsafe()).hasValueSatisfying(
            unsafe -> assertThat(unsafe.reason()).contains("outside the list boundary")
        );
    }

    @Test
    void plannerDoesNotRenderOrAccountCommentsInsideRawGaps() {
        withGuardrails("true", () -> {
            String source = """
                    class Demo {
                      int before;
                      int raw; // stays raw later
                      int after;
                    }
                    """;
            CompilationUnit unit = parse(source);
            ClassOrInterfaceDeclaration type = onlyType(unit);
            SourceText sourceText = new SourceText(source);
            CommentTracker comments = new CommentTracker(new JavaCommentPlacementPolicy());

            RecoveredListPlanner.Plan<BodyDeclaration<?>> plan = new RecoveredListPlanner(sourceText).plan(
                type,
                classBodyRegion(source),
                type.getMembers(),
                member -> !fieldNamed(member, "raw")
            );

            assertThat(plan.isSafe()).isTrue();
            assertThat(rawGaps(plan))
                    .singleElement()
                    .satisfies(gap -> {
                        assertThat(gap.kind()).isEqualTo(RecoveredListPlanner.RawGapKind.BETWEEN);
                        assertThat(sourceText.slice(gap.region())).contains("// stays raw later");
                    });
            assertThatThrownBy(() -> comments.assertAllCommentsAccounted(unit))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("unclaimed comment")
                    .hasMessageContaining("// stays raw later");
        });
    }

    private static boolean isBoundaryField(BodyDeclaration<?> member) {
        return fieldNamed(member, "first") || fieldNamed(member, "second");
    }

    private static boolean fieldNamed(BodyDeclaration<?> member, String name) {
        return (
            member instanceof FieldDeclaration field
            && field.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals(name))
        );
    }

    private static SourceRegion classBodyRegion(String source) {
        int openingBrace = source.indexOf('{');
        int closingBrace = source.lastIndexOf('}');
        assertThat(openingBrace).isNotNegative();
        assertThat(closingBrace).isGreaterThan(openingBrace);
        return new SourceText(source).region(openingBrace + 1, closingBrace);
    }

    private static SourceRegion blockInteriorRegion(String source, BlockStmt block) {
        SourceText sourceText = new SourceText(source);
        SourceRegion blockRegion = sourceText.region(block.getRange().orElseThrow());
        return sourceText.region(blockRegion.beginOffset() + 1, blockRegion.endOffset() - 1);
    }

    private static ClassOrInterfaceDeclaration onlyType(CompilationUnit unit) {
        return unit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }

    private static MethodDeclaration method(ClassOrInterfaceDeclaration type, String name) {
        return type.getMethodsByName(name).get(0);
    }

    private static <N extends Node> List<String> validSiblingSlices(
            RecoveredListPlanner.Plan<N> plan,
            SourceText sourceText
    ) {
        return validSiblings(plan)
                .stream()
                .map(entry -> sourceText.slice(entry.region()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static <N extends Node> List<RecoveredListPlanner.ValidSibling<N>> validSiblings(
            RecoveredListPlanner.Plan<N> plan
    ) {
        return plan.entries()
                .stream()
                .filter(RecoveredListPlanner.ValidSibling.class::isInstance)
                .map(entry -> (RecoveredListPlanner.ValidSibling<N>) entry)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static <N extends Node> List<RecoveredListPlanner.RawGap<N>> rawGaps(RecoveredListPlanner.Plan<N> plan) {
        return plan.entries()
                .stream()
                .filter(RecoveredListPlanner.RawGap.class::isInstance)
                .map(entry -> (RecoveredListPlanner.RawGap<N>) entry)
                .toList();
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setStoreTokens(true)
                    .setAttributeComments(true)
        );
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }

    private static void withGuardrails(String value, Runnable action) {
        String previous = System.getProperty(FormatterGuardrails.ENABLED_PROPERTY);
        try {
            if (value == null) {
                System.clearProperty(FormatterGuardrails.ENABLED_PROPERTY);
            } else {
                System.setProperty(FormatterGuardrails.ENABLED_PROPERTY, value);
            }
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(FormatterGuardrails.ENABLED_PROPERTY);
            } else {
                System.setProperty(FormatterGuardrails.ENABLED_PROPERTY, previous);
            }
        }
    }
}
