package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.Problem;
import com.github.javaparser.TokenMgrException;
import dev.lanwen.frmtr.FormatterException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds structured source context for JavaParser failures without deciding how that context should be rendered.
 *
 * <p>The formatter core owns parser positions, source windows, and best-effort enclosing-unit detection because those
 * depend on the original source. CLI, Gradle, and other adapters decide whether to print line numbers, pointers, or
 * summaries from the structured fields on {@link FormatterException}.
 */
final class ParseErrorSourceContext {
    private static final int CONTEXT_LINES = 5;
    private static final int MAX_CONTEXT_LINE_LENGTH = 256;
    private static final int MAX_HEADER_LENGTH = 8192;
    private static final Pattern MESSAGE_POSITION = Pattern.compile("line (\\d+), column (\\d+)");
    private static final Pattern ANNOTATION_HEADER =
            Pattern.compile("\\B@interface\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern TYPE_HEADER =
            Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern CALLABLE_HEADER =
            Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*\\([^{};]*\\)\\s*(?:throws\\s+[^{};]+)?$");
    private static final Set<String> CONTROL_HEADERS = Set.of(
            "assert",
            "catch",
            "do",
            "else",
            "finally",
            "for",
            "if",
            "switch",
            "synchronized",
            "try",
            "while");

    private ParseErrorSourceContext() {}

    static List<FormatterException.SourceProblem> from(String source, List<Problem> problems) {
        if (problems.isEmpty()) {
            return List.of(new FormatterException.SourceProblem(
                    "unknown parse error",
                    Optional.empty(),
                    Optional.empty(),
                    List.of()));
        }
        List<String> lines = source.lines().toList();
        return problems.stream()
                .sorted(Comparator
                        .comparingInt((Problem problem) -> problemPosition(problem)
                                .map(position -> position.line)
                                .orElse(Integer.MAX_VALUE))
                        .thenComparingInt(problem -> problemPosition(problem)
                                .map(position -> position.column)
                                .orElse(Integer.MAX_VALUE))
                        .thenComparing(problem -> Optional.ofNullable(problem.getVerboseMessage()).orElse("")))
                .map(problem -> sourceProblem(source, lines, problem))
                .toList();
    }

    static List<FormatterException.SourceProblem> from(String source, TokenMgrException exception) {
        List<String> lines = source.lines().toList();
        Optional<Position> position = tokenManagerMessagePosition(exception.getMessage());
        return List.of(new FormatterException.SourceProblem(
                problemMessage(exception),
                position.map(ParseErrorSourceContext::sourceLocation),
                position.flatMap(found -> enclosingUnitLine(source, found)),
                position.map(found -> contextLines(lines, found)).orElseGet(List::of)));
    }

    private static FormatterException.SourceProblem sourceProblem(
            String source, List<String> lines, Problem problem) {
        Optional<Position> position = problemPosition(problem);
        return new FormatterException.SourceProblem(
                problemMessage(problem),
                position.map(ParseErrorSourceContext::sourceLocation),
                position.flatMap(found -> enclosingUnitLine(source, found)),
                position.map(found -> contextLines(lines, found)).orElseGet(List::of));
    }

    private static String problemMessage(Problem problem) {
        String message = problem.getVerboseMessage();
        if (message == null || message.isBlank()) {
            return "unknown parse error";
        }
        return message;
    }

    private static FormatterException.SourceLocation sourceLocation(Position position) {
        return new FormatterException.SourceLocation(position.line, position.column);
    }

    private static Optional<Position> problemPosition(Problem problem) {
        return problem.getLocation()
                .flatMap(location -> location.toRange().map(range -> range.begin))
                .filter(Position::valid)
                .or(() -> tokenManagerMessagePosition(problem));
    }

    private static Optional<Position> tokenManagerMessagePosition(Problem problem) {
        return problem.getCause()
                .filter(TokenMgrException.class::isInstance)
                .flatMap(cause -> tokenManagerMessagePosition(cause.getMessage()));
    }

    private static Optional<Position> tokenManagerMessagePosition(String message) {
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

    private static String problemMessage(TokenMgrException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "unknown lexical parse error";
        }
        return message;
    }

    private static List<FormatterException.SourceLine> contextLines(List<String> lines, Position position) {
        if (position.line < 1 || position.line > lines.size()) {
            return List.of();
        }
        int startLine = Math.max(1, position.line - CONTEXT_LINES);
        int endLine = Math.min(lines.size(), position.line + CONTEXT_LINES);
        List<FormatterException.SourceLine> context = new ArrayList<>();
        for (int lineNumber = startLine; lineNumber <= endLine; lineNumber++) {
            context.add(sourceLine(lineNumber, lines.get(lineNumber - 1), position.column));
        }
        return List.copyOf(context);
    }

    /**
     * Preserves the parser's column neighborhood for long lines while keeping every stored line bounded.
     */
    private static FormatterException.SourceLine sourceLine(int lineNumber, String line, int contextColumn) {
        if (line.length() <= MAX_CONTEXT_LINE_LENGTH) {
            return new FormatterException.SourceLine(lineNumber, 1, line);
        }
        int anchorColumn = Math.max(1, Math.min(contextColumn, line.length()));
        int start = Math.max(0, anchorColumn - 1 - (MAX_CONTEXT_LINE_LENGTH / 2));
        if (start + MAX_CONTEXT_LINE_LENGTH > line.length()) {
            start = line.length() - MAX_CONTEXT_LINE_LENGTH;
        }
        return new FormatterException.SourceLine(
                lineNumber,
                start + 1,
                line.substring(start, start + MAX_CONTEXT_LINE_LENGTH));
    }

    private static Optional<FormatterException.SourceLine> enclosingUnitLine(String source, Position position) {
        List<String> lines = source.lines().toList();
        List<OpenBlock> openBlocks = new ArrayList<>();
        StringBuilder header = new StringBuilder();
        ScanState state = ScanState.CODE;
        boolean escaped = false;
        for (int lineNumber = 1; lineNumber <= lines.size() && lineNumber <= position.line; lineNumber++) {
            String line = lines.get(lineNumber - 1);
            int limit = lineNumber == position.line
                    ? Math.min(line.length(), Math.max(0, position.column - 1))
                    : line.length();
            LineScanResult result = scanLine(lineNumber, line, limit, state, escaped, header, openBlocks);
            state = result.state();
            escaped = result.escaped();
            if (state == ScanState.LINE_COMMENT) {
                state = ScanState.CODE;
            }
            if (state == ScanState.STRING_LITERAL || state == ScanState.CHAR_LITERAL) {
                state = ScanState.CODE;
                escaped = false;
            }
            if (state == ScanState.CODE) {
                appendHeader(header, ' ');
            }
        }
        for (int index = openBlocks.size() - 1; index >= 0; index--) {
            Optional<FormatterException.SourceLine> unitLine = openBlocks.get(index).unitLine();
            if (unitLine.isPresent()) {
                return unitLine;
            }
        }
        return Optional.empty();
    }

    private static LineScanResult scanLine(
            int lineNumber,
            String line,
            int limit,
            ScanState initialState,
            boolean initialEscaped,
            StringBuilder header,
            List<OpenBlock> openBlocks) {
        ScanState state = initialState;
        boolean escaped = initialEscaped;
        for (int index = 0; index < limit; index++) {
            char current = line.charAt(index);
            char next = index + 1 < limit ? line.charAt(index + 1) : '\0';
            switch (state) {
                case LINE_COMMENT -> index = limit;
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = ScanState.CODE;
                        index++;
                        appendHeader(header, ' ');
                    }
                }
                case STRING_LITERAL -> {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '"') {
                        state = ScanState.CODE;
                    }
                }
                case CHAR_LITERAL -> {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == '\'') {
                        state = ScanState.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (current == '"' && index + 2 < limit && line.charAt(index + 1) == '"'
                            && line.charAt(index + 2) == '"') {
                        state = ScanState.CODE;
                        index += 2;
                    }
                }
                case CODE -> {
                    if (current == '/' && next == '/') {
                        state = ScanState.LINE_COMMENT;
                        index = limit;
                    } else if (current == '/' && next == '*') {
                        state = ScanState.BLOCK_COMMENT;
                        index++;
                        appendHeader(header, ' ');
                    } else if (current == '"' && index + 2 < limit && line.charAt(index + 1) == '"'
                            && line.charAt(index + 2) == '"') {
                        state = ScanState.TEXT_BLOCK;
                        index += 2;
                        appendHeader(header, ' ');
                    } else if (current == '"') {
                        state = ScanState.STRING_LITERAL;
                        escaped = false;
                        appendHeader(header, ' ');
                    } else if (current == '\'') {
                        state = ScanState.CHAR_LITERAL;
                        escaped = false;
                        appendHeader(header, ' ');
                    } else if (current == '{') {
                        openBlocks.add(new OpenBlock(sourceUnitLine(header.toString(), lineNumber, line, index + 1)));
                        header.setLength(0);
                    } else if (current == '}') {
                        if (!openBlocks.isEmpty()) {
                            openBlocks.removeLast();
                        }
                        header.setLength(0);
                    } else if (current == ';') {
                        header.setLength(0);
                    } else {
                        appendHeader(header, current);
                    }
                }
            }
        }
        return new LineScanResult(state, escaped);
    }

    private static Optional<FormatterException.SourceLine> sourceUnitLine(
            String header, int lineNumber, String line, int braceColumn) {
        String normalized = header.replaceAll("\\s+", " ").trim();
        return isSourceUnit(normalized)
                ? Optional.of(sourceLine(lineNumber, line, braceColumn))
                : Optional.empty();
    }

    private static boolean isSourceUnit(String header) {
        return !header.isEmpty()
                && (ANNOTATION_HEADER.matcher(header).find()
                        || TYPE_HEADER.matcher(header).find()
                        || isCallableUnit(header));
    }

    private static boolean isCallableUnit(String header) {
        Matcher matcher = CALLABLE_HEADER.matcher(header);
        if (!matcher.find()) {
            return false;
        }
        String name = matcher.group(1);
        if (CONTROL_HEADERS.contains(name)) {
            return false;
        }
        String beforeName = header.substring(0, matcher.start(1)).stripTrailing();
        return !beforeName.endsWith("new");
    }

    private static void appendHeader(StringBuilder header, char value) {
        header.append(value);
        if (header.length() > MAX_HEADER_LENGTH) {
            header.delete(0, header.length() - MAX_HEADER_LENGTH);
        }
    }

    private enum ScanState {
        /**
         * Reads ordinary Java source where declarations, braces, and statement separators can affect unit detection.
         */
        CODE,
        /**
         * Ignores text from {@code //} to the end of the current line so comment braces do not affect block tracking.
         */
        LINE_COMMENT,
        /**
         * Ignores text from {@code /*} through {@code *\/} so multi-line comment braces do not affect block tracking.
         */
        BLOCK_COMMENT,
        /**
         * Ignores text inside a double-quoted string literal so literal braces do not affect block tracking.
         */
        STRING_LITERAL,
        /**
         * Ignores text inside a single-quoted character literal so literal braces do not affect block tracking.
         */
        CHAR_LITERAL,
        /**
         * Ignores text inside a text block so multi-line literal braces do not affect block tracking.
         */
        TEXT_BLOCK
    }

    private record OpenBlock(Optional<FormatterException.SourceLine> unitLine) {}

    private record LineScanResult(ScanState state, boolean escaped) {}
}
