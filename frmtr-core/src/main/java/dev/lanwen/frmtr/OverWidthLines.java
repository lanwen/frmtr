package dev.lanwen.frmtr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides whether a single rendered output line is a <em>suspicious</em> over-width line: one that exceeds a configured
 * line width <em>and</em> still carries an obvious breakable Java construct the formatter could have split further.
 *
 * <p>The formatter treats line width as a target, not a hard cap. Prose comments, text blocks, formatter-off regions,
 * and atomic string/char literals legitimately overrun it and cannot be broken, so flagging every over-width line would
 * be noise. This helper owns the one definition of "breakable over-width" shared by two callers that must agree:
 * <ul>
 *   <li>the {@code SuspiciousLineWidthAudit} fixture gate (test-only), which layers a TSV allowlist and SHA hashing on
 *       top of this predicate; and</li>
 *   <li>the {@code --check --verify} CLI path, which emits informational warnings for the same lines.</li>
 * </ul>
 *
 * <p>Width is measured the way the formatter measures it — as the UTF-16 {@link String#length()} of the line, matching
 * {@code LayoutWidth} — after trailing whitespace is stripped. Before the breakable check runs, string, char, and
 * text-block literals are masked to empty pairs and {@code //} / {@code /* *}{@code /} comment bodies are removed, so an
 * operator that only appears <em>inside</em> a literal or comment never counts as breakable. Text blocks and block
 * comments span lines, so a {@link Scanner} carries that masking state across an entire formatted document; callers
 * that already hold a full formatted string should walk it through {@link #scan(String, int)}.
 *
 * <p><strong>Formatter pragmas.</strong> Inside a {@code @formatter:off}…{@code @formatter:on} range, or a
 * {@code frmtr-ignore-start}…{@code frmtr-ignore-end} range (and on a single line marked {@code frmtr-ignore}), the
 * formatter emits the user's <em>original</em> source verbatim — it deliberately does not reformat. An over-width line
 * inside such a region is the user's own choice that frmtr was told to leave alone, so warning "frmtr could break this"
 * there would contradict the opt-out. Because the {@link Scanner} already surfaces every comment body it masks, it owns
 * the one pragma-range state machine that drives this suppression, so the CLI warning and the audit gate stay in
 * lockstep: a line inside a disabled pragma range (and the pragma comment line itself) is never flagged. The recognized
 * markers — {@code @formatter:off}/{@code @formatter:on}, {@code frmtr-ignore-start}/{@code frmtr-ignore-end}, and a
 * bare {@code frmtr-ignore} — match {@code FormatterPragmas} exactly (substring match, with the {@code -start}/{@code
 * -end} forms taking precedence over a bare {@code frmtr-ignore}). A marker that appears only inside a string/char/
 * text-block literal is masked away before pragma scanning, so it never toggles suppression.
 *
 * <p>This helper intentionally leaves three decisions to its callers: how to report a finding, whether a line is exempt
 * for a reason it cannot see (allowlist entries), and whether a finding should affect any exit status. It reports; it
 * does not gate.
 */
public final class OverWidthLines {

    private static final Pattern CALL_CHAIN = Pattern.compile(
        "(?:\\.[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(|\\)\\s*\\.[A-Za-z_$][A-Za-z0-9_$]*)"
    );

    private static final Pattern CAST_OR_LAMBDA = Pattern.compile(
        "(?:->|\\([A-Za-z_$][A-Za-z0-9_$]*(?:\\s*&\\s*[A-Za-z_$][A-Za-z0-9_$]*)+\\)\\s*[A-Za-z_$])"
    );

    private OverWidthLines() {}

    /**
     * Scans a full formatted document, returning every suspicious over-width line in source order.
     *
     * <p>The document is split on any line terminator and walked through a single {@link Scanner} so multi-line text
     * blocks and block comments are masked correctly, and so the scanner's formatter-off / {@code frmtr-ignore}
     * pragma-range state suppresses lines the formatter emitted verbatim. This overload applies no allowlist policy;
     * pragma suppression is built in, which is exactly what the CLI warning path wants.
     */
    public static List<OverWidthLine> scan(String formatted, int lineWidth) {
        Scanner scanner = new Scanner();
        String[] lines = formatted.split("\\R", -1);
        List<OverWidthLine> findings = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            scanner.suspicious(lines[index], index + 1, lineWidth).ifPresent(findings::add);
        }
        return List.copyOf(findings);
    }

    /**
     * Tests a single, self-contained line (no open text block or block comment carried in from a previous line) against
     * the shared predicate. Convenience for callers that genuinely have one isolated line; documents should use
     * {@link #scan(String, int)} or a long-lived {@link Scanner} so cross-line masking state is preserved.
     */
    public static boolean isSuspiciousOverWidth(String line, int lineWidth) {
        return new Scanner().suspicious(line, 1, lineWidth).isPresent();
    }

    /**
     * Reports whether already-masked code (literals collapsed, comments removed) of the given measured width is a
     * suspicious over-width line: it combines the exact width threshold and the breakable set so the {@link Scanner}
     * applies one predicate after masking.
     */
    private static boolean isSuspiciousOverWidthCode(String maskedCode, int lineWidth) {
        return maskedCode.stripTrailing().length() > lineWidth && suspiciousBreakableSyntax(maskedCode);
    }

    /**
     * Returns {@code true} when masked code carries a breakable Java construct: a binary/ternary operator at top level,
     * a comma-heavy argument or declaration list, a fluent call chain, a multi-type {@code throws} clause, or a cast /
     * lambda arrow. These mirror the constructs the formatter knows how to split across lines.
     */
    static boolean suspiciousBreakableSyntax(String code) {
        String compact = code.strip();
        return containsBinaryOrTernary(compact)
            || commaHeavyCallOrDeclaration(compact)
            || CALL_CHAIN.matcher(compact).find()
            || throwsList(compact)
            || CAST_OR_LAMBDA.matcher(compact).find();
    }

    private static boolean containsBinaryOrTernary(String code) {
        return code.contains(" + ")
            || code.contains(" && ")
            || code.contains(" || ")
            || (code.contains(" ? ") && code.contains(" : "));
    }

    private static boolean commaHeavyCallOrDeclaration(String code) {
        return count(code, ',') >= 2 && code.contains("(") && code.contains(")");
    }

    private static boolean throwsList(String code) {
        return code.contains(" throws ") && code.contains(",");
    }

    private static int count(String value, char needle) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == needle) {
                count++;
            }
        }
        return count;
    }

    /**
     * A finding: a rendered line that overruns {@code lineWidth} and still contains a breakable construct.
     *
     * @param lineNumber 1-based line number within the scanned document
     * @param width      the line's measured width ({@link String#length()})
     * @param lineWidth  the configured limit the line exceeded
     * @param line       the original rendered line (unmasked), for display
     */
    public record OverWidthLine(int lineNumber, int width, int lineWidth, String line) {}

    /**
     * Masks literals and comments as it walks a document line by line, carrying open text-block and block-comment state
     * across line boundaries so a construct that legitimately overruns the width is never mistaken for breakable code,
     * and tracking the formatter-off / {@code frmtr-ignore} pragma ranges it sees in those comment bodies so lines the
     * formatter emitted verbatim are never flagged.
     *
     * <p>The {@link MaskedLine} it produces exposes the masked {@code code} (for the width + breakable predicate) and
     * any {@code commentBodies} on the line (so a caller can scan them for additional pragmas it cares about). The
     * scanner owns the over-width pragma policy itself; allowlist policy stays with the caller.
     */
    public static final class Scanner {

        private boolean inTextBlock;

        private boolean inBlockComment;

        private final PragmaRanges pragmaRanges = new PragmaRanges();

        /**
         * Masks the given line, advancing cross-line state, and returns a finding if the masked line is a suspicious
         * over-width line. Lines whose open text block or block comment has not yet closed are never flagged, because a
         * mid-literal/mid-comment line cannot be broken further. Lines inside a formatter-off / {@code frmtr-ignore}
         * disabled range — and the pragma comment line that opens, closes, or carries the marker — are likewise never
         * flagged, because the formatter emitted them verbatim from source.
         */
        public Optional<OverWidthLine> suspicious(String line, int lineNumber, int lineWidth) {
            boolean carriedRawRegion = inTextBlock || inBlockComment;
            boolean carriedPragmaSkip = pragmaRanges.inDisabledRange();
            MaskedLine masked = mask(line);
            boolean pragmaSkip = pragmaRanges.apply(masked.commentBodies());
            if (carriedRawRegion || carriedPragmaSkip || pragmaSkip) {
                return Optional.empty();
            }
            if (!isSuspiciousOverWidthCode(masked.code(), lineWidth)) {
                return Optional.empty();
            }
            return Optional.of(new OverWidthLine(lineNumber, line.length(), lineWidth, line));
        }

        /**
         * Masks one line, collapsing string/char/text-block literals to empty pairs and stripping comment bodies, while
         * recording any comment text encountered so callers can inspect it for pragmas. Updates the scanner's open
         * text-block / block-comment state for the next line.
         */
        public MaskedLine mask(String line) {
            StringBuilder code = new StringBuilder(line.length());
            List<String> commentBodies = new ArrayList<>();
            int index = 0;
            while (index < line.length()) {
                if (inTextBlock) {
                    int delimiter = textBlockDelimiter(line, index);
                    if (delimiter == -1) {
                        return new MaskedLine(code.toString(), List.copyOf(commentBodies));
                    }
                    code.append("\"\"");
                    inTextBlock = false;
                    index = delimiter + 3;
                    continue;
                }
                if (inBlockComment) {
                    int end = line.indexOf("*/", index);
                    if (end == -1) {
                        commentBodies.add(line.substring(index));
                        return new MaskedLine(code.toString(), List.copyOf(commentBodies));
                    }
                    commentBodies.add(line.substring(index, end));
                    inBlockComment = false;
                    index = end + 2;
                    continue;
                }

                char current = line.charAt(index);
                if (current == '/' && index + 1 < line.length()) {
                    char next = line.charAt(index + 1);
                    if (next == '/') {
                        commentBodies.add(line.substring(index + 2));
                        break;
                    }
                    if (next == '*') {
                        inBlockComment = true;
                        index += 2;
                        continue;
                    }
                }
                if (line.startsWith("\"\"\"", index)) {
                    code.append("\"\"");
                    inTextBlock = true;
                    index += 3;
                    continue;
                }
                if (current == '"' || current == '\'') {
                    code.append(current).append(current);
                    index = skipLiteral(line, index + 1, current);
                    continue;
                }

                code.append(current);
                index++;
            }
            return new MaskedLine(code.toString(), List.copyOf(commentBodies));
        }
    }

    /**
     * The formatter-off / {@code frmtr-ignore} pragma-range state machine, driven by the comment bodies the
     * {@link Scanner} masks out of each line. It is the single source of truth for over-width pragma suppression shared
     * by the CLI warning and the fixture audit: a line is suppressed when it falls inside an open
     * {@code @formatter:off}…{@code @formatter:on} or {@code frmtr-ignore-start}…{@code frmtr-ignore-end} range, when it
     * opens or closes such a range, or when it carries a bare {@code frmtr-ignore}.
     *
     * <p>Markers are recognized by substring, matching {@code FormatterPragmas}: the {@code frmtr-ignore-start} /
     * {@code frmtr-ignore-end} forms are tested before a bare {@code frmtr-ignore} so the longer markers are not
     * mistaken for the single-line one. Because the {@code -start}/{@code -end} and {@code @formatter:off}/{@code :on}
     * markers only ever toggle range state, a bare {@code frmtr-ignore} suppresses only the line that carries it.
     */
    static final class PragmaRanges {

        private boolean inFormatterOff;

        private boolean inFrmtrIgnoreRange;

        /** Whether a prior line opened a formatter-off or {@code frmtr-ignore} range that has not yet closed. */
        boolean inDisabledRange() {
            return inFormatterOff || inFrmtrIgnoreRange;
        }

        /**
         * Folds one line's comment bodies into the range state and reports whether <em>this</em> line is suppressed by a
         * marker it carries. Range toggles take effect immediately, so the line that opens or closes a range is itself
         * suppressed, as is a line carrying a bare {@code frmtr-ignore}.
         */
        boolean apply(List<String> commentBodies) {
            boolean formatterOff = false;
            boolean formatterOn = false;
            boolean frmtrIgnore = false;
            boolean frmtrIgnoreStart = false;
            boolean frmtrIgnoreEnd = false;
            for (String comment : commentBodies) {
                if (comment.contains("@formatter:off")) {
                    formatterOff = true;
                }
                if (comment.contains("@formatter:on")) {
                    formatterOn = true;
                }
                if (comment.contains("frmtr-ignore-start")) {
                    frmtrIgnoreStart = true;
                } else if (comment.contains("frmtr-ignore-end")) {
                    frmtrIgnoreEnd = true;
                } else if (comment.contains("frmtr-ignore")) {
                    frmtrIgnore = true;
                }
            }

            boolean lineSuppressed = frmtrIgnore
                || frmtrIgnoreStart
                || frmtrIgnoreEnd
                || formatterOff
                || formatterOn;

            if (frmtrIgnoreStart) {
                inFrmtrIgnoreRange = true;
            }
            if (frmtrIgnoreEnd) {
                inFrmtrIgnoreRange = false;
            }
            if (formatterOff) {
                inFormatterOff = true;
            }
            if (formatterOn) {
                inFormatterOff = false;
            }
            return lineSuppressed;
        }
    }

    /**
     * The result of masking one line: {@code code} with literals/comments removed (for the breakable predicate) and the
     * raw {@code commentBodies} seen on the line (so callers can scan them for pragmas).
     */
    public record MaskedLine(String code, List<String> commentBodies) {}

    private static int textBlockDelimiter(String line, int index) {
        while (index < line.length()) {
            int delimiter = line.indexOf("\"\"\"", index);
            if (delimiter == -1) {
                return -1;
            }
            if (!escaped(line, delimiter)) {
                return delimiter;
            }
            index = delimiter + 1;
        }
        return -1;
    }

    private static boolean escaped(String line, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && line.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static int skipLiteral(String line, int index, char delimiter) {
        boolean escaped = false;
        while (index < line.length()) {
            char current = line.charAt(index++);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == delimiter) {
                return index;
            }
        }
        return index;
    }
}
