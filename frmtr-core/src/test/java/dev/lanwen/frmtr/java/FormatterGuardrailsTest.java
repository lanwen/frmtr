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
        withGuardrails(null, () -> {
            CommentTracker comments = commentTracker();
            LineComment comment = new LineComment("value");

            assertThat(comments.comment(comment)).isNotEqualTo(Doc.EMPTY);
            assertThatCode(() -> assertThat(comments.comment(comment))
                            .isEqualTo(Doc.EMPTY))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void duplicateCommentClaimsFailFastWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
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
    void guardrailActivationUsesSystemProperty() {
        withGuardrails("true", () -> assertThat(FormatterGuardrails.enabled()).isTrue());
        withGuardrails("false", () -> assertThat(FormatterGuardrails.enabled()).isFalse());
        withGuardrails(null, () -> assertThat(FormatterGuardrails.enabled()).isFalse());
    }

    @Test
    void duplicateCommentClaimMessageCapsCommentText() {
        withGuardrails("true", () -> {
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
    void missedCommentAccountingKeepsExistingBehaviorByDefault() {
        withGuardrails(null, () -> {
            CompilationUnit unit = parse("""
                    class Demo {
                        int value; // value
                    }
                    """);
            CommentTracker comments = commentTracker();

            assertThatCode(() -> comments.assertAllCommentsAccounted(unit)).doesNotThrowAnyException();
        });
    }

    @Test
    void missedCommentAccountingFailsAtCompletionWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse("""
                    class Demo {
                        int value; // value
                    }
                    """);
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
            CompilationUnit unit = parse("""
                    class Demo {
                        int value; // value
                    }
                    """);
            CommentTracker comments = commentTracker();
            RawPreservedSource rawPreservedSource = new RawPreservedSource(
                    new RawSource(FormatterOptions.defaults()),
                    comments);
            rawPreservedSource.raw(unit);

            assertThatCode(() -> comments.assertAllCommentsAccounted(unit)).doesNotThrowAnyException();
        });
    }

    @Test
    void formatterCompletesWhenStructuredCommentsAreClaimedWithDebugGuardrailsEnabled() {
        withGuardrails("true", () -> {
            String formatted = new JavaFormatter(FormatterOptions.defaults()).format("""
                    package dev.example;
                    // demo type
                    class Demo {
                        // value comment
                        int value; // trailing value
                    }
                    """);

            assertThat(formatted)
                    .contains("// demo type")
                    .contains("// value comment")
                    .contains("// trailing value");
        });
    }

    @Test
    void transformGuardrailsKeepExistingBehaviorByDefault() {
        withGuardrails(null, () -> {
            CompilationUnit unit = parse("""
                    package dev.example;

                    // demo import
                    import z.Value;

                    // demo type
                    class Demo {}
                    """);

            CompilationUnit transformed = new JavaTransformPipeline(List.of(
                            new ReplacingImportDeclarationTransform(),
                            new ReplacingTypeCommentTransform(),
                            new ReplacingCompilationUnitTransform()))
                    .transform(unit);

            assertThat(transformed).isNotSameAs(unit);
        });
    }

    @Test
    void transformThatReplacesCompilationUnitFailsWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse("class Demo {}");

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingCompilationUnitTransform()))
                            .transform(unit))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("ReplacingCompilationUnitTransform")
                    .hasMessageContaining("different CompilationUnit instance")
                    .hasMessageContaining("original JavaParser tree");
        });
    }

    @Test
    void transformThatReplacesVisibleCommentFailsWhenDebugGuardrailsAreEnabled() {
        withGuardrails("true", () -> {
            CompilationUnit unit = parse("""
                    // demo type
                    class Demo {}
                    """);

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingTypeCommentTransform()))
                            .transform(unit))
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
            CompilationUnit unit = parse("""
                    package dev.example;

                    // demo import
                    import z.Value;

                    class Demo {}
                    """);

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingImportDeclarationTransform()))
                            .transform(unit))
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
            CompilationUnit unit = parse("""
                    package dev.example;

                    // first import
                    import a.First;
                    // second import
                    import b.Second;

                    class Demo {}
                    """);

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new SwappingImportCommentsTransform()))
                            .transform(unit))
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
            CompilationUnit unit = parse("""
                    package dev.example;

                    import z.Value;

                    class Demo {
                        void value() {}
                    }
                    """);

            assertThatThrownBy(() -> new JavaTransformPipeline(List.of(new ReplacingTypeDeclarationTransform()))
                            .transform(unit))
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
            String formatted = new JavaFormatter(FormatterOptions.defaults()).format("""
                    class Demo {
                        // prettier-ignore
                        void messy( ) { int value=1; /* preserved */ }
                    }
                    """);

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
            ImportDeclaration replacement =
                    new ImportDeclaration(imported.getName(), imported.isStatic(), imported.isAsterisk());
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

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true));
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }

    private static CommentTracker commentTracker() {
        return new CommentTracker(new JavaCommentPlacementPolicy());
    }
}
