package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FormatterRunner {
    private FormatterRunner() {}

    public static FormatRunResult check(
            Path displayRoot, List<Path> files, FormatterOptions options, boolean includeDiffs) {
        return check(displayRoot, files, options, includeDiffs, UnifiedDiffRenderer.RenderMode.PATCH);
    }

    public static FormatRunResult check(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode) {
        return new FormatRunResult(selectedFiles(displayRoot, files).stream()
                .map(file -> checkFile(displayRoot, file, options, includeDiffs, diffRenderMode))
                .toList());
    }

    public static FormatRunResult write(Path displayRoot, List<Path> files, FormatterOptions options) {
        return new FormatRunResult(selectedFiles(displayRoot, files).stream()
                .map(file -> writeFile(displayRoot, file, options))
                .toList());
    }

    private static FormatFileResult checkFile(
            Path displayRoot,
            Path file,
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = Frmtr.format(original, options);
            if (formatted.equals(original)) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.UNCHANGED, "", null);
            }
            String diff = includeDiffs
                    ? UnifiedDiffRenderer.render(displayPath, original, formatted, options.lineWidth(), diffRenderMode)
                    : "";
            return new FormatFileResult(file, displayPath, FormatFileStatus.CHANGED, diff, null);
        } catch (FormatterException | IOException exception) {
            return new FormatFileResult(file, displayPath, FormatFileStatus.FAILED, "", exception);
        }
    }

    private static FormatFileResult writeFile(Path displayRoot, Path file, FormatterOptions options) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = Frmtr.format(original, options);
            if (formatted.equals(original)) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.UNCHANGED, "", null);
            }
            try {
                Files.writeString(file, formatted, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.WRITTEN_PARTIALLY, "", exception);
            }
            return new FormatFileResult(file, displayPath, FormatFileStatus.WRITTEN, "", null);
        } catch (FormatterException | IOException exception) {
            return new FormatFileResult(file, displayPath, FormatFileStatus.FAILED, "", exception);
        }
    }

    private static List<Path> selectedFiles(Path displayRoot, List<Path> files) {
        Path root = displayRoot.toAbsolutePath().normalize();
        Set<Path> selected = new LinkedHashSet<>();
        files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(path -> displayPath(root, path).toString()))
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private static Path displayPath(Path displayRoot, Path file) {
        Path root = displayRoot.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            return root.relativize(absolute);
        }
        return absolute;
    }
}
