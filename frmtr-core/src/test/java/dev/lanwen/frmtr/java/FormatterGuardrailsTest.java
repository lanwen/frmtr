package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class FormatterGuardrailsTest {
    @Test
    void duplicateCommentClaimsKeepExistingSkipBehaviorByDefault() {
        withGuardrails(null, () -> {
            CommentTracker comments = new CommentTracker();
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
            CommentTracker comments = new CommentTracker();
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
            CommentTracker comments = new CommentTracker();
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
            CommentTracker comments = new CommentTracker();

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
            CommentTracker comments = new CommentTracker();

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
            CommentTracker comments = new CommentTracker();
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
}
