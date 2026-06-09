package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Maps JavaParser source positions to offset-based regions in the original source text.
 *
 * <p>This helper owns the conversion from one-based JavaParser line and column coordinates to half-open character
 * offsets, plus raw slicing by those regions. The boundary exists so recovery planning can preserve exact source text,
 * including source line endings, without making each planner or printer repeat line-indexing rules.
 *
 * <p>Callers still decide which AST ranges form safe recovery boundaries, whether raw trailing horizontal whitespace
 * should be preserved for a particular output path, how comments are accounted, and how recovered text is labeled in
 * formatter docs.
 */
final class SourceText {
    private final String source;
    private final int[] lineStartOffsets;
    private final int[] lineContentEndOffsets;

    SourceText(String source) {
        this.source = Objects.requireNonNull(source, "source");
        LineOffsets lineOffsets = lineOffsets(source);
        this.lineStartOffsets = lineOffsets.starts();
        this.lineContentEndOffsets = lineOffsets.contentEnds();
    }

    /**
     * Converts a JavaParser inclusive range into a half-open source region.
     */
    SourceRegion region(Range range) {
        Objects.requireNonNull(range, "range");
        return region(range.begin, range.end);
    }

    /**
     * Converts JavaParser inclusive begin/end positions into a half-open source region.
     *
     * <p>JavaParser ranges point at the last covered source character. The returned region therefore keeps the begin
     * offset inclusive and advances the end offset past the end position so callers can slice the original string
     * without off-by-one adjustments.
     */
    SourceRegion region(Position begin, Position endInclusive) {
        Objects.requireNonNull(begin, "begin");
        Objects.requireNonNull(endInclusive, "endInclusive");
        return new SourceRegion(
                offset(begin),
                offsetAfter(endInclusive),
                begin.line,
                begin.column,
                endInclusive.line,
                endInclusive.column);
    }

    /**
     * Creates a source region directly from half-open offsets.
     *
     * <p>Recovery planners use offset boundaries when a raw gap starts after one parsed sibling and ends before the next
     * one. The line/column span is derived from the same source text so later debug labels do not need to remember the
     * original JavaParser positions.
     */
    SourceRegion region(int beginOffset, int endOffset) {
        validateOffset(beginOffset);
        validateOffset(endOffset);
        if (endOffset < beginOffset) {
            throw new IllegalArgumentException("endOffset must be greater than or equal to beginOffset");
        }
        SourcePosition begin = positionAt(beginOffset);
        SourcePosition end = endOffset == beginOffset ? begin : positionAt(endOffset - 1);
        return new SourceRegion(
                beginOffset,
                endOffset,
                begin.line(),
                begin.column(),
                end.line(),
                end.column());
    }

    /**
     * Returns the zero-based source offset for a JavaParser position.
     */
    int offset(Position position) {
        int lineIndex = lineIndex(position);
        int lineStart = lineStartOffsets[lineIndex];
        int lineContentEnd = lineContentEndOffsets[lineIndex];
        return Math.min(lineStart + position.column - 1, lineContentEnd);
    }

    /**
     * Returns the original source slice covered by {@code region}, preserving all source line endings.
     */
    String slice(SourceRegion region) {
        validateRegion(region);
        return source.substring(region.beginOffset(), region.endOffset());
    }

