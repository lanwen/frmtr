package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.comments.Comment;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class JavaCommentTriviaTest {
    @Test
    void classifiesJavaParserCommentKinds() {
        CompilationUnit unit = parse("""
                /** type doc */
                class Demo {
                    // value line
                    int value; /* value block */
                }
                """);

        List<JavaCommentKind> kinds = unit.getAllContainedComments().stream()
                .map(JavaCommentTrivia::from)
                .map(JavaCommentTrivia::kind)
                .toList();

        assertThat(kinds).contains(JavaCommentKind.JAVADOC, JavaCommentKind.LINE, JavaCommentKind.BLOCK);
    }

    @Test
    void delegatesSourceLineQueriesToCommentIndex() {
        CompilationUnit unit = parse("""
                class Demo {
                    int value; // value line
                }
                """);
        FieldDeclaration field = unit.findFirst(FieldDeclaration.class).orElseThrow();
        JavaCommentTrivia comment = field.getComment().map(JavaCommentTrivia::from).orElseThrow();

        assertThat(comment.isLine()).isTrue();
        assertThat(comment.beginLine(Integer.MAX_VALUE)).isEqualTo(2);
        assertThat(comment.endLine(Integer.MAX_VALUE)).isEqualTo(2);
        assertThat(comment.startsOnEndLine(field)).isTrue();
    }

    @Test
    void claimsCommentsByIdentityForTrackerState() {
        Comment comment = parse("""
                class Demo {
                    // value line
                    int value;
                }
                """).findFirst(FieldDeclaration.class).orElseThrow().getComment().orElseThrow();
        JavaCommentTrivia trivia = JavaCommentTrivia.from(comment);
        Set<Comment> claimed = Collections.newSetFromMap(new IdentityHashMap<>());

        assertThat(trivia.isClaimedBy(claimed)).isFalse();
        assertThat(trivia.claim(claimed)).isTrue();
        assertThat(trivia.isClaimedBy(claimed)).isTrue();
        assertThat(JavaCommentTrivia.from(comment).claim(claimed)).isFalse();
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
