package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JavaCommentPlacementPolicyTest {

    @Test
    void requiresExplicitRunInitializationBeforeServingPlacementQueries() {
        JavaCommentPlacementPolicy policy = new JavaCommentPlacementPolicy();
        CompilationUnit unit = parse("class Demo {}");

        assertThatThrownBy(() -> policy.ownComment(unit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been initialized");

        policy.startRun(unit);

        assertThatThrownBy(() -> policy.startRun(unit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already initialized");
    }

    @Test
    void exposesOwnTrailingAndOrphanCommentsFromTheRunMap() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    int value; // field tail

                    // type orphan
                }
                """
        );
        ClassOrInterfaceDeclaration type = unit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        FieldDeclaration field = unit.findFirst(FieldDeclaration.class).orElseThrow();
        JavaCommentPlacementPolicy policy = startedPolicy(unit);

        assertThat(policy.trailingLineComment(field)).hasValueSatisfying(comment -> {
            assertThat(comment.isLine()).isTrue();
            assertThat(comment.comment()).isSameAs(field.getComment().orElseThrow());
        });
        assertThat(type.getOrphanComments()).isNotEmpty();
        assertThat(policy.orphanCommentsInSourceOrder(type))
                .extracting(comment -> comment.comment().getContent().strip())
                .containsExactly("type orphan");
    }

    @Test
    void findsContainedLineCommentsBetweenNeighboringBinaryOperands() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    boolean value(boolean left, boolean right) {
                        return left // keep with left
                                && right;
                    }
                }
                """
        );
        BinaryExpr binary = unit.findFirst(BinaryExpr.class).orElseThrow();
        JavaCommentPlacementPolicy policy = startedPolicy(unit);

        List<JavaCommentTrivia> comments = policy.lineCommentsBetween(binary, binary.getLeft(), binary.getRight());

        assertThat(comments)
                .extracting(comment -> comment.comment().getContent().strip())
                .containsExactly("keep with left");
        assertThat(policy.commentsStartingOnEndLine(binary.getLeft(), comments))
                .extracting(comment -> comment.comment().getContent().strip())
                .containsExactly("keep with left");
    }

    private static JavaCommentPlacementPolicy startedPolicy(CompilationUnit unit) {
        JavaCommentPlacementPolicy policy = new JavaCommentPlacementPolicy();
        policy.startRun(unit);
        return policy;
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
}
