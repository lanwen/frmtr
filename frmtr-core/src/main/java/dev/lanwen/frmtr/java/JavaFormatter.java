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
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocDebugRenderer;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
        var configuration = new ParserConfiguration()
                .setLanguageLevel(javaParserLanguageLevel(options.javaLanguageLevel()))
                .setStoreTokens(true)
                .setAttributeComments(true);
        this.parser = new JavaParser(configuration);
    }

    public String format(String source) {
        if (options.requirePragma() && !hasFormatPragma(source)) {
            return source;
        }
        Doc doc = printDoc(source);
        return new DocRenderer(options).render(doc);
    }

    /**
     * Returns the structural document tree produced after parsing, transforms, and Java printing.
     */
    public String debugDoc(String source) {
        return DocDebugRenderer.render(printDoc(source));
    }

    private Doc printDoc(String source) {
        JavaParseResult parseResult = parse(source);
        // TODO: Expose parseResult.problems() through a future diagnostics/debug result API.
        SourceText sourceText = new SourceText(source);
        if (parseResult.hasParseProblems()) {
            unsupportedRecoveryReason(parseResult.compilationUnit())
                    .ifPresent(reason -> {
                        throw parseFailure(
                                source,
                                parseResult.problems(),
                                new ParseProblemException(parseResult.problems()),
                                Optional.of(reason));
                    });
        }
        CompilationUnit printableUnit = parseResult.hasParseProblems()
                ? parseResult.compilationUnit()
                : TRANSFORMS.transform(parseResult.compilationUnit());
        SyntaxNodeView.from(printableUnit);
        JavaPrinter printer = new JavaPrinter(options, sourceText, parseResult.hasParseProblems());
        return printer.print(printableUnit);
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

    private JavaParseResult parse(String source) {
        try {
            ParseResult<CompilationUnit> result =
                    parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
            return parseResult(source, result);
        } catch (ParseProblemException exception) {
            throw parseFailure(source, exception.getProblems(), exception, thrownBeforeRecoveredCompilationUnit());
        }
    }

    private JavaParseResult parseResult(String source, ParseResult<CompilationUnit> result) {
        if (result.getResult().isEmpty()) {
            throw parseFailure(
                    source,
                    result.getProblems(),
                    new ParseProblemException(result.getProblems()),
                    noRecoveredCompilationUnit());
        }
        var parseResult = new JavaParseResult(
                result.getResult().orElseThrow(),
                result.getProblems(),
                !result.isSuccessful() || !result.getProblems().isEmpty());
        if (parseResult.hasParseProblems()
                && options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            throw parseFailure(
                    source,
                    parseResult.problems(),
                    new ParseProblemException(parseResult.problems()),
                    Optional.empty());
        }
        return parseResult;
    }

    private FormatterException parseFailure(
            String source,
            List<Problem> problems,
            ParseProblemException cause,
            Optional<String> recoveryFailureReason) {
        String message = "Unable to parse Java source:" + System.lineSeparator();
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.RECOVER
                && recoveryFailureReason.isPresent()) {
            message += recoveryFailureReason.orElseThrow()
                    + System.lineSeparator()
                    + System.lineSeparator();
        }
        message += formatProblems(source, problems);
        return new FormatterException(message, cause);
    }

    private Optional<String> parseProblemsUnsupportedByCurrentPrinters() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of(
                "Parse-error recovery is configured, but this recovery slice only supports malformed block statement lists, class/interface/record member declaration lists, and top-level declaration lists.");
    }

    private Optional<String> unsupportedRecoveryReason(CompilationUnit unit) {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        List<Node> recoveredNodes = unit.stream()
                .filter(node -> node.getParsed() != Node.Parsedness.PARSED)
                .toList();
        if (recoveredNodes.isEmpty()) {
            return parseProblemsUnsupportedByCurrentPrinters();
        }
        return recoveredNodes.stream()
                .filter(node -> !isSupportedRecovery(node))
                .findFirst()
                .map(node -> parseProblemsUnsupportedByCurrentPrinters().orElseThrow()
                        + " Unsupported recovered node: "
                        + node.getClass().getSimpleName()
                        + node.getRange().map(range -> " at " + range).orElse("."));
    }

    private static boolean isSupportedRecovery(Node recoveredNode) {
        return isSupportedBlockStatementListRecovery(recoveredNode)
                || isSupportedMemberDeclarationListRecovery(recoveredNode)
                || isSupportedTopLevelDeclarationListRecovery(recoveredNode);
    }

    private static boolean isSupportedBlockStatementListRecovery(Node recoveredNode) {
        if (recoveredNode instanceof BlockStmt) {
            return true;
        }
        return nearestBlockStatementListSibling(recoveredNode).isPresent();
    }

    private static Optional<Statement> nearestBlockStatementListSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof Statement statement && isBlockStatementListSibling(statement)) {
                return Optional.of(statement);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isBlockStatementListSibling(Statement statement) {
        return statement.getParentNode()
                .filter(BlockStmt.class::isInstance)
                .map(BlockStmt.class::cast)
                .filter(block -> block.getStatements().contains(statement))
                .isPresent();
    }

    private static boolean isSupportedMemberDeclarationListRecovery(Node recoveredNode) {
        return nearestClassInterfaceOrRecordMemberSibling(recoveredNode)
                .filter(member -> member.getParsed() != Node.Parsedness.PARSED)
                .isPresent();
    }

    private static Optional<BodyDeclaration<?>> nearestClassInterfaceOrRecordMemberSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof BodyDeclaration<?> member && isClassInterfaceOrRecordMember(member)) {
                return Optional.of(member);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isClassInterfaceOrRecordMember(BodyDeclaration<?> member) {
        return member.getParentNode()
                .filter(parent -> {
                    if (parent instanceof ClassOrInterfaceDeclaration declaration) {
                        return declaration.getMembers().contains(member);
                    }
                    if (parent instanceof RecordDeclaration declaration) {
                        return declaration.getMembers().contains(member);
                    }
                    return false;
                })
                .isPresent();
    }

    private static boolean isSupportedTopLevelDeclarationListRecovery(Node recoveredNode) {
        if (!(recoveredNode instanceof TypeDeclaration<?> type)
                || type.getParsed() == Node.Parsedness.PARSED) {
            return false;
        }
        return type.getParentNode()
                .filter(CompilationUnit.class::isInstance)
                .map(CompilationUnit.class::cast)
                .filter(unit -> unit.getTypes().contains(type))
                .map(unit -> unit.getTypes().stream()
                        .anyMatch(sibling -> sibling != type && sibling.getParsed() == Node.Parsedness.PARSED))
                .orElse(false);
    }

    private Optional<String> noRecoveredCompilationUnit() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of("Parse-error recovery is configured, but JavaParser did not return a compilation unit to recover.");
    }

    private Optional<String> thrownBeforeRecoveredCompilationUnit() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of(
                "Parse-error recovery is configured, but JavaParser threw before returning a recovered compilation unit.");
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
                .repeat(" ", width + 2)
                .repeat("-", Math.max(0, position.column - 1))
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

    private record JavaParseResult(
            CompilationUnit compilationUnit,
            List<Problem> problems,
            boolean hasParseProblems) {
        private JavaParseResult {
            problems = List.copyOf(problems);
        }
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
