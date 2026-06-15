package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocDebugRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class RecoveredSourceRegionsTest {

    @Test
    void emitsLabeledRawDocForRecoveredRegion() {
        String source = "class Demo {\n"
            + "  int before;\n"
            + "  int value; // inside\n"
            + "  int after;\n"
            + "}\n";
        CompilationUnit unit = parse(source);
        RecoveredSourceRegions recovered = recoveredSourceRegions(source, commentTracker());
        SourceRegion region = region(source, "int value; // inside");

        Doc doc = recovered.raw(unit, region, "member");

        assertThat(DocDebugRenderer.render(doc)).isEqualTo(
            """
                Label("java.recoveredRegion:member@3:3-3:22")
                  Text("int value; // inside")"""
        );
    }

    @Test
    void rawAccountsCommentsFullyContainedByRecoveredRegion() {
        withGuardrails("true", () -> {
            String source = "class Demo {\n" + "  int value; // inside\n" + "}\n";
            CompilationUnit unit = parse(source);
            CommentTracker comments = commentTracker();
            RecoveredSourceRegions recovered = recoveredSourceRegions(source, comments);

            recovered.raw(unit, region(source, "int value; // inside"), "member");

            assertThatCode(() -> comments.assertAllCommentsAccounted(unit)).doesNotThrowAnyException();
        });
    }

    @Test
    void leavesCommentsOutsideRecoveredRegionForStructuredAccounting() {
        withGuardrails("true", () -> {
            String source = "class Demo {\n" + "  int value; // inside\n" + "  int after; // outside\n" + "}\n";
            CompilationUnit unit = parse(source);
            CommentTracker comments = commentTracker();
            RecoveredSourceRegions recovered = recoveredSourceRegions(source, comments);

            recovered.raw(unit, region(source, "int value; // inside"), "member");

            assertThatThrownBy(() -> comments.assertAllCommentsAccounted(unit))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("unclaimed comment")
                    .hasMessageContaining("// outside")
                    .hasMessageNotContaining("// inside");
        });
    }

    @Test
    void detectsCommentsCrossingRecoveredRegionBoundaries() {
        String source = "class Demo {\n" + "  int value; /* crossing */\n" + "}\n";
        CompilationUnit unit = parse(source);
        SourceText sourceText = new SourceText(source);
        Comment comment = unit.getAllContainedComments().getFirst();
        SourceRegion commentRegion = sourceText.region(comment.getRange().orElseThrow());
        SourceRegion crossingRegion = sourceText.region(commentRegion.beginOffset() + 2, commentRegion.endOffset());
        RecoveredSourceRegions recovered = recoveredSourceRegions(source, commentTracker());

        RecoveredSourceRegions.CrossingCommentBoundaryException thrown = catchThrowableOfType(
            RecoveredSourceRegions.CrossingCommentBoundaryException.class,
            () -> recovered.raw(
                unit,
                crossingRegion,
                "member"
            )
        );

        assertThat(thrown)
                .hasMessageContaining("Recovered source region")
                .hasMessageContaining("crosses")
                .hasMessageContaining("BlockComment");
        assertThat(thrown.region()).isEqualTo(crossingRegion);
        assertThat(thrown.crossingComments()).containsExactly(comment);
    }

    @Test
    void rejectsRangeLessCommentsBeforeRecoveredRawAccounting() {
        String source = "class Demo {\n" + "  int value;\n" + "}\n";
        CompilationUnit unit = parse(source);
        LineComment rangeLessComment = new LineComment("range-less");
        unit.addOrphanComment(rangeLessComment);
        RecoveredSourceRegions recovered = recoveredSourceRegions(source, commentTracker());
        SourceRegion region = region(source, "int value;");

        RecoveredSourceRegions.CrossingCommentBoundaryException thrown = catchThrowableOfType(
            RecoveredSourceRegions.CrossingCommentBoundaryException.class,
            () -> recovered.raw(
                unit,
                region,
                "member"
            )
        );

        assertThat(rangeLessComment.getRange()).isEmpty();
        assertThat(unit.getAllContainedComments()).contains(rangeLessComment);
        assertThat(thrown)
                .hasMessageContaining("cannot safely account")
                .hasMessageContaining("LineComment")
                .hasMessageContaining("unknown range");
        assertThat(thrown.region()).isEqualTo(region);
        assertThat(thrown.crossingComments()).containsExactly(rangeLessComment);
    }

    @Test
    void doesNotAccountCommentsWhenRecoveredRawDocConstructionFails() {
        withGuardrails("true", () -> {
            String source = "class Demo {\n" + "  int value; // inside\n" + "}\n";
            CompilationUnit unit = parse(source);
            CommentTracker comments = commentTracker();
            RecoveredSourceRegions recovered = recoveredSourceRegions(source, comments);

            assertThatThrownBy(() -> recovered.raw(unit, region(source, "int value; // inside"), " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("diagnosticKind must not be blank");

            assertThatThrownBy(() -> comments.assertAllCommentsAccounted(unit))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("unclaimed comment")
                    .hasMessageContaining("// inside");
        });
    }

    private static RecoveredSourceRegions recoveredSourceRegions(String source, CommentTracker comments) {
        return new RecoveredSourceRegions(new SourceText(source), FormatterOptions.defaults(), comments);
    }

    private static SourceRegion region(String source, String raw) {
        int begin = source.indexOf(raw);
        assertThat(begin).isNotNegative();
        return new SourceText(source).region(begin, begin + raw.length());
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
