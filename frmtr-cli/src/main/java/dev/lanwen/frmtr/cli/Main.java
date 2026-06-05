package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.tooling.FormatFileResult;
import dev.lanwen.frmtr.tooling.FormatFileStatus;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterRunner;
import dev.lanwen.frmtr.tooling.UnifiedDiffRenderer;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "frmtr",
        mixinStandardHelpOptions = true,
        versionProvider = Main.BuildVersionProvider.class,
        description = "Formats Java source.")
public final class Main implements Callable<Integer> {
    private static final List<String> DEFAULT_SELECTORS = List.of("./**/*.java");

    @Option(names = "--stdin", description = "Read Java source from stdin and print formatted source to stdout.")
    boolean stdinMode;

    @Option(names = "--check", description = "Check whether files are already formatted.")
    boolean check;

    @Option(names = "--diff", description = "Print unified diffs for checked sources that need formatting.")
    boolean diff;

    @Option(names = "--write", description = "Rewrite files in place.")
    boolean write;

    @Option(names = "--stacktrace", description = "Print stack traces for formatter and I/O failures.")
    boolean stacktrace;

    @Option(names = "--line-width", description = "Target line width.", defaultValue = "140")
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
        return new CommandLine(main)
                .setOut(main.out)
                .setErr(main.err)
                .setExecutionExceptionHandler(Main::handleExecutionException);
    }

    private static int handleExecutionException(
            Exception exception, CommandLine commandLine, CommandLine.ParseResult parseResult) {
        Main main = commandLine.getCommand();
        main.printFailure("frmtr", exception);
        return 2;
    }

    @Override
    public Integer call() throws Exception {
        if (stdinMode) {
            if (write || !selectors.isEmpty()) {
                err.println("--stdin cannot be combined with --write or selectors");
                return 2;
            }
            FormatterOptions options = formatterOptions();
            if (check || diff) {
                return checkStdin(options);
            }
            return formatStdin(options);
        }
        boolean usingDefaultSelectors = selectors.isEmpty();
        boolean effectiveCheck = check || (usingDefaultSelectors && !write);
        if (diff && !effectiveCheck) {
            err.println("--diff requires --check");
            return 2;
        }
        if (check && write) {
            err.println("--check and --write cannot be used together");
            return 2;
        }
        FormatterOptions options = formatterOptions();
        FileDiscovery.Result discovery = new FileDiscovery(workingDirectory)
                .discover(usingDefaultSelectors ? DEFAULT_SELECTORS : selectors);
        if (discovery.hasMissingFileSelectors()) {
            return printMissingFileSelectors(discovery.missingFileSelectors());
        }
        List<Path> files = discovery.files();
        if (files.isEmpty()) {
            if (write && discovery.ignoredCount() > 0) {
                printWriteSummary(new FormatRunResult(List.of()), discovery.ignoredCount());
                return 0;
            }
            if (!effectiveCheck && discovery.ignoredCount() > 0) {
                printPrintSummary(0, 0, 0, discovery.ignoredCount());
                return 0;
            }
            return noFilesMatched();
        }
        if (effectiveCheck) {
            return checkFiles(files, options);
        }
        if (write) {
            return writeFiles(files, options, discovery.ignoredCount());
        }
        return printFiles(files, options, discovery.ignoredCount());
    }

    private FormatterOptions formatterOptions() {
        return new FormatterOptions(
                lineWidth,
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true,
                javaLanguageLevel);
    }

    private int formatStdin(FormatterOptions options) {
        try {
            out.print(Frmtr.format(readStdin(), options));
            out.flush();
            return 0;
        } catch (FormatterException | IOException exception) {
            printFailure("stdin", exception);
            return 2;
        }
    }

    private int checkStdin(FormatterOptions options) {
        Path displayPath = Path.of("stdin");
        try {
            String original = readStdin();
            String formatted = Frmtr.format(original, options);
            if (formatted.equals(original)) {
                out.println(statusLine(statusMarker(FormatFileStatus.UNCHANGED), displayPath));
                out.flush();
                return 0;
            }
            out.println(statusLine(statusMarker(FormatFileStatus.CHANGED), displayPath));
            if (diff) {
                out.print(UnifiedDiffRenderer.render(displayPath, original, formatted));
            }
            out.flush();
            return 1;
        } catch (FormatterException | IOException exception) {
            printFailure("stdin", exception);
            return 2;
        }
    }

    private int checkFiles(List<Path> files, FormatterOptions options) {
        FormatRunResult run = FormatterRunner.check(workingDirectory, files, options, diff);
        for (FormatFileResult result : run.results()) {
            out.println(statusLine(statusMarker(result.status()), result.displayPath()));
            result.unifiedDiff().ifPresent(out::print);
            if (result.failed()) {
                result.failureException().ifPresent(exception -> printFailure(result.displayPath().toString(), exception));
            }
        }
        printCheckSummary(run);
        out.flush();
        if (run.hasFailures()) {
            return 2;
        }
        return run.hasChanges() ? 1 : 0;
    }

    private int writeFiles(List<Path> files, FormatterOptions options, long ignored) {
        FormatRunResult run = FormatterRunner.write(workingDirectory, files, options);
        run.failedResults().stream()
                .forEach(result -> result.failureException()
                        .ifPresent(exception -> printFailure(result.displayPath().toString(), exception)));
        printWriteSummary(run, ignored);
        out.flush();
        return run.hasFailures() ? 2 : 0;
    }

    private int printFiles(List<Path> files, FormatterOptions options, long ignored) {
        long failed = 0;
        long printed = 0;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                String formatted = Frmtr.format(Files.readString(file, StandardCharsets.UTF_8), options);
                printFormatted(files, i, file, formatted);
                printed++;
            } catch (FormatterException | IOException exception) {
                failed++;
                printFailure(displayPath(file).toString(), exception);
            }
        }
        printPrintSummary(files.size(), printed, failed, ignored);
        return failed > 0 ? 2 : 0;
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

    private int printMissingFileSelectors(List<String> selectors) {
        for (String selector : selectors) {
            err.println("File selector does not exist: " + selector);
        }
        err.flush();
        return 2;
    }

    private int noFilesMatched() {
        err.println("No Java files matched.");
        err.flush();
        return 0;
    }

    private void printCheckSummary(FormatRunResult run) {
        List<String> parts = new ArrayList<>();
        addCount(parts, statusCount(run, FormatFileStatus.UNCHANGED), "unchanged");
        addCount(parts, statusCount(run, FormatFileStatus.CHANGED), "would change");
        addCount(parts, run.failureCount(), "failed");
        out.println(summaryLine("Checked", run.results().size(), parts));
    }

    private void printWriteSummary(FormatRunResult run, long ignored) {
        List<String> parts = new ArrayList<>();
        addRequiredCount(parts, statusCount(run, FormatFileStatus.WRITTEN), "formatted");
        addCount(parts, run.failureCount(), "failed");
        addCount(parts, ignored, "ignored");
        addCount(parts, statusCount(run, FormatFileStatus.UNCHANGED), "unchanged");
        out.println(summaryLine("Processed", run.results().size() + ignored, parts));
    }

    private void printPrintSummary(long total, long printed, long failed, long ignored) {
        List<String> parts = new ArrayList<>();
        addRequiredCount(parts, printed, "printed");
        addCount(parts, failed, "failed");
        addCount(parts, ignored, "ignored");
        err.println(summaryLine("Processed", total + ignored, parts));
        err.flush();
    }

    private long statusCount(FormatRunResult run, FormatFileStatus status) {
        return run.results().stream().filter(result -> result.status() == status).count();
    }

    private void addCount(List<String> parts, long count, String label) {
        if (count > 0) {
            parts.add(count + " " + label);
        }
    }

    private void addRequiredCount(List<String> parts, long count, String label) {
        parts.add(count + " " + label);
    }

    private String summaryLine(String action, long count, List<String> parts) {
        String summary = action + " " + count + " " + plural(count, "file");
        if (!parts.isEmpty()) {
            summary += ": " + String.join(", ", parts);
        }
        return summary + ".";
    }

    private String plural(long count, String singular) {
        return count == 1 ? singular : singular + "s";
    }

    private String statusLine(String marker, Path file) {
        return marker + " " + file;
    }

    private String statusMarker(FormatFileStatus status) {
        return switch (status) {
            case UNCHANGED -> "✓";
            case CHANGED -> "✗";
            case WRITTEN -> "✗";
            case WRITTEN_PARTIALLY -> "!";
            case FAILED -> "!";
        };
    }

    private void printFailure(String target, Exception exception) {
        err.println(target + ": " + failureMessage(exception));
        if (stacktrace) {
            exception.printStackTrace(err);
        }
        err.flush();
    }

    private String failureMessage(Exception exception) {
        if (exception instanceof FormatterException formatterException
                && formatterException.internal()
                && !stacktrace) {
            return exception.getMessage() + " (run with --stacktrace for details)";
        }
        return exception.getMessage();
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

    static final class BuildVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {
                "frmtr version " + BuildInfo.VERSION,
                "commit " + BuildInfo.COMMIT_SHA,
                "built " + BuildInfo.BUILD_TIMESTAMP
            };
        }
    }
}
