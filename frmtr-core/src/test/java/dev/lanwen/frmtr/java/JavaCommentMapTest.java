package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JavaCommentMapTest {

    @Test
    void matchesJavaParserWhenOwnCommentsAreExcludedFromTheirNodeContainedComments() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    int value; // field own
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        FieldDeclaration field = unit.findFirst(FieldDeclaration.class).orElseThrow();

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(field.getComment()).isPresent();
        assertThat(map.containedComments(field))
                .extracting(JavaCommentTrivia::comment)
                .doesNotContain(field.getComment().orElseThrow());
    }

    @Test
    void matchesJavaParserOrphanFirstOrderBeforeChildOwnComments() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    int value; // field own

                    // type orphan
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        ClassOrInterfaceDeclaration type = onlyType(unit);

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(type.getOrphanComments())
                .extracting(comment -> comment.getContent().strip())
                .containsExactly("type orphan");
        assertThat(commentContents(map.containedComments(type))).containsExactly("type orphan", "field own");
    }

    @Test
    void matchesJavaParserChildOwnBeforeChildContainedComments() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    // method own
                    void run() {
                        // statement own
                        call();
                    }
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        ClassOrInterfaceDeclaration type = onlyType(unit);

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(commentContents(map.containedComments(type))).containsExactly("method own", "statement own");
    }

    @Test
    void matchesJavaParserForNestedStatementComments() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    void run(boolean ready) {
                        if (ready) {
                            while (next()) {
                                // nested call
                                call();
                            }
                        }
                    }
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        IfStmt ifStatement = unit.findFirst(IfStmt.class).orElseThrow();

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(commentContents(map.containedComments(ifStatement))).containsExactly("nested call");
    }

    @Test
    void matchesJavaParserForMethodCallArgumentComments() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    void run(int first, int second, int third) {
                        call(
                                first,
                                /* second argument */ second,
                                // third argument
                                third);
                    }
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        MethodCallExpr call = unit.findAll(MethodCallExpr.class)
                .stream()
                .filter(expression -> expression.getNameAsString().equals("call"))
                .findFirst()
                .orElseThrow();

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(commentContents(map.containedComments(call))).containsExactly("second argument", "third argument");
    }

    @Test
    void matchesJavaParserForArrayInitializerComments() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    int[] values = {
                            // first value
                            1,
                            /* second value */ 2
                    };
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        ArrayInitializerExpr initializer = unit.findFirst(ArrayInitializerExpr.class).orElseThrow();

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(commentContents(map.containedComments(initializer))).containsExactly("first value", "second value");
    }

    @Test
    void matchesJavaParserForLambdaAndConditionalComments() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    java.util.function.IntUnaryOperator op = value -> value > 0
                            ? /* positive */ value
                            : /* negative */ -value;
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        LambdaExpr lambda = unit.findFirst(LambdaExpr.class).orElseThrow();
        ConditionalExpr conditional = unit.findFirst(ConditionalExpr.class).orElseThrow();

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(commentContents(map.containedComments(lambda))).containsExactly("positive", "negative");
        assertThat(commentContents(map.containedComments(conditional))).containsExactly("positive", "negative");
    }

    @Test
    void matchesJavaParserForRangeLessOrphanComments() {
        CompilationUnit unit = parse("class Demo {}");
        LineComment rangeLess = new LineComment("range-less orphan");
        unit.addOrphanComment(rangeLess);
        JavaCommentMap map = JavaCommentMap.from(unit);

        assertAllContainedCommentsMatchJavaParser(unit, map);
        assertThat(map.orphanComments(unit).getFirst().comment()).isSameAs(rangeLess);
        assertThat(map.containedComments(unit).getFirst().comment()).isSameAs(rangeLess);
    }

    @Test
    void reusesCanonicalTriviaWrappersForRepeatedRawCommentIdentities() {
        CompilationUnit unit = parse(
            """
                class Demo {
                    int value; // field own

                    // type orphan
                }
                """
        );
        JavaCommentMap map = JavaCommentMap.from(unit);
        ClassOrInterfaceDeclaration type = onlyType(unit);
        FieldDeclaration field = unit.findFirst(FieldDeclaration.class).orElseThrow();

        JavaCommentTrivia fieldOwn = map.ownComment(field).orElseThrow();
        JavaCommentTrivia containedFieldOwn = map.containedComments(type)
                .stream()
                .filter(comment -> comment.comment() == fieldOwn.comment())
                .findFirst()
                .orElseThrow();
        JavaCommentTrivia typeOrphan = map.orphanComments(type).getFirst();
        JavaCommentTrivia containedTypeOrphan = map.containedComments(type)
                .stream()
                .filter(comment -> comment.comment() == typeOrphan.comment())
                .findFirst()
                .orElseThrow();

        assertThat(map.ownComment(field).orElseThrow()).isSameAs(fieldOwn);
        assertThat(containedFieldOwn).isSameAs(fieldOwn);
        assertThat(containedTypeOrphan).isSameAs(typeOrphan);
    }

    private static void assertAllContainedCommentsMatchJavaParser(CompilationUnit unit, JavaCommentMap map) {
        List<Node> nodes = unit.stream().toList();
        for (Node node : nodes) {
            assertThat(map.containedComments(node))
                    .as("contained comments for %s", node.getClass().getSimpleName())
                    .extracting(JavaCommentTrivia::comment)
                    .containsExactlyElementsOf(node.getAllContainedComments());
        }
    }

    private static List<String> commentContents(List<JavaCommentTrivia> comments) {
        return comments.stream().map(comment -> comment.comment().getContent().strip()).toList();
    }

    private static ClassOrInterfaceDeclaration onlyType(CompilationUnit unit) {
        return unit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
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
