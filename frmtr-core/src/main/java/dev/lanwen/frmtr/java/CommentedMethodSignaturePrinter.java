package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.MethodDeclaration;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Formats the raw-source escape hatch for method signatures that contain comments.
 *
 * <p>This helper owns the narrow method-signature fallback used when JavaParser keeps comments in the token stream but
 * does not expose them in a structured form that is useful to the normal signature printer. It deliberately does not
 * decide leading-comment attachment, structured body formatting, JavaParser dispatch, or the general method-signature
 * layout used for methods whose signatures can be printed from the AST.
 */
final class CommentedMethodSignaturePrinter {

    private final String indentUnit;

    CommentedMethodSignaturePrinter(FormatterOptions options) {
        this.indentUnit = options.indentUnit();
    }

    /**
     * Returns a raw-source method rendering only for commented signatures with bodies small enough for the fallback.
     *
     * <p>The body-size guard keeps this source-string path from becoming a second statement formatter; larger bodies
     * remain with the structured block printer even when the signature contains comments.
     */
    Optional<String> tryFormat(MethodDeclaration declaration, String rawMethod) {
        if (declaration.getBody().isEmpty()) {
            return Optional.empty();
        }
        if (!hasCommentedMethodSignature(declaration, rawMethod)) {
            return Optional.empty();
        }
        if (!canFormatCommentedMethodSignatureFromRaw(declaration)) {
            return Optional.empty();
        }
        return Optional.of(indentEmbeddedLines(formatCommentedMethod(rawMethod)));
    }

    private boolean hasCommentedMethodSignature(MethodDeclaration declaration, String rawMethod) {
        int bodyStart = rawMethod.indexOf('{');
        if (bodyStart < 0) {
            return false;
        }
        String signature = signatureWithoutLeadingDeclarationAnnotations(
            declaration,
            rawMethod.substring(0, bodyStart)
        );
        return signature.contains("//") || signature.contains("/*");
    }

    /**
     * Removes declaration-annotation lines before deciding whether the method signature needs the raw comment fallback.
     *
     * <p>Annotation expressions own their trailing comments. If this raw fallback sees those comments as method-signature
     * comments, it flattens annotations, method header, and body text together instead of letting the structured
     * declaration printers keep each boundary.
     */
    private String signatureWithoutLeadingDeclarationAnnotations(MethodDeclaration declaration, String signature) {
        int declarationBeginLine = declaration.getRange().map(range -> range.begin.line).orElse(Integer.MIN_VALUE);
        int nameBeginLine = declaration.getName().getRange().map(range -> range.begin.line).orElse(Integer.MIN_VALUE);
        int lastLeadingAnnotationLine = declaration.getAnnotations()
                .stream()
                .flatMap(annotation -> annotation.getRange().stream())
                .filter(range -> range.end.line < nameBeginLine)
                .mapToInt(range -> range.end.line)
                .max()
                .orElse(Integer.MIN_VALUE);
        if (lastLeadingAnnotationLine < declarationBeginLine) {
            return signature;
        }
        List<String> lines = signature.lines().toList();
        int linesToDrop = Math.min(lines.size(), lastLeadingAnnotationLine - declarationBeginLine + 1);
        int firstSignatureLine = linesToDrop;
        int sourceLine = declarationBeginLine + firstSignatureLine;
        // Skip the comment-only and blank trivia lines that sit between the annotations and the method name. They are
        // leading comments the structured path renders (annotationMethodGapComments), not signature content. Blank lines
        // must be skipped too: when source whitespace is collapsed, blank lines can appear between stacked leading
        // comments, and stopping at the first one would leave a leading `//` in the "signature", wrongly routing the
        // method through the raw fallback — which re-indents the preserved blank lines deeper on every pass and never
        // converges.
        while (
            firstSignatureLine < lines.size()
            && sourceLine < nameBeginLine
            && leadingAnnotationGapLine(lines.get(firstSignatureLine).strip())
        ) {
            firstSignatureLine++;
            sourceLine++;
        }
        return String.join("\n", lines.subList(firstSignatureLine, lines.size()));
    }

