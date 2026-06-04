package dev.lanwen.frmtr.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.Problem;
import com.github.javaparser.Position;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class JavaFormatter {
    private static final int PARSE_ERROR_CONTEXT_LINES = 2;

    private final FormatterOptions options;
    private final JavaParser parser;

    public JavaFormatter(FormatterOptions options) {
        this.options = options;
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(javaParserLanguageLevel(options.javaLanguageLevel()))
                .setStoreTokens(true)
                .setAttributeComments(true);
        this.parser = new JavaParser(configuration);
    }

    public String format(String source) {
        CompilationUnit unit = parse(source);
        SyntaxNodeView.from(unit);
        JavaPrinter printer = new JavaPrinter(options);
        Doc doc = printer.print(unit);
        return new DocRenderer(options).render(doc);
    }

    private CompilationUnit parse(String source) {
        try {
            ParseResult<CompilationUnit> result =
                    parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                throw new FormatterException(
                        "Unable to parse Java source:"
                                + System.lineSeparator()
                                + formatProblems(source, result.getProblems()),
                        new ParseProblemException(result.getProblems()));
            }
            return result.getResult().orElseThrow();
        } catch (ParseProblemException exception) {
            throw new FormatterException(
                    "Unable to parse Java source:"
                            + System.lineSeparator()
                            + formatProblems(source, exception.getProblems()),
                    exception);
        }
    }

    private static ParserConfiguration.LanguageLevel javaParserLanguageLevel(
            FormatterOptions.JavaLanguageLevel languageLevel) {
        return switch (languageLevel) {
            case UNSET -> null;
            case LATEST_AVAILABLE -> ParserConfiguration.LanguageLevel.BLEEDING_EDGE;
            case JAVA_8 -> ParserConfiguration.LanguageLevel.JAVA_8;
            case JAVA_9 -> ParserConfiguration.LanguageLevel.JAVA_9;
            case JAVA_10 -> ParserConfiguration.LanguageLevel.JAVA_10;
            case JAVA_11 -> ParserConfiguration.LanguageLevel.JAVA_11;
            case JAVA_12 -> ParserConfiguration.LanguageLevel.JAVA_12;
            case JAVA_13 -> ParserConfiguration.LanguageLevel.JAVA_13;
            case JAVA_14 -> ParserConfiguration.LanguageLevel.JAVA_14;
            case JAVA_15 -> ParserConfiguration.LanguageLevel.JAVA_15;
            case JAVA_16 -> ParserConfiguration.LanguageLevel.JAVA_16;
            case JAVA_17 -> ParserConfiguration.LanguageLevel.JAVA_17;
            case JAVA_18 -> ParserConfiguration.LanguageLevel.JAVA_18;
            case JAVA_19 -> ParserConfiguration.LanguageLevel.JAVA_19;
            case JAVA_20 -> ParserConfiguration.LanguageLevel.JAVA_20;
            case JAVA_21 -> ParserConfiguration.LanguageLevel.JAVA_21;
            case JAVA_22 -> ParserConfiguration.LanguageLevel.JAVA_22;
            case JAVA_23 -> ParserConfiguration.LanguageLevel.JAVA_23;
            case JAVA_24 -> ParserConfiguration.LanguageLevel.JAVA_24;
            case JAVA_25 -> ParserConfiguration.LanguageLevel.JAVA_25;
        };
    }

    private static String formatProblems(String source, List<Problem> problems) {
        List<String> lines = source.lines().toList();
        return problems.stream()
                .sorted(Comparator.comparing(problem -> problem.getLocation().map(Object::toString).orElse("")))
                .map(problem -> formatProblem(lines, problem))
                .reduce((left, right) -> left + parseProblemSeparator() + right)
                .orElse("unknown parse error");
    }

    private static String parseProblemSeparator() {
        return System.lineSeparator()
                + System.lineSeparator()
                + "// ..."
                + System.lineSeparator()
                + System.lineSeparator();
    }

    private static String formatProblem(List<String> lines, Problem problem) {
        return problem.getLocation()
                .flatMap(location -> location.toRange().map(range -> range.begin))
                .filter(Position::valid)
                .map(position -> formatProblemAtPosition(lines, position, problem.getVerboseMessage()))
                .orElse(problem.getVerboseMessage());
    }

    private static String formatProblemAtPosition(List<String> lines, Position position, String message) {
        if (position.line < 1 || position.line > lines.size()) {
            return message;
        }
        int startLine = Math.max(1, position.line - PARSE_ERROR_CONTEXT_LINES);
        int endLine = Math.min(lines.size(), position.line + PARSE_ERROR_CONTEXT_LINES);
        int width = Integer.toString(endLine).length();
        StringBuilder formatted = new StringBuilder();
        appendSourceLines(formatted, lines, startLine, position.line, width);
        formatted.append(System.lineSeparator())
                .append(" ".repeat(width + 2))
                .append("-".repeat(Math.max(0, position.column - 1)))
                .append("^")
                .append(System.lineSeparator());
        formatted.append(message);
        if (position.line < endLine) {
            formatted.append(System.lineSeparator());
            appendSourceLines(formatted, lines, position.line + 1, endLine, width);
        }
        return formatted.toString();
    }

    private static void appendSourceLines(StringBuilder formatted, List<String> lines, int startLine, int endLine, int width) {
        for (int line = startLine; line <= endLine; line++) {
            if (!formatted.isEmpty()) {
                formatted.append(System.lineSeparator());
            }
            formatted.append(String.format("%" + width + "d  %s", line, lines.get(line - 1)));
        }
    }

    static final class CommentTracker {
        private final Set<Comment> printed = Collections.newSetFromMap(new IdentityHashMap<>());

        Doc leading(Node node) {
            return node.getComment()
                    .filter(printed::add)
                    .map(JavaFormatter::commentDoc)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .orElse(Doc.EMPTY);
        }

        Doc trailingLineComment(Node node) {
            return node.getComment()
                    .filter(LineComment.class::isInstance)
                    .filter(comment -> sameEndLine(node, comment))
                    .filter(printed::add)
                    .map(JavaFormatter::commentDoc)
                    .orElse(Doc.EMPTY);
        }

        Doc orphanComments(Node node) {
            return orphanComments(node, ignored -> true);
        }

        Doc orphanComments(Node node, Predicate<Comment> predicate) {
            return Doc.concat(node.getOrphanComments().stream()
                    .filter(predicate)
                    .filter(printed::add)
                    .map(comment -> Doc.concat(commentDoc(comment), Doc.HARD_LINE))
                    .toList());
        }

        List<Doc> orphanCommentStatements(Node node) {
            return node.getOrphanComments().stream()
                    .filter(printed::add)
                    .map(JavaFormatter::commentDoc)
                    .toList();
        }

        private boolean sameEndLine(Node node, Comment comment) {
            return node.getRange()
                    .flatMap(nodeRange -> comment.getRange()
                            .map(commentRange -> nodeRange.end.line == commentRange.begin.line))
                    .orElse(false);
        }
    }

    static Doc commentDoc(Comment comment) {
        if (comment instanceof LineComment lineComment) {
            return Doc.text("//" + lineComment.getContent().stripTrailing());
        }
        if (comment instanceof JavadocComment javadocComment) {
            return Doc.text(javadocComment.toString().stripTrailing());
        }
        if (comment instanceof BlockComment blockComment) {
            return Doc.text(blockComment.toString().stripTrailing());
        }
        return Doc.text(comment.toString().stripTrailing());
    }
}
