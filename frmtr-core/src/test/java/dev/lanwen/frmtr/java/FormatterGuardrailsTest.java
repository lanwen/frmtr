package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.doc.Doc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class FormatterGuardrailsTest {
    @Test
    void duplicateCommentClaimsKeepExistingSkipBehaviorByDefault() {
        withGuardrails(null, () -> {
            JavaFormatter.CommentTracker comments = new JavaFormatter.CommentTracker();
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
            JavaFormatter.CommentTracker comments = new JavaFormatter.CommentTracker();
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
            JavaFormatter.CommentTracker comments = new JavaFormatter.CommentTracker();
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
