package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class FormatterGuardrailsTest {

    @Test
    void duplicateCommentClaimsKeepExistingSkipBehaviorByDefault() {
        withStrictClaims(null, () -> {
            CommentTracker comments = commentTracker();
            LineComment comment = new LineComment("value");

            assertThat(comments.comment(comment)).isNotEqualTo(Doc.EMPTY);
            assertThatCode(() -> assertThat(comments.comment(comment)).isEqualTo(Doc.EMPTY)).doesNotThrowAnyException();
        });
    }

    @Test
    void duplicateCommentClaimsFailFastWhenStrictClaimsAreEnabled() {
        withStrictClaims("true", () -> {
            CommentTracker comments = commentTracker();
            LineComment comment = new LineComment("value");

            comments.comment(comment);

            assertThatThrownBy(() -> comments.comment(comment))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("duplicate claim")
                    .hasMessageContaining("LineComment")
                    .hasMessageContaining("//value");
        });
    }

    @Test
    void duplicateCommentClaimsKeepSkipBehaviorWhenOnlyDropDetectionIsEnabled() {
        // Comment-drop detection (ENABLED_PROPERTY) is on in CI, but the strict "claimed at most once" invariant is not:
        // a duplicate claim must keep its benign skip behavior, since the claim/render-coupled design legitimately offers
        // the same comment from more than one printer path.
        withGuardrails("true", () -> withStrictClaims(null, () -> {
            CommentTracker comments = commentTracker();
            LineComment comment = new LineComment("value");

            assertThat(comments.comment(comment)).isNotEqualTo(Doc.EMPTY);
            assertThatCode(() -> assertThat(comments.comment(comment)).isEqualTo(Doc.EMPTY)).doesNotThrowAnyException();
        }));
    }

    @Test
    void guardrailActivationUsesSystemProperty() {
        withGuardrails("true", () -> assertThat(FormatterGuardrails.enabled()).isTrue());
        withGuardrails("false", () -> assertThat(FormatterGuardrails.enabled()).isFalse());
        withGuardrails(null, () -> assertThat(FormatterGuardrails.enabled()).isFalse());
    }

    @Test
    void strictClaimsActivationUsesSeparateSystemProperty() {
        // The strict-claims fail-fast is gated independently of the main guardrail, so enabling drop detection does not
        // enable it and vice versa.
        withStrictClaims("true", () -> assertThat(FormatterGuardrails.strictClaimsEnabled()).isTrue());
        withStrictClaims("false", () -> assertThat(FormatterGuardrails.strictClaimsEnabled()).isFalse());
        withStrictClaims(null, () -> assertThat(FormatterGuardrails.strictClaimsEnabled()).isFalse());
        withGuardrails("true", () -> withStrictClaims(null, () ->
            assertThat(FormatterGuardrails.strictClaimsEnabled()).isFalse()));
    }

    @Test
    void duplicateCommentClaimMessageCapsCommentText() {
        withStrictClaims("true", () -> {
            CommentTracker comments = commentTracker();
            LineComment comment = new LineComment("a".repeat(120) + "tail-marker");

            comments.comment(comment);

            assertThatThrownBy(() -> comments.comment(comment))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("duplicate claim")
                    .hasMessageContaining("LineComment")
                    .hasMessageContaining("...")
                    .hasMessageNotContaining("tail-marker");
        });
    }

    @Test
    void recordedOwnerReclaimRendersInEveryRealPassArmUnderStrictClaims() {
        // Enabler for comment-bearing ranked candidate sets: when a later slice builds a comment-bearing subtree in more
        // than one eagerly evaluated arm, each arm re-offers the comment from its single recorded owner. That owner
        // re-claim must render the comment in every arm (the renderer keeps whichever it picks) instead of skipping the
        // later arms or tripping the strict-claims fail-fast. Without this the second arm would throw here.
        withStrictClaims("true", () -> {
            CommentTracker comments = commentTracker();
            Node field = parse(
                """
                    class Ledger {
                        int balance;
                    }
                    """
            ).getType(0).getMember(0);
            JavaCommentTrivia trivia = JavaCommentTrivia.from(new LineComment("running total"));

            // Dry-run records (field, TRAILING) as the comment's owner, mirroring JavaPrinter#print.
            comments.beginRecording();
            comments.comment(trivia, field, OwnerSlot.TRAILING);
            comments.endRecordingAndReset(new LayoutDecisionLog(), new FormatterPragmas());

            Doc firstArm = comments.comment(trivia, field, OwnerSlot.TRAILING);
            Doc secondArm = comments.comment(trivia, field, OwnerSlot.TRAILING);

            assertThat(firstArm).isNotEqualTo(Doc.EMPTY);
            assertThat(secondArm).isEqualTo(firstArm);
        });
    }

    @Test
    void reclaimWithoutRecordedOwnerStillFailsFastUnderStrictClaims() {
        // The benign re-claim above is gated on a *recorded* owner, not on the broader ownsHere (which also admits an
        // unmigrated comment the dry-run never recorded). With no recording pass, the same (node, slot) offering a comment
        // twice is a genuine duplicate claim and must still trip the guardrail. This is the only difference from the test
        // above, so it pins the recorded-owner distinction: loosening the predicate back to ownsHere would break it.
        withStrictClaims("true", () -> {
            CommentTracker comments = commentTracker();
            Node field = parse(
                """
                    class Ledger {
                        int balance;
                    }
                    """
            ).getType(0).getMember(0);
            JavaCommentTrivia trivia = JavaCommentTrivia.from(new LineComment("running total"));

            comments.comment(trivia, field, OwnerSlot.TRAILING);

            assertThatThrownBy(() -> comments.comment(trivia, field, OwnerSlot.TRAILING))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("duplicate claim")
                    .hasMessageContaining("running total");
        });
    }

    @Test
    void missedCommentAccountingKeepsExistingBehaviorByDefault() {
        withGuardrails(null, () -> {
            CompilationUnit unit = parse(
                """
                    class Demo {
                        int value; // value
                    }
                    """
            );
            CommentTracker comments = commentTracker();

            assertThatCode(() -> comments.assertAllCommentsAccounted(unit)).doesNotThrowAnyException();
        });
    }

    @Test
    void missedCommentAccountingFailsAtCompletionWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse(
                """
                    class Demo {
                        int value; // value
                    }
                    """
            );
            CommentTracker comments = commentTracker();

            assertThatThrownBy(() -> comments.assertAllCommentsAccounted(unit))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("unclaimed comment")
                    .hasMessageContaining("LineComment")
                    .hasMessageContaining("line 2")
                    .hasMessageContaining("// value");
        });
    }

    @Test
    void rawRenderedCommentsAreAccountedAtCompletionWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse(
                """
                    class Demo {
                        int value; // value
                    }
                    """
            );
            CommentTracker comments = commentTracker();
            RawPreservedSource rawPreservedSource = new RawPreservedSource(
                new RawSource(FormatterOptions.defaults()),
                comments
            );
            rawPreservedSource.raw(unit);

            assertThatCode(() -> comments.assertAllCommentsAccounted(unit)).doesNotThrowAnyException();
        });
    }

    @Test
    void formatterCompletesWhenStructuredCommentsAreClaimedWithDebugGuardrailsEnabled() {
        withGuardrails("true", () -> {
            String formatted = new JavaFormatter(FormatterOptions.defaults()).format(
                """
                    package dev.example;
                    // demo type
                    class Demo {
                        // value comment
                        int value; // trailing value
                    }
                    """
            );

            assertThat(formatted)
                    .contains("// demo type")
                    .contains("// value comment")
                    .contains("// trailing value");
        });
    }

    @Test
    void transformGuardrailsKeepExistingBehaviorByDefault() {
        withGuardrails(null, () -> {
            CompilationUnit unit = parse(
                """
                    package dev.example;

                    // demo import
                    import z.Value;

                    // demo type
                    class Demo {}
                    """
            );

            CompilationUnit transformed = new JavaTransformPipeline(
                List.of(
                    new ReplacingImportDeclarationTransform(),
                    new ReplacingTypeCommentTransform(),
                    new ReplacingCompilationUnitTransform()
                )
            ).transform(unit);

            assertThat(transformed).isNotSameAs(unit);
        });
    }

    @Test
    void transformThatReplacesCompilationUnitFailsWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse("class Demo {}");

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingCompilationUnitTransform()))
                        .transform(unit)
            )
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("ReplacingCompilationUnitTransform")
                    .hasMessageContaining("different CompilationUnit instance")
                    .hasMessageContaining("original JavaParser tree");
        });
    }

    @Test
    void transformThatReplacesVisibleCommentFailsWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse(
                """
                    // demo type
                    class Demo {}
                    """
            );

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingTypeCommentTransform())).transform(
                    unit
            ))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("ReplacingTypeCommentTransform")
                    .hasMessageContaining("JavaParser-visible comment")
                    .hasMessageContaining("lost or replaced")
                    .hasMessageContaining("// demo type");
        });
    }

    @Test
    void transformThatReplacesImportDeclarationFailsWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse(
                """
                    package dev.example;

                    // demo import
                    import z.Value;

                    class Demo {}
                    """
            );

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingImportDeclarationTransform()))
                        .transform(unit)
            )
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("ReplacingImportDeclarationTransform")
                    .hasMessageContaining("import declaration node")
                    .hasMessageContaining("reordered in place")
                    .hasMessageContaining("z.Value");
        });
    }

    @Test
    void transformThatMovesImportCommentsFailsWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse(
                """
                    package dev.example;

                    // first import
                    import a.First;
                    // second import
                    import b.Second;

                    class Demo {}
                    """
            );

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new SwappingImportCommentsTransform()))
                        .transform(unit)
            )
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("SwappingImportCommentsTransform")
                    .hasMessageContaining("comment attachment changed")
                    .hasMessageContaining("a.First")
                    .hasMessageContaining("original import nodes");
        });
    }

    @Test
    void transformThatReplacesNonImportNodeFailsWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse(
                """
                    package dev.example;

                    import z.Value;

                    class Demo {
                        void value() {}
                    }
                    """
            );

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingTypeDeclarationTransform()))
                        .transform(unit)
            )
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("ReplacingTypeDeclarationTransform")
                    .hasMessageContaining("JavaParser tree node")
                    .hasMessageContaining("lost or replaced")
                    .hasMessageContaining("ClassOrInterfaceDeclaration");
        });
    }

    @Test
    void formatterCompletesWhenRawPragmaCommentsAreAccountedWithDebugGuardrailsEnabled() {
        withGuardrails("true", () -> {
            String formatted = new JavaFormatter(FormatterOptions.defaults()).format(
                """
                    class Demo {
                        // frmtr-ignore
                        void messy( ) { int value=1; /* preserved */ }
                    }
                    """
            );

            assertThat(formatted).contains("/* preserved */");
        });
    }

    private static final class ReplacingCompilationUnitTransform implements JavaFormatTransform {

        @Override
        public JavaTransformResult transform(CompilationUnit unit) {
            return JavaTransformResult.completed(this, new CompilationUnit());
        }
    }

    private static final class ReplacingTypeCommentTransform implements JavaFormatTransform {

        @Override
        public JavaTransformResult transform(CompilationUnit unit) {
            unit.getType(0).setComment(new LineComment("replacement"));
            return JavaTransformResult.completed(this, unit);
        }
    }

    private static final class ReplacingImportDeclarationTransform implements JavaFormatTransform {

        @Override
        public JavaTransformResult transform(CompilationUnit unit) {
            ImportDeclaration imported = unit.getImport(0);
            Optional<Comment> comment = imported.getComment();
            ImportDeclaration replacement = new ImportDeclaration(
                imported.getName(),
                imported.isStatic(),
                imported.isAsterisk()
            );
            comment.ifPresent(replacement::setComment);
            unit.getImports().set(0, replacement);
            return JavaTransformResult.completed(this, unit);
        }
    }

    private static final class SwappingImportCommentsTransform implements JavaFormatTransform {

        @Override
        public JavaTransformResult transform(CompilationUnit unit) {
            ImportDeclaration first = unit.getImport(0);
            ImportDeclaration second = unit.getImport(1);
            Comment firstComment = first.getComment().orElseThrow();
            Comment secondComment = second.getComment().orElseThrow();
            first.setComment(secondComment);
            second.setComment(firstComment);
            return JavaTransformResult.completed(this, unit);
        }
    }

    private static final class ReplacingTypeDeclarationTransform implements JavaFormatTransform {

        @Override
        public JavaTransformResult transform(CompilationUnit unit) {
            unit.getTypes().set(0, unit.getType(0).clone());
            return JavaTransformResult.completed(this, unit);
        }
    }

    private static void withGuardrails(String value, Runnable action) {
        withProperty(FormatterGuardrails.ENABLED_PROPERTY, value, action);
    }

    private static void withStrictClaims(String value, Runnable action) {
        withProperty(FormatterGuardrails.STRICT_CLAIMS_PROPERTY, value, action);
    }

    private static void withProperty(String key, String value, Runnable action) {
        String previous = System.getProperty(key);
        try {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
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

    private static CommentTracker commentTracker() {
        return new CommentTracker(new JavaCommentPlacementPolicy());
    }
}