    private boolean leadingAnnotationGapLine(String line) {
        if (line.isBlank() || isCommentOnlyLine(line)) {
            return true;
        }
        return line.startsWith("/*") || line.startsWith("*") || line.endsWith("*/");
    }

    private boolean canFormatCommentedMethodSignatureFromRaw(MethodDeclaration declaration) {
        return declaration.getBody().map(body -> body.getStatements().size() <= 1).orElse(false);
    }

    private String formatCommentedMethod(String rawMethod) {
        String method = rawMethod.strip();
        int bodyStart = method.indexOf('{');
        int bodyEnd = method.lastIndexOf('}');
        if (bodyStart < 0 || bodyEnd < bodyStart) {
            return method;
        }
        String signature = method.substring(0, bodyStart).stripTrailing();
        String body = method.substring(bodyStart + 1, bodyEnd).strip();
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < open) {
            return method;
        }
        String prefix = CommentedTokenText.tokenLine(CommentedTokenText.tokens(signature.substring(0, open)));
        String parameters = signature.substring(open + 1, close);
        List<String> parameterLines = nonBlankLines(parameters);
        String inlineOpeningLineComment = inlineOpeningLineComment(parameters);
        if (parameterLines.isEmpty()) {
            return formatMethodWithBody(prefix + "()", List.of(), inlineOpeningLineComment, body);
        }
        // JavaParser can lose a single inline block comment before empty parentheses as header trivia, so keep it
        // attached to the method prefix instead of treating it like a parameter comment line. This only applies when the
        // parentheses are otherwise empty: the line must be the block comment and nothing else. A leading block comment
        // followed by a real parameter (e.g. `( /* alpha */ String name )`) is a commented parameter, not an empty list,
        // and must keep its parentheses around the parameter rather than being hoisted to the prefix.
        if (
            parameterLines.size() == 1
            && isBlockCommentOnlyLine(parameterLines.getFirst())
            && !parameters.contains("\n")
        ) {
            return formatMethodWithBody(prefix + " " + parameterLines.getFirst() + "()", List.of(), "", body);
        }
        List<String> leadingComments = new ArrayList<>();
        int cursor = 0;
        while (cursor < parameterLines.size() && isCommentOnlyLine(parameterLines.get(cursor))) {
            leadingComments.add(parameterLines.get(cursor++));
        }
        List<String> trailingComments = new ArrayList<>();
        int end = parameterLines.size();
        while (end > cursor && isCommentOnlyLine(parameterLines.get(end - 1))) {
            trailingComments.add(0, parameterLines.get(--end));
        }
        List<String> parameterParts = parameterLines.subList(cursor, end);
        if (parameterParts.isEmpty()) {
            if (!inlineOpeningLineComment.isEmpty()) {
                return formatMethodWithInlineOpeningComment(prefix + "()", inlineOpeningLineComment, body);
            }
            return formatMethodWithBody(prefix + "()", parameterLines, inlineOpeningLineComment, body);
        }
        List<String> formattedParameterLines = formattedParameterLines(parameterParts);
        // Comment-only lines at the edges of the parameter list stay outside the rebuilt parameter text so leading and
        // trailing comments keep their original side of the parameter declaration.
        if (
            leadingComments.isEmpty()
            && formattedParameterLines.size() == 1
            && !containsLineComment(formattedParameterLines.getFirst())
        ) {
            List<String> suffixComments = new ArrayList<>(trailingComments);
            return formatMethodWithBody(
                prefix + "(" + formattedParameterLines.getFirst() + ")",
                suffixComments,
                "",
                body
            );
        }
        List<String> lines = new ArrayList<>();
        lines.add(prefix + "(");
        leadingComments.forEach(comment -> lines.add("  " + comment));
        formattedParameterLines.forEach(parameterLine -> lines.add("  " + parameterLine));
        lines.add(")");
        return formatMethodWithBody(String.join("\n", lines), trailingComments, "", body);
    }

    private List<String> formattedParameterLines(List<String> parameterParts) {
        List<String> lines = new ArrayList<>();
        StringBuilder parameter = new StringBuilder();
        for (String part : parameterParts) {
            if (isCommentOnlyLine(part)) {
                flushParameterLine(parameter, lines);
                lines.add(part);
                continue;
            }
            if (!parameter.isEmpty()) {
                parameter.append(' ');
            }
            parameter.append(part);
            if (part.endsWith(",")) {
                flushParameterLine(parameter, lines);
            }
        }
        flushParameterLine(parameter, lines);
        return lines;
    }

    private void flushParameterLine(StringBuilder parameter, List<String> lines) {
        if (parameter.isEmpty()) {
            return;
        }
        lines.add(CommentedTokenText.tokenLine(CommentedTokenText.tokens(parameter.toString())));
        parameter.setLength(0);
    }

    private String formatMethodWithBody(
            String signature,
            List<String> suffixComments,
            String inlineOpeningComment,
            String body
    ) {
        List<String> lines = new ArrayList<>();
        if (suffixComments.isEmpty()) {
            if (body.isEmpty()) {
                lines.add(signature + " {}");
            } else if (inlineOpeningComment.isEmpty()) {
                lines.add(signature + " {");
                lines.addAll(formatMethodBodyLines(body));
                lines.add("}");
            } else {
                lines.add(signature + " { " + inlineOpeningComment);
                lines.addAll(formatMethodBodyLines(body));
                lines.add("}");
            }
            return String.join("\n", lines);
        }
        lines.add(signature + " " + suffixComments.getFirst());
        lines.addAll(suffixComments.subList(1, suffixComments.size()));
        if (body.isEmpty()) {
            lines.add("{}");
        } else {
            lines.add("{");
            lines.addAll(formatMethodBodyLines(body));
            lines.add("}");
        }
        return String.join("\n", lines);
    }

    private String formatMethodWithInlineOpeningComment(String signature, String comment, String body) {
        if (body.isEmpty()) {
            return signature + " {} " + comment;
        }
        List<String> lines = new ArrayList<>();
        lines.add(signature + " { " + comment);
        lines.addAll(formatMethodBodyLines(body));
        lines.add("}");
        return String.join("\n", lines);
    }

    private List<String> formatMethodBodyLines(String body) {
        return body.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(line -> "  " + line)
                .toList();
    }

    private List<String> nonBlankLines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * Finds a line comment that starts before any parameter newline so it can be preserved after the opening brace.
     */
    private String inlineOpeningLineComment(String parameters) {
        String stripped = parameters.stripLeading();
        if (!stripped.startsWith("//")) {
            return "";
        }
        int commentStart = parameters.indexOf("//");
        if (parameters.substring(0, commentStart).contains("\n")) {
            return "";
        }
        int commentEnd = stripped.indexOf('\n');
        return commentEnd < 0 ? stripped.stripTrailing() : stripped.substring(0, commentEnd).stripTrailing();
    }

    private boolean isCommentOnlyLine(String line) {
        if (line.startsWith("//") || line.startsWith("*")) {
            return true;
        }
        return line.startsWith("/*") && (!line.contains("*/") || line.endsWith("*/"));
    }

    /**
     * Reports whether a parameter-list line is a single block comment with no parameter text after it.
     *
     * <p>{@code "/* c *}{@code /"} qualifies but {@code "/* c *}{@code / String name"} does not: the latter is a commented
     * parameter, so the closing {@code *}{@code /} is not the end of the line.
     */
    private boolean isBlockCommentOnlyLine(String line) {
        String stripped = line.strip();
        return stripped.startsWith("/*") && stripped.endsWith("*/") && stripped.indexOf("*/") == stripped.length() - 2;
    }

    private boolean containsLineComment(String line) {
        return line.contains("//");
    }

    private String indentEmbeddedLines(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= 1) {
            return text;
        }
        List<String> indented = new ArrayList<>();
        indented.add(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            indented.add(indentUnit + lines[i]);
        }
        return String.join("\n", indented);
    }
}