    /**
     * Returns the raw source slice after applying the formatter's raw trailing-whitespace option.
     *
     * <p>When raw trailing whitespace is not preserved, stripping happens line-by-line without changing the slice's
     * existing LF, CRLF, or CR line separators. This keeps recovered raw islands source-shaped while matching
     * {@link RawSource}'s trailing-horizontal-whitespace policy.
     *
     * <p>This intentionally does not delegate to {@link RawSource#stripTrailingHorizontalWhitespace(String)}. RawSource
     * works from JavaParser token/node text and currently normalizes line separators through line streams; recovered
     * slices are direct substrings, so their LF, CRLF, CR, and trailing final line-separator shape must stay unchanged.
     * Both helpers share the same trailing-horizontal-whitespace policy, but SourceText owns the line-ending-preserving
     * variant recovery needs before inserting raw islands into formatted docs.
     */
    String rawSlice(SourceRegion region, FormatterOptions options) {
        Objects.requireNonNull(options, "options");
        String raw = slice(region);
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    private int offsetAfter(Position position) {
        int lineIndex = lineIndex(position);
        int offset = offset(position);
        int lineContentEnd = lineContentEndOffsets[lineIndex];
        return offset >= lineContentEnd ? offset : offset + 1;
    }

    private int lineIndex(Position position) {
        Objects.requireNonNull(position, "position");
        if (!position.valid() || position.line < 1 || position.column < 1) {
            throw new IllegalArgumentException("position must be valid");
        }
        if (position.line > lineStartOffsets.length) {
            throw new IllegalArgumentException("line %d is outside source".formatted(position.line));
        }
        int lineIndex = position.line - 1;
        int lineLength = lineContentEndOffsets[lineIndex] - lineStartOffsets[lineIndex];
        if (position.column > lineLength + 1) {
            throw new IllegalArgumentException(
                    "column %d is outside line %d".formatted(position.column, position.line));
        }
        return lineIndex;
    }

    private SourcePosition positionAt(int offset) {
        validateOffset(offset);
        int lineIndex = Arrays.binarySearch(lineStartOffsets, offset);
        if (lineIndex < 0) {
            lineIndex = -lineIndex - 2;
        }
        if (lineIndex < 0) {
            lineIndex = 0;
        }
        int columnOffset = Math.min(offset, lineContentEndOffsets[lineIndex]) - lineStartOffsets[lineIndex];
        return new SourcePosition(lineIndex + 1, columnOffset + 1);
    }

    private void validateRegion(SourceRegion region) {
        Objects.requireNonNull(region, "region");
        validateOffset(region.beginOffset());
        validateOffset(region.endOffset());
        if (region.endOffset() < region.beginOffset()) {
            throw new IllegalArgumentException("region endOffset must be greater than or equal to beginOffset");
        }
    }

    private void validateOffset(int offset) {
        if (offset < 0 || offset > source.length()) {
            throw new IllegalArgumentException("offset %d is outside source".formatted(offset));
        }
    }

    private static String stripTrailingHorizontalWhitespace(String text) {
        StringBuilder stripped = new StringBuilder(text.length());
        int lineStart = 0;
        int cursor = 0;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (current == '\r' || current == '\n') {
                appendStrippedLine(text, lineStart, cursor, stripped);
                if (current == '\r' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '\n') {
                    stripped.append("\r\n");
                    cursor += 2;
                } else {
                    stripped.append(current);
                    cursor++;
                }
                lineStart = cursor;
                continue;
            }
            cursor++;
        }
        appendStrippedLine(text, lineStart, text.length(), stripped);
        return stripped.toString();
    }

    private static void appendStrippedLine(String text, int start, int end, StringBuilder stripped) {
        int strippedEnd = end;
        while (strippedEnd > start && isHorizontalWhitespace(text.charAt(strippedEnd - 1))) {
            strippedEnd--;
        }
        stripped.append(text, start, strippedEnd);
    }

    private static boolean isHorizontalWhitespace(char value) {
        return value != '\r' && value != '\n' && Character.isWhitespace(value);
    }

    private static LineOffsets lineOffsets(String source) {
        List<Integer> starts = new ArrayList<>();
        List<Integer> contentEnds = new ArrayList<>();
        starts.add(0);

        int cursor = 0;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (current == '\r' || current == '\n') {
                contentEnds.add(cursor);
                if (current == '\r' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '\n') {
                    cursor += 2;
                } else {
                    cursor++;
                }
                starts.add(cursor);
                continue;
            }
            cursor++;
        }

        contentEnds.add(source.length());
        return new LineOffsets(toIntArray(starts), toIntArray(contentEnds));
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private record SourcePosition(int line, int column) {}

    private record LineOffsets(int[] starts, int[] contentEnds) {}
}
