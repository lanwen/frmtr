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
        return Optional.of(indentEmbeddedLines(formatCommentedMethod(declaration, rawMethod)));
    }

    private boolean hasCommentedMethodSignature(MethodDeclaration declaration, String rawMethod) {
        int bodyStart = bodyOpeningBrace(rawMethod);
        if (bodyStart < 0) {
            return false;
        }
        String signature = splitLeadingDeclarationAnnotations(
            declaration,
            rawMethod.substring(0, bodyStart)
        ).signatureText();
        return signature.contains("//") || signature.contains("/*");
    }

    /**
     * Removes declaration-annotation lines before deciding whether the method signature needs the raw comment fallback.
     *
     * <p>Annotation expressions own their trailing comments. If this raw fallback sees those comments as method-signature
     * comments, it flattens annotations, method header, and body text together instead of letting the structured
     * declaration printers keep each boundary.
     */
    private SignaturePrefix splitLeadingDeclarationAnnotations(MethodDeclaration declaration, String text) {
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
            return new SignaturePrefix("", text);
        }
        List<String> lines = text.lines().toList();
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
        return new SignaturePrefix(
            String.join("\n", lines.subList(0, firstSignatureLine)),
            String.join("\n", lines.subList(firstSignatureLine, lines.size()))
        );
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

    private String formatCommentedMethod(MethodDeclaration declaration, String rawMethod) {
        SignaturePrefix signature = splitLeadingDeclarationAnnotations(declaration, rawMethod.strip());
        if (signature.leadingText().isEmpty()) {
            return formatCommentedMethod(signature.signatureText());
        }
        List<String> formatted = new ArrayList<>();
        signature.leadingText()
                .lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .forEach(formatted::add);
        formatted.add(formatCommentedMethod(signature.signatureText()));
        return String.join("\n", formatted);
    }

    private String formatCommentedMethod(String rawMethod) {
        String method = rawMethod.strip();
        int bodyStart = bodyOpeningBrace(method);
        int bodyEnd = bodyClosingBrace(method);
        if (bodyStart < 0 || bodyEnd < bodyStart) {
            return method;
        }
        String signature = method.substring(0, bodyStart).stripTrailing();
        int close = lastCloseParenOutsideComment(signature);
        int open = matchingOpenParenthesis(signature, close);
        if (open < 0 || close < open) {
            return method;
        }
        String body = method.substring(bodyStart + 1, bodyEnd);
        // The text between the parameter-list `)` and the real body `{` is the gap comment(s) that JavaParser keeps in
        // the token stream but does not expose structurally. Now that the body brace is found comment-aware, this region
        // is no longer the swallowed body; carry any comment token here in a dedicated gap-comment channel, separate from
        // the parameter-trailing suffix comments, so it renders on its own line between the signature and `{` (preserving
        // the source shape) rather than being pulled up onto the signature line.
        List<String> gapComments = signatureGapComments(signature.substring(close + 1));
        String prefix = CommentedTokenText.tokenLine(CommentedTokenText.tokens(signature.substring(0, open)));
        String parameters = signature.substring(open + 1, close);
        List<String> parameterLines = nonBlankLines(parameters);
        String inlineOpeningLineComment = inlineOpeningLineComment(parameters);
        if (parameterLines.isEmpty()) {
            if (!gapComments.isEmpty()) {
                return formatMethodWithBody(prefix + "()", List.of(), gapComments, "", body);
            }
            return formatMethodWithBody(prefix + "()", List.of(), List.of(), inlineOpeningLineComment, body);
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
            return formatMethodWithBody(prefix + " " + parameterLines.getFirst() + "()", List.of(), List.of(), "", body);
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
            return formatMethodWithBody(
                prefix + "()",
                new ArrayList<>(parameterLines),
                gapComments,
                inlineOpeningLineComment,
                body
            );
        }
        List<String> formattedParameterLines = formattedParameterLines(parameterParts);
        // Comment-only lines at the edges of the parameter list stay outside the rebuilt parameter text so leading and
        // trailing comments keep their original side of the parameter declaration.
        if (
            leadingComments.isEmpty()
            && formattedParameterLines.size() == 1
            && !containsLineComment(formattedParameterLines.getFirst())
        ) {
            return formatMethodWithBody(
                prefix + "(" + formattedParameterLines.getFirst() + ")",
                new ArrayList<>(trailingComments),
                gapComments,
                "",
                body
            );
        }
        List<String> lines = new ArrayList<>();
        lines.add(prefix + "(");
        leadingComments.forEach(comment -> lines.add("  " + comment));
        formattedParameterLines.forEach(parameterLine -> lines.add("  " + parameterLine));
        lines.add(")");
        return formatMethodWithBody(
            String.join("\n", lines),
            new ArrayList<>(trailingComments),
            gapComments,
            "",
            body
        );
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

    /**
     * Assembles a commented method's signature, gap comments, parameter suffix comments, and body into the rendered
     * lines.
     *
     * <p>Two distinct comment channels feed in. {@code suffixComments} are the parameter-trailing/leading {@code //}
     * comments that JavaParser keeps attached to the parameter list; the first of them renders on the signature line
     * (the source shape for {@code foo(int x) // note}). {@code gapComments} are the {@code //} comments written alone
     * between the parameter-list {@code )} and the body {@code {}; preserving the issue #23 source shape, each renders on
     * its own line below the signature, with the {@code {} dropped onto the next line. The opening brace must never share
     * a line with a {@code //} gap comment or it would comment the brace out.
     *
     * <p>When {@code gapComments} is empty the rendering is exactly the historical behavior so every pre-existing golden
     * stays byte-identical. A gap comment and an {@code inlineOpeningComment} are assumed not to co-occur (they originate
     * from mutually exclusive source regions); if both are somehow present the gap comment wins its own line and the
     * inline opening comment is dropped, since a brace-line {@code //} comment cannot be rendered safely.
     */
    private String formatMethodWithBody(
            String signature,
            List<String> suffixComments,
            List<String> gapComments,
            String inlineOpeningComment,
            String body
    ) {
        List<String> lines = new ArrayList<>();
        if (suffixComments.isEmpty() && gapComments.isEmpty()) {
            if (bodyIsEmpty(body)) {
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
        if (suffixComments.isEmpty()) {
            lines.add(signature);
        } else {
            lines.add(signature + " " + suffixComments.getFirst());
            lines.addAll(suffixComments.subList(1, suffixComments.size()));
        }
        // Gap comments render on their own lines between the signature and `{`; the brace must stay off a `//` line.
        lines.addAll(gapComments);
        if (bodyIsEmpty(body)) {
            lines.add("{}");
        } else {
            lines.add("{");
            lines.addAll(formatMethodBodyLines(body));
            lines.add("}");
        }
        return String.join("\n", lines);
    }

    private String formatMethodWithInlineOpeningComment(String signature, String comment, String body) {
        if (bodyIsEmpty(body)) {
            return signature + " {} " + comment;
        }
        List<String> lines = new ArrayList<>();
        lines.add(signature + " { " + comment);
        lines.addAll(formatMethodBodyLines(body));
        lines.add("}");
        return String.join("\n", lines);
    }

    private boolean bodyIsEmpty(String body) {
        return body.strip().isEmpty();
    }

    private List<String> formatMethodBodyLines(String body) {
        List<String> lines = body.lines().toList();
        int start = firstNonBlankLine(lines);
        int end = lastNonBlankLine(lines);
        if (start > end) {
            return List.of();
        }
        List<String> bodyLines = lines.subList(start, end + 1);
        int commonIndent = bodyLines.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(this::leadingWhitespace)
                .min()
                .orElse(0);
        return bodyLines.stream()
                .map(line -> removeCommonIndent(line, commonIndent).stripTrailing())
                .filter(line -> !line.isEmpty())
                .map(line -> "  " + line)
                .toList();
    }

    private int firstNonBlankLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return lines.size();
    }

    private int lastNonBlankLine(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private int leadingWhitespace(String line) {
        int cursor = 0;
        while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private String removeCommonIndent(String line, int commonIndent) {
        int removable = Math.min(commonIndent, leadingWhitespace(line));
        return line.substring(removable);
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
        if (line.startsWith("//")) {
            return true;
        }
        if (line.startsWith("/*")) {
            int close = line.indexOf("*/");
            return close < 0 || close == line.length() - 2;
        }
        if (line.startsWith("*")) {
            int close = line.indexOf("*/");
            return close < 0 || close == line.length() - 2;
        }
        return false;
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

    /**
     * Finds the first {@code &#123;} that opens the method body, skipping any {@code &#123;} that appears inside a line or
     * block comment in the raw method text.
     *
     * <p>The signature-comment fallback runs on raw source where a {@code //} or {@code /* *}{@code /} comment can sit
     * between the parameter-list {@code )} and the body {@code &#123;}, and that comment text may itself contain braces
     * (for example {@code // marks &#123; the bit &#125;}). A plain {@code indexOf('&#123;')} would point at the comment's
     * brace and de-comment the rest of the comment into the body, producing non-compiling output (issue #23). This scan
     * is alignment-independent: it works on the possibly re-sliced signature string rather than mapping AST ranges, which
     * are unreliable here. When no comment precedes the body brace it returns exactly the same offset as
     * {@code indexOf('&#123;')}, so the normal case is byte-identical.
     */
    private int bodyOpeningBrace(String text) {
        int cursor = 0;
        while (cursor < text.length()) {
            cursor = skipComment(text, cursor);
            if (cursor >= text.length()) {
                break;
            }
            if (text.charAt(cursor) == '{') {
                return cursor;
            }
            cursor++;
        }
        return -1;
    }

    /**
     * Finds the last {@code &#125;} that closes the method body, skipping any {@code &#125;} that appears inside a line or
     * block comment. The counterpart to {@link #bodyOpeningBrace(String)} for the body's closing brace; when no comment
     * contains a {@code &#125;} it returns exactly the same offset as {@code lastIndexOf('&#125;')}.
     */
    private int bodyClosingBrace(String text) {
        int cursor = 0;
        int lastBrace = -1;
        while (cursor < text.length()) {
            cursor = skipComment(text, cursor);
            if (cursor >= text.length()) {
                break;
            }
            if (text.charAt(cursor) == '}') {
                lastBrace = cursor;
            }
            cursor++;
        }
        return lastBrace;
    }

    /**
     * Advances past a comment that begins at {@code cursor}, returning the index just after it; returns {@code cursor}
     * unchanged when no comment starts there. A {@code //} comment is consumed to end-of-line and a {@code /* *}{@code /}
     * block to its closing {@code *}{@code /} (or end-of-text when unterminated), so brace scans never see braces that
     * live inside comment spans.
     */
    private int skipComment(String text, int cursor) {
        if (text.startsWith("//", cursor)) {
            int newline = text.indexOf('\n', cursor + 2);
            return newline < 0 ? text.length() : newline;
        }
        if (text.startsWith("/*", cursor)) {
            int close = text.indexOf("*/", cursor + 2);
            return close < 0 ? text.length() : close + 2;
        }
        return cursor;
    }

    /**
     * Finds the last {@code )} that is not inside a line or block comment.
     *
     * <p>When a {@code //} or {@code /* *}{@code /} gap comment sits between the parameter list and the body it can carry
     * its own {@code )} (for example {@code // table[code] |= (1 &lt;&lt; 3)}). A plain {@code lastIndexOf(')')} would then
     * point at the comment's {@code )}, treating the comment text as the signature tail and de-commenting it. This scan
     * skips comment spans, so it lands on the real parameter-list close paren; with no comment present it returns exactly
     * the same offset as {@code lastIndexOf(')')}.
     */
    private int lastCloseParenOutsideComment(String text) {
        int cursor = 0;
        int lastClose = -1;
        while (cursor < text.length()) {
            int afterComment = skipComment(text, cursor);
            if (afterComment != cursor) {
                cursor = afterComment;
                continue;
            }
            if (text.charAt(cursor) == ')') {
                lastClose = cursor;
            }
            cursor++;
        }
        return lastClose;
    }

    /**
     * Extracts the comment token(s) that sit between the parameter-list {@code )} and the body {@code &#123;}.
     *
     * <p>Once {@link #bodyOpeningBrace(String)} points at the real body brace, this region is the gap comment JavaParser
     * keeps in the token stream but does not expose structurally. Returning it lets the caller carry it in the dedicated
     * gap-comment channel so it renders verbatim on its own line between the signature and {@code &#123;}, preserving the
     * source shape. Non-comment text here (there should be none for a well-formed signature) is ignored.
     */
    private List<String> signatureGapComments(String gap) {
        return nonBlankLines(gap).stream().filter(this::isCommentOnlyLine).toList();
    }

    private int matchingOpenParenthesis(String signature, int close) {
        if (close < 0 || close >= signature.length()) {
            return -1;
        }
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char current = signature.charAt(i);
            if (current == ')') {
                depth++;
            } else if (current == '(') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private record SignaturePrefix(String leadingText, String signatureText) {}
}
