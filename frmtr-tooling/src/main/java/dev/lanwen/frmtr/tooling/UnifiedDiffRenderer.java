package dev.lanwen.frmtr.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

public final class UnifiedDiffRenderer {

    private static final char LINE_WIDTH_MARKER = '⋮';

    private static final int LINE_WIDTH_RULER_PROXIMITY = 10;

    private UnifiedDiffRenderer() {}

    /**
     * Selects whether rendered diffs stay patch-like or receive terminal-only visual decoration.
     */
    public enum RenderMode {
        /**
         * Renders a plain unified diff that remains suitable for tools expecting patch-shaped output.
         */
        PATCH,

        /**
         * Adds a dotted source-width marker to and around hunk lines near or over the configured formatter width.
         */
        LINE_WIDTH_RULER,
    }

    public static String render(Path displayPath, String original, String formatted) throws IOException {
        return renderPatch(displayPath, original, formatted);
    }

    public static String render(
            Path displayPath, String original, String formatted, int lineWidth, RenderMode renderMode
    ) throws IOException {
        String diff = renderPatch(displayPath, original, formatted);
        return switch (Objects.requireNonNull(renderMode, "renderMode")) {
            case PATCH -> diff;
            case LINE_WIDTH_RULER -> decorateWithLineWidthRuler(diff, lineWidth);
        };
    }

    private static String renderPatch(Path displayPath, String original, String formatted) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("diff --git origin frmtr\n".getBytes(StandardCharsets.UTF_8));
        output.write("--- origin\n".getBytes(StandardCharsets.UTF_8));
        output.write("+++ frmtr\n".getBytes(StandardCharsets.UTF_8));

        RawText oldText = new RawText(original.getBytes(StandardCharsets.UTF_8));
        RawText newText = new RawText(formatted.getBytes(StandardCharsets.UTF_8));
        EditList edits = DiffAlgorithm.getAlgorithm(SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, oldText, newText);
        try (DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setContext(3);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.format(edits, oldText, newText);
            formatter.flush();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    static String decorateWithLineWidthRuler(String diff, int lineWidth) {
        if (lineWidth < 1) {
            throw new IllegalArgumentException("lineWidth must be at least 1");
        }
        var lines = diffLines(diff);
        boolean[] lineWidthMarkers = lineWidthMarkers(lines, lineWidth);
        StringBuilder decorated = new StringBuilder(diff.length());
        for (int i = 0; i < lines.size(); i++) {
            DiffLine diffLine = lines.get(i);
            String line = diffLine.text();
            if (line.startsWith("@@ ")) {
                appendHunkHeader(decorated, line, lineWidth);
                if (diffLine.hasLineEnding()) {
                    decorated.append('\n');
                }
                continue;
            }
            boolean decoratedLine = lineWidthMarkers[i];
            decorated.append(decoratedLine ? decorateLine(line, lineWidth) : line);
            if (diffLine.hasLineEnding()) {
                decorated.append('\n');
            } else if (decoratedLine) {
                decorated.append('\n');
            }
        }
        return decorated.toString();
    }

    private static List<DiffLine> diffLines(String diff) {
        List<DiffLine> lines = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < diff.length()) {
            int lineEnd = diff.indexOf('\n', lineStart);
            boolean hasLineEnding = lineEnd >= 0;
            lines.add(
                new DiffLine(
                    hasLineEnding ? diff.substring(lineStart, lineEnd) : diff.substring(lineStart),
                    hasLineEnding
                )
            );
            lineStart = hasLineEnding ? lineEnd + 1 : diff.length();
        }
        return List.copyOf(lines);
    }

    private static boolean[] lineWidthMarkers(List<DiffLine> lines, int lineWidth) {
        boolean[] markers = new boolean[lines.size()];
        boolean inHunk = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).text();
            if (line.startsWith("@@ ")) {
                inHunk = true;
                continue;
            }
            if (shouldDecorateLine(inHunk, line, lineWidth)) {
                markers[i] = true;
                markNearestNeighbor(lines, markers, i - 1, -1);
                markNearestNeighbor(lines, markers, i + 1, 1);
            }
        }
        return markers;
    }

    private static void markNearestNeighbor(List<DiffLine> lines, boolean[] markers, int index, int step) {
        for (int i = index; i >= 0 && i < lines.size(); i += step) {
            String line = lines.get(i).text();
            if (line.startsWith("@@ ")) {
                return;
            }
            if (isHunkSourceLine(line)) {
                markers[i] = true;
                return;
            }
        }
    }

    private static boolean shouldDecorateLine(boolean inHunk, String line, int lineWidth) {
        if (!inHunk || !isHunkSourceLine(line)) {
            return false;
        }
        int sourceWidth = line.length() - 1;
        int proximityStart = Math.max(0, lineWidth - LINE_WIDTH_RULER_PROXIMITY);
        return sourceWidth >= proximityStart;
    }

    private static boolean isHunkSourceLine(String line) {
        if (line.isEmpty() || line.charAt(0) == '\\') {
            return false;
        }
        char prefix = line.charAt(0);
        return prefix == '+' || prefix == '-' || prefix == ' ';
    }

    private static String decorateLine(String line, int lineWidth) {
        char prefix = line.charAt(0);
        String source = line.substring(1);
        int overflow = source.length() - lineWidth;
        if (overflow <= 0) {
            return prefix + source + " ".repeat(lineWidth - source.length()) + LINE_WIDTH_MARKER;
        }
        return prefix
            + source.substring(0, lineWidth)
            + LINE_WIDTH_MARKER
            + source.substring(lineWidth)
            + "\n"
            + " ".repeat(lineWidth + 1)
            + LINE_WIDTH_MARKER
            + "+"
            + overflow;
    }

    private static void appendHunkHeader(StringBuilder decorated, String line, int lineWidth) {
        decorated.append(line);
        int guideColumn = lineWidth + 1;
        decorated.append(" ".repeat(Math.max(1, guideColumn - line.length())));
        decorated.append(LINE_WIDTH_MARKER).append(' ').append(lineWidth);
    }

    private record DiffLine(String text, boolean hasLineEnding) {}
}
