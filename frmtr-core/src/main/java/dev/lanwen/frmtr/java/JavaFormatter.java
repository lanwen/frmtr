package dev.lanwen.frmtr.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ParseStart;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class JavaFormatter {
    private final FormatterOptions options;
    private final JavaParser parser;

    public JavaFormatter(FormatterOptions options) {
        this.options = options;
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                .setStoreTokens(true)
                .setAttributeComments(true);
        this.parser = new JavaParser(configuration);
    }

    public String format(String source) {
        CompilationUnit unit = parse(source);
        SyntaxNodeView.from(unit);
        JavaPrinter printer = new JavaPrinter();
        Doc doc = printer.print(unit);
        return new DocRenderer(options).render(doc);
    }

    private CompilationUnit parse(String source) {
        try {
            ParseResult<CompilationUnit> result =
                    parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                throw new FormatterException(
                        "Unable to parse Java source:" + System.lineSeparator() + formatProblems(result.getProblems()),
                        new ParseProblemException(result.getProblems()));
            }
            return result.getResult().orElseThrow();
        } catch (ParseProblemException exception) {
            throw new FormatterException(
                    "Unable to parse Java source:" + System.lineSeparator() + formatProblems(exception.getProblems()),
                    exception);
        }
    }

    private static String formatProblems(List<Problem> problems) {
        return problems.stream()
                .sorted(Comparator.comparing(problem -> problem.getLocation().map(Object::toString).orElse("")))
                .map(Problem::getVerboseMessage)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("unknown parse error");
    }

    static final class CommentTracker {
        private final Set<Comment> printed = new HashSet<>();

        Doc leading(Node node) {
            return node.getComment()
                    .filter(printed::add)
                    .map(JavaFormatter::commentDoc)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .orElse(Doc.EMPTY);
        }

        Doc orphanComments(Node node) {
            return Doc.concat(node.getOrphanComments().stream()
                    .filter(printed::add)
                    .map(comment -> Doc.concat(commentDoc(comment), Doc.HARD_LINE))
                    .toList());
        }
    }

    static Doc commentDoc(Comment comment) {
        if (comment instanceof LineComment lineComment) {
            return Doc.text("//" + lineComment.getContent().stripTrailing());
        }
        if (comment instanceof JavadocComment javadocComment) {
            return Doc.text("/**" + javadocComment.getContent().stripTrailing() + "*/");
        }
        if (comment instanceof BlockComment blockComment) {
            return Doc.text("/*" + blockComment.getContent().stripTrailing() + "*/");
        }
        return Doc.text(comment.toString().stripTrailing());
    }
}
