package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "frmtr",
        mixinStandardHelpOptions = true,
        description = "Formats Java source.")
public final class Main implements Callable<Integer> {
    @Option(names = "--check", description = "Check whether files are already formatted.")
    boolean check;

    @Option(names = "--diff", description = "With --check, print unified diffs for files that need formatting.")
    boolean diff;

    @Option(names = "--write", description = "Rewrite files in place.")
    boolean write;

    @Option(names = "--stacktrace", description = "Print stack traces for formatter and I/O failures.")
    boolean stacktrace;

    @Option(names = "--line-width", description = "Target line width.", defaultValue = "100")
    int lineWidth;

    @Option(
            names = "--java-level",
            description = "Java parser language level. Use LATEST_AVAILABLE by default or UNSET for raw parser mode.",
            defaultValue = "LATEST_AVAILABLE",
            converter = JavaLanguageLevelConverter.class)
    FormatterOptions.JavaLanguageLevel javaLanguageLevel;

    @Parameters(arity = "0..*", paramLabel = "SELECTOR", description = "Java files, directories, globs, or comma-separated selectors.")
    List<String> selectors = List.of();

    private final PrintWriter out;
    private final PrintWriter err;
    private final Path workingDirectory;
    private String stdin;

    public Main() {
        this(new PrintWriter(System.out, true), new PrintWriter(System.err, true), Path.of("."), null);
    }

    Main(PrintWriter out, PrintWriter err, String stdin) {
        this(out, err, Path.of("."), stdin);
    }

    Main(PrintWriter out, PrintWriter err, Path workingDirectory, String stdin) {
        this.out = out;
        this.err = err;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.stdin = stdin;
    }

    public static void main(String[] args) {
        int exitCode = commandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    static CommandLine commandLine(Main main) {
        return new CommandLine(main).setExecutionExceptionHandler(Main::handleExecutionException);
    }

    private static int handleExecutionException(
            Exception exception, CommandLine commandLine, CommandLine.ParseResult parseResult) {
        Main main = commandLine.getCommand();
        main.printFailure("frmtr", exception);
        return 2;
    }

    @Override
    public Integer call() throws Exception {
        if (diff && !check) {
            err.println("--diff requires --check");
            return 2;
        }
        if (check && write) {
            err.println("--check and --write cannot be used together");
            return 2;
        }
        FormatterOptions options = new FormatterOptions(
                lineWidth,
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true,
                javaLanguageLevel);
        if (selectors.isEmpty()) {
            if (check || write) {
                err.println("file-oriented options require at least one file or directory");
                return 2;
            }
            try {
                out.print(Frmtr.format(readStdin(), options));
                out.flush();
                return 0;
            } catch (FormatterException | IOException exception) {
                printFailure("stdin", exception);
                return 2;
            }
        }
        List<Path> files = new FileDiscovery(workingDirectory).discover(selectors);
        boolean changed = false;
        boolean failed = false;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                String original = Files.readString(file, StandardCharsets.UTF_8);
                String formatted = Frmtr.format(original, options);
                boolean fileChanged = !formatted.equals(original);
                if (fileChanged) {
                    changed = true;
                    if (write) {
                        Files.writeString(file, formatted, StandardCharsets.UTF_8);
                    }
                }
                if (check) {
                    out.println(statusLine(fileChanged ? "✗" : "✓", file));
                    if (diff && fileChanged) {
                        out.print(renderDiff(file, original, formatted));
                        out.flush();
                    }
                }
                if (!write && !check) {
                    printFormatted(files, i, file, formatted);
                }
            } catch (FormatterException exception) {
                failed = true;
                if (check) {
                    out.println(statusLine("!", file));
                }
                printFailure(displayPath(file).toString(), exception);
            } catch (IOException exception) {
                failed = true;
                if (check) {
                    out.println(statusLine("!", file));
                }
                printFailure(displayPath(file).toString(), exception);
            }
        }
        if (failed) {
            return 2;
        }
        return changed && check ? 1 : 0;
    }

    private String readStdin() throws IOException {
        if (stdin != null) {
            return stdin;
        }
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private void printFormatted(List<Path> files, int index, Path file, String formatted) {
        if (files.size() > 1) {
            if (index > 0) {
                out.println();
            }
            out.println("==> " + displayPath(file) + " <==");
        }
        out.print(formatted);
        out.flush();
    }

    private String statusLine(String marker, Path file) {
        return marker + " " + displayPath(file);
    }

    private String renderDiff(Path file, String original, String formatted) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String path = displayPath(file).toString().replace('\\', '/');
        output.write(("diff --git a/" + path + " b/" + path + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("--- a/" + path + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("+++ b/" + path + "\n").getBytes(StandardCharsets.UTF_8));

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

    private void printFailure(String target, Exception exception) {
        err.println(target + ": " + exception.getMessage());
        if (stacktrace) {
            exception.printStackTrace(err);
        }
        err.flush();
    }

    private Path displayPath(Path file) {
        return workingDirectory.relativize(file.toAbsolutePath().normalize());
    }

    static final class JavaLanguageLevelConverter
            implements CommandLine.ITypeConverter<FormatterOptions.JavaLanguageLevel> {
        @Override
        public FormatterOptions.JavaLanguageLevel convert(String value) {
            String normalized = value.trim().toUpperCase().replace('-', '_');
            if (normalized.equals("LATEST")) {
                return FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE;
            }
            if (normalized.matches("\\d+")) {
                normalized = "JAVA_" + normalized;
            }
            return FormatterOptions.JavaLanguageLevel.valueOf(normalized);
        }
    }
}
