package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.tooling.FormatFileResult;
import dev.lanwen.frmtr.tooling.FormatFileStatus;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterRunner;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    @Option(names = "--check", description = "Check whether files are already formatted.")
    boolean check;

    @Option(names = "--diff", description = "With --check, print unified diffs for files that need formatting.")
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
        if (check) {
            return checkFiles(files, options);
        }
        if (write) {
            return writeFiles(files, options);
        }
        return printFiles(files, options);
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
        out.flush();
        if (run.hasFailures()) {
            return 2;
        }
        return run.hasChanges() ? 1 : 0;
    }

    private int writeFiles(List<Path> files, FormatterOptions options) {
        FormatRunResult run = FormatterRunner.write(workingDirectory, files, options);
        run.failedResults().stream()
                .forEach(result -> result.failureException()
                        .ifPresent(exception -> printFailure(result.displayPath().toString(), exception)));
        return run.hasFailures() ? 2 : 0;
    }

    private int printFiles(List<Path> files, FormatterOptions options) {
        boolean failed = false;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                String formatted = Frmtr.format(Files.readString(file, StandardCharsets.UTF_8), options);
                printFormatted(files, i, file, formatted);
            } catch (FormatterException | IOException exception) {
                failed = true;
                printFailure(displayPath(file).toString(), exception);
            }
        }
        return failed ? 2 : 0;
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
