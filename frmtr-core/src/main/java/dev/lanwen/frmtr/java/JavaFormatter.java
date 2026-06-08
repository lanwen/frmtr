package dev.lanwen.frmtr.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.Problem;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaFormatter {
    private static final int PARSE_ERROR_CONTEXT_LINES = 2;
    private static final Pattern MESSAGE_POSITION = Pattern.compile("line (\\d+), column (\\d+)");
    private static final JavaTransformPipeline TRANSFORMS =
            new JavaTransformPipeline(List.of(new ImportSortTransform()));

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
        if (options.requirePragma() && !hasFormatPragma(source)) {
            return source;
        }
        CompilationUnit parsedUnit = parse(source);
        CompilationUnit transformedUnit = TRANSFORMS.transform(parsedUnit);
        SyntaxNodeView.from(transformedUnit);
        JavaPrinter printer = new JavaPrinter(options);
        Doc doc = printer.print(transformedUnit);
        return new DocRenderer(options).render(doc);
    }

    private boolean hasFormatPragma(String source) {
        String stripped = source.stripLeading();
        if (!stripped.startsWith("/**")) {
            return false;
        }
        int end = stripped.indexOf("*/");
        if (end < 0) {
            return false;
        }
        String leadingDocComment = stripped.substring(0, end + 2);
        return leadingDocComment.contains("@format") || leadingDocComment.contains("@prettier");
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
        return problemPosition(problem)
                .map(position -> formatProblemAtPosition(lines, position, problem.getVerboseMessage()))
                .orElse(problem.getVerboseMessage());
    }

    private static Optional<Position> problemPosition(Problem problem) {
        return problem.getLocation()
                .flatMap(location -> location.toRange().map(range -> range.begin))
                .filter(Position::valid)
                .or(() -> messagePosition(problem.getVerboseMessage()));
    }

    private static Optional<Position> messagePosition(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher matcher = MESSAGE_POSITION.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }
        Position position = new Position(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)));
        return position.valid() ? Optional.of(position) : Optional.empty();
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
                    .map(JavaCommentTrivia::from)
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .orElse(Doc.EMPTY);
        }

        Doc trailingLineComment(Node node) {
            return node.getComment()
                    .map(JavaCommentTrivia::from)
                    .filter(JavaCommentTrivia::isLine)
                    .filter(comment -> comment.startsOnEndLine(node))
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .orElse(Doc.EMPTY);
        }

        Doc orphanComments(Node node) {
            return orphanComments(node, ignored -> true);
        }

        Doc orphanComments(Node node, Predicate<Comment> predicate) {
            return Doc.concat(node.getOrphanComments().stream()
                    .map(JavaCommentTrivia::from)
                    .filter(trivia -> predicate.test(trivia.comment()))
                    .filter(this::claim)
                    .map(comment -> Doc.concat(commentDoc(comment), Doc.HARD_LINE))
                    .toList());
        }

        List<Doc> orphanCommentStatements(Node node) {
            return orphanCommentStatements(node, ignored -> true);
        }

        List<Doc> orphanCommentStatements(Node node, Predicate<Comment> predicate) {
            return node.getOrphanComments().stream()
                    .map(JavaCommentTrivia::from)
                    .filter(trivia -> predicate.test(trivia.comment()))
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .toList();
        }

        List<Doc> orphanTriviaCommentStatements(Node node, Predicate<JavaCommentTrivia> predicate) {
            return node.getOrphanComments().stream()
                    .map(JavaCommentTrivia::from)
                    .filter(predicate)
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .toList();
        }

        Doc ownComment(Node node, Predicate<Comment> predicate) {
            return node.getComment()
                    .map(JavaCommentTrivia::from)
                    .filter(trivia -> predicate.test(trivia.comment()))
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .orElse(Doc.EMPTY);
        }

        Doc ownTriviaComment(Node node, Predicate<JavaCommentTrivia> predicate) {
            return node.getComment()
                    .map(JavaCommentTrivia::from)
                    .filter(predicate)
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .orElse(Doc.EMPTY);
        }

        Doc comment(Comment comment) {
            JavaCommentTrivia trivia = JavaCommentTrivia.from(comment);
            return claim(trivia) ? JavaFormatter.commentDoc(trivia) : Doc.EMPTY;
        }

        boolean isPrinted(JavaCommentTrivia trivia) {
            return trivia.isClaimedBy(printed);
        }

        private boolean claim(JavaCommentTrivia trivia) {
            return trivia.claim(printed);
        }
    }

    static Doc commentDoc(Comment comment) {
        return commentDoc(JavaCommentTrivia.from(comment));
    }

    static Doc commentDoc(JavaCommentTrivia trivia) {
        Comment comment = trivia.comment();
        if (trivia.isLine()) {
            LineComment lineComment = (LineComment) comment;
            String text = lineComment.toString().stripTrailing();
            if (text.contains("\n")) {
                return lineDoc(text);
            }
            text = splitAdjacentLineComments("//" + lineComment.getContent().stripTrailing());
            return text.contains("\n") ? lineDoc(text) : Doc.text(text);
        }
        if (trivia.isJavadoc()) {
            JavadocComment javadocComment = (JavadocComment) comment;
            String raw = comment.getTokenRange().map(Object::toString).orElseGet(javadocComment::toString).strip();
            if (raw.lines().count() == 1) {
                return Doc.text(raw);
            }
            return lineDoc(javadocComment.toString().stripTrailing());
        }
        if (trivia.isBlock()) {
            BlockComment blockComment = (BlockComment) comment;
            String text = blockComment.toString();
            String normalized = normalizeBlockComment(text);
            return normalized.equals(text.stripTrailing()) ? Doc.text(normalized) : lineDoc(normalized);
        }
        return lineDoc(comment.toString().stripTrailing());
    }

    private static Doc lineDoc(String value) {
        List<Doc> lines = value.lines().map(Doc::text).toList();
        return Doc.join(Doc.HARD_LINE, lines);
    }

    private static String splitAdjacentLineComments(String value) {
        String split = value.replaceAll("(?<!:)//", System.lineSeparator() + "//");
        return split.startsWith(System.lineSeparator()) ? split.substring(System.lineSeparator().length()) : split;
    }

    private static String normalizeBlockComment(String value) {
        String text = value.stripTrailing();
        List<String> lines = text.lines().toList();
        if (lines.size() < 3 || lines.stream().skip(1).limit(lines.size() - 2)
                .map(String::stripLeading)
                .anyMatch(line -> !line.startsWith("*"))) {
            return text;
        }
        List<String> normalized = new java.util.ArrayList<>();
        normalized.add(lines.getFirst());
        lines.stream()
                .skip(1)
                .limit(lines.size() - 2)
                .map(String::stripLeading)
                .map(line -> " " + line)
                .forEach(normalized::add);
        normalized.add(" */");
        return String.join(System.lineSeparator(), normalized);
    }
}
