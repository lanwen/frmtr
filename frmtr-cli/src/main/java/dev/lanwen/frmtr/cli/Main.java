package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.ExplainResult;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.OverWidthLines.OverWidthLine;
import dev.lanwen.frmtr.tooling.DiagnosticSpan;
import dev.lanwen.frmtr.tooling.DiagnosticStyle;
import dev.lanwen.frmtr.tooling.DiagnosticText;
import dev.lanwen.frmtr.tooling.FormatFileResult;
import dev.lanwen.frmtr.tooling.FormatFileStatus;
import dev.lanwen.frmtr.tooling.FormatRunProgress;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterFailureRenderer;
import dev.lanwen.frmtr.tooling.FormatterRunFailureRenderer;
import dev.lanwen.frmtr.tooling.FormatterRunner;
import dev.lanwen.frmtr.tooling.UnifiedDiffRenderer;
import dev.lanwen.frmtr.tooling.UnifiedDiffRenderer.RenderMode;
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
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Ansi.IStyle;
import picocli.CommandLine.Help.Ansi.Style;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "frmtr",
    mixinStandardHelpOptions = true,
    versionProvider = Main.BuildVersionProvider.class,
    description = "Formats Java source.",
    footer = {
        "",
        "Exit codes (highest severity wins: 3 > 2 > 1 > 0):",
        "  0  success: all files clean / written / verified, or no files matched.",
        "  1  would change: check modes only, files need formatting, no failures.",
        "  2  parse failure, IO error, or usage error: the run could not be completed.",
        "  3  verify violation: a cleanly-parsed file's formatted output was not",
        "     AST-equivalent to the input (or did not re-parse) — a formatter bug.",
    }
)
public final class Main implements Callable<Integer> {

    private static final List<String> DEFAULT_SELECTORS = List.of("./**/*.java");

    /** All files clean / written / verified, or no files matched. */
    static final int EXIT_OK = 0;

    /** Check modes only: files would change, with no failures. */
    static final int EXIT_CHANGED = 1;

    /** Parse failure, IO error, or usage/config error: the run could not be completed. */
    static final int EXIT_FAILED = 2;

    /**
     * Verify violation: a cleanly-parsed file's formatted output was not AST-equivalent to the input (or did not
     * re-parse) — a formatter bug. Highest severity; takes precedence over {@link #EXIT_FAILED} and {@link #EXIT_CHANGED}.
     */
    static final int EXIT_VERIFY = 3;

    private static final char LINE_WIDTH_MARKER = '⋮';

    private static final IStyle LINE_BORDER_STYLE = Style.fg("8");

    @Option(names = "--stdin", description = "Read Java source from stdin and print formatted source to stdout.")
    boolean stdinMode;

    @Option(names = "--check", description = "Check whether files are already formatted.")
    boolean check;

    @Option(names = "--diff", description = "Print unified diffs for checked sources that need formatting.")
    boolean diff;

    @Option(names = "--render-line-width", description = "Print diff output with a dotted width guide.")
    boolean renderLineWidth;

    @Option(
        names = "--render-indentation",
        description = "Render leading indentation of printed source as middle-dots (·) to visualize how far each line "
            + "is indented. Display aid only for printed source (default print or --stdin); the formatting is unchanged. "
            + "Cannot be combined with --write, --check, --diff, --render-line-width, or --explain."
    )
    boolean renderIndentation;

    @Option(names = "--write", description = "Rewrite files in place.")
    boolean write;

    @Option(
        names = "--verify",
        description = "Re-parse each formatted file and assert AST-equivalence. Valid with --write (refuse "
            + "non-equivalent overwrites) or --check (read-only, writes nothing; also warns on stderr about breakable "
            + "output lines over the line width without changing the exit code). Off by default; doubles parse cost."
    )
    boolean verify;

    @Option(
        names = "--explain",
        description = "Explain why the formatter wrapped (or kept flat) each group. Reads --stdin or a single file. "
            + "Prints the formatted result, why each group broke, a rule-label decision tree, and a legend."
    )
    boolean explain;

    @Option(
        names = { "-v", "--verbose" },
        description = "With --explain, show every group in the decision tree, not only the paths that wrapped."
    )
    boolean verbose;

    @Option(
        names = "--exclude",
        paramLabel = "PATTERN",
        description = "Exclude files, directories, globs, or comma-separated patterns from selector discovery."
    )
    List<String> excludes = List.of();

    @Option(names = "--stacktrace", description = "Print stack traces for formatter and I/O failures.")
    boolean stacktrace;

    @Option(
        names = "--color",
        paramLabel = "auto|always|never",
        description = "Color status markers, diff output, and diagnostics. Defaults to ${DEFAULT-VALUE}.",
        defaultValue = "auto",
        converter = ColorModeConverter.class
    )
    ColorMode colorMode;

    @Option(
        names = "--progress",
        paramLabel = "auto|always|never",
        description = "Render multi-file check/write progress to stderr. Defaults to ${DEFAULT-VALUE}.",
        defaultValue = "auto",
        converter = ProgressModeConverter.class
    )
    ProgressMode progressMode;

    @Option(names = "--line-width", description = "Target line width. Defaults to the formatter default.")
    Integer lineWidth;

    @Option(names = "--indent-width", description = "Spaces per indentation level. Defaults to the formatter default.")
    Integer indentWidth;

    @Option(
        names = "--java-level",
        description = "Java parser language level. Use LATEST_AVAILABLE by default or UNSET for raw parser mode.",
        defaultValue = "LATEST_AVAILABLE",
        converter = JavaLanguageLevelConverter.class
    )
    FormatterOptions.JavaLanguageLevel javaLanguageLevel;

    @Option(
        names = "--parse-error-behavior",
        paramLabel = "recover|fail",
        description = "Parse-error behavior. Use RECOVER by default or FAIL for strict parse failures.",
        defaultValue = "RECOVER",
        converter = ParseErrorBehaviorConverter.class
    )
    FormatterOptions.ParseErrorBehavior parseErrorBehavior;

    @Parameters(
        arity = "0..*",
        paramLabel = "SELECTOR",
        description = "Java files, directories, globs, or comma-separated selectors."
    )
    List<String> selectors = List.of();

    private final PrintWriter out;

    private final PrintWriter err;

    private final Path workingDirectory;

    private final boolean consolePresent;

    private String stdin;

    public Main() {
        this(
            new PrintWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
            new PrintWriter(new java.io.OutputStreamWriter(System.err, StandardCharsets.UTF_8), true),
            Path.of("."),
            null,
            System.console() != null
        );
    }

    Main(PrintWriter out, PrintWriter err, String stdin) {
        this(out, err, Path.of("."), stdin, false);
    }

    Main(PrintWriter out, PrintWriter err, Path workingDirectory, String stdin) {
        this(out, err, workingDirectory, stdin, false);
    }

    Main(PrintWriter out, PrintWriter err, Path workingDirectory, String stdin, boolean consolePresent) {
        this.out = out;
        this.err = err;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        this.consolePresent = consolePresent;
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
            Exception exception,
            CommandLine commandLine,
            CommandLine.ParseResult parseResult
    ) {
        Main main = commandLine.getCommand();
        main.printFailure("frmtr", exception);
        return 2;
    }

    @Override
    public Integer call() throws Exception {
        if (verify && !write && !check) {
            err.println("--verify requires --write or --check");
            return EXIT_FAILED;
        }
        if (renderIndentation && (check || write || diff || renderLineWidth || explain)) {
            err.println(
                "--render-indentation renders printed source only and cannot be combined with --check, --write, "
                    + "--diff, --render-line-width, or --explain"
            );
            return 2;
        }
        if (explain) {
            return runExplain();
        }
        if (stdinMode) {
            if (verify) {
                err.println("--verify requires --write or --check");
                return EXIT_FAILED;
            }
            if (write || !selectors.isEmpty() || !excludes.isEmpty()) {
                err.println("--stdin cannot be combined with --write, selectors, or --exclude");
                return 2;
            }
            FormatterOptions options = formatterOptions();
            if (check || diff || renderLineWidth) {
                return checkStdin(options);
            }
            return formatStdin(options);
        }
        boolean usingDefaultSelectors = selectors.isEmpty();
        boolean effectiveCheck = check || (usingDefaultSelectors && !write);
        if (renderIndentation && effectiveCheck) {
            err.println("--render-indentation renders printed source only; pass file selectors to print them");
            return 2;
        }
        boolean effectiveDiff = diff || renderLineWidth;
        if (effectiveDiff && !effectiveCheck) {
            err.println(renderLineWidth && !diff ? "--render-line-width requires --check" : "--diff requires --check");
            return 2;
        }
        if (check && write) {
            err.println("--check and --write cannot be used together");
            return 2;
        }
        FormatterOptions options = formatterOptions();
        CliProgressRenderer progress = progressRendererBeforeDiscovery(
            effectiveCheck || write,
            effectiveCheck ? "would change" : "formatted",
            usingDefaultSelectors ? DEFAULT_SELECTORS : selectors
        );
        FileDiscovery.Result discovery = new FileDiscovery(workingDirectory).discover(
            usingDefaultSelectors ? DEFAULT_SELECTORS : selectors,
            excludes
        );
        if (discovery.hasMissingFileSelectors()) {
            clearProgress(progress);
            return printMissingFileSelectors(discovery.missingFileSelectors());
        }
        List<Path> files = discovery.files();
        if (files.isEmpty()) {
            clearProgress(progress);
            if (write && discovery.skippedCount() > 0) {
                printWriteSummary(new FormatRunResult(List.of()), discovery.ignoredCount(), discovery.excludedCount());
                return 0;
            }
            if (effectiveCheck && discovery.excludedCount() > 0) {
                printCheckSummary(new FormatRunResult(List.of()), discovery.excludedCount());
                return 0;
            }
            if (!effectiveCheck && discovery.skippedCount() > 0) {
                printPrintSummary(0, 0, 0, discovery.ignoredCount(), discovery.excludedCount());
                return 0;
            }
            return noFilesMatched();
        }
        if (effectiveCheck) {
            return checkFiles(files, options, discovery.excludedCount(), progress);
        }
        if (write) {
            return writeFiles(files, options, discovery.ignoredCount(), discovery.excludedCount(), progress);
        }
        clearProgress(progress);
        return printFiles(files, options, discovery.ignoredCount(), discovery.excludedCount());
    }

    private FormatterOptions formatterOptions() {
        FormatterOptions options = FormatterOptions.defaults()
                .withJavaLanguageLevel(javaLanguageLevel)
                .withParseErrorBehavior(parseErrorBehavior);
        if (lineWidth != null) {
            options = options.withLineWidth(lineWidth);
        }
        if (indentWidth != null) {
            options = options.withIndentWidth(indentWidth);
        }
        return options;
    }

    private int runExplain() {
        if (check || write || diff || renderLineWidth) {
            err.println(
                "--explain is its own mode and cannot be combined with --check, --write, --diff, or --render-line-width"
            );
            return 2;
        }
        FormatterOptions options = formatterOptions();
        if (stdinMode) {
            if (!selectors.isEmpty() || !excludes.isEmpty()) {
                err.println("--explain --stdin cannot be combined with selectors or --exclude");
                return 2;
            }
            return explainSource("stdin", () -> readStdin(), options);
        }
        if (selectors.size() != 1) {
            err.println("--explain expects exactly one file, or --stdin");
            return 2;
        }
        Path file = workingDirectory.resolve(selectors.getFirst()).normalize();
        if (!Files.isRegularFile(file)) {
            err.println("File selector does not exist: " + selectors.getFirst());
            return 2;
        }
        return explainSource(
            displayPath(file).toString(),
            () -> Files.readString(file, StandardCharsets.UTF_8),
            options
        );
    }

    private int explainSource(String target, SourceSupplier source, FormatterOptions options) {
        try {
            ExplainResult result = Frmtr.explain(source.get(), options);
            out.print(new ExplainView(this::styleExplain, verbose).render(result));
            out.flush();
            return 0;
        } catch (FormatterException | IOException exception) {
            printFailure(target, exception);
            return 2;
        }
    }

    @FunctionalInterface
    private interface SourceSupplier {
        String get() throws IOException;
    }

    private String styleExplain(ExplainView.Role role, String text) {
        return switch (role) {
            case HEADING -> styled(text, Style.bold);
            case BREAK -> styled(text, Style.fg_yellow, Style.bold);
            case FLAT -> styled(text, Style.fg_green);
            case LABEL -> styled(text, Style.fg_cyan);
            case NUMBER -> styled(text, Style.bold);
            case TREE, FADE -> styled(text, LINE_BORDER_STYLE);
        };
    }

    private int formatStdin(FormatterOptions options) {
        try {
            out.print(renderSource(Frmtr.format(readStdin(), options)));
            out.flush();
            return 0;
        } catch (FormatterException | IOException exception) {
            printFailure("stdin", exception);
            return 2;
        }
    }

    /**
     * Applies the optional printed-source display transforms before emission. Currently only
     * {@code --render-indentation}, which visualizes leading indentation as middle-dots. Off by default, so the printed
     * source is byte-for-byte the formatter output unless the flag is set. Only source-printing paths (default print and
     * {@code --stdin}) route through here; {@code --write}, {@code --check}, and {@code --diff} deliberately do not.
     */
    private String renderSource(String formatted) {
        return renderIndentation ? IndentationRenderer.render(formatted) : formatted;
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
            if (diff || renderLineWidth) {
                out.print(
                    colorizeDiff(
                        UnifiedDiffRenderer.render(displayPath, original, formatted, options.lineWidth(), diffMode())
                    )
                );
            }
            out.flush();
            return 1;
        } catch (FormatterException | IOException exception) {
            printFailure("stdin", exception);
            return 2;
        }
    }

    private int checkFiles(
            List<Path> files,
            FormatterOptions options,
            long excluded,
            CliProgressRenderer progress
    ) {
        FormatRunProgress runProgress = progressRenderer(files.size(), "would change", progress);
        FormatRunResult run = verify
            ? FormatterRunner.checkVerified(workingDirectory, files, options, diff || renderLineWidth, diffMode(),
                runProgress)
            : FormatterRunner.check(workingDirectory, files, options, diff || renderLineWidth, diffMode(), runProgress);
        for (FormatFileResult result : run.results()) {
            out.println(statusLine(statusMarker(result.status()), result.displayPath()));
            if (result.failed() && !stacktrace) {
                out.println(colorizeDiagnostic(FormatterRunFailureRenderer.renderDiagnostic(result)));
            } else {
                result.unifiedDiff().map(this::colorizeDiff).ifPresent(out::print);
            }
        }
        if (stacktrace) {
            printRunFailures(run);
        }
        printOverWidthWarnings(run);
        printCheckSummary(run, excluded);
        out.flush();
        if (run.hasFailures()) {
            return failureExit(run);
        }
        return run.hasChanges() ? EXIT_CHANGED : EXIT_OK;
    }

    /**
     * Prints informational warnings (to stderr, so stdout's status lines and diffs stay machine-readable) for breakable
     * output lines that still overrun the configured width — populated only on the {@code --check --verify} path. These
     * are advisory: they never feed {@link FormatRunResult#hasChanges()} / {@link FormatRunResult#hasFailures()} or the
     * exit-code mapping, so the {@code 0/1/2/3} contract is untouched.
     */
    private void printOverWidthWarnings(FormatRunResult run) {
        long totalLines = 0;
        long fileCount = 0;
        int limit = -1;
        for (FormatFileResult result : run.results()) {
            List<OverWidthLine> overWidth = result.overWidthLines();
            if (overWidth.isEmpty()) {
                continue;
            }
            fileCount++;
            totalLines += overWidth.size();
            limit = overWidth.getFirst().lineWidth();
            String path = result.displayPath().toString();
            err.println(styled(
                "%s: %d %s over the %d-column limit that frmtr could break:".formatted(
                    path,
                    overWidth.size(),
                    plural(overWidth.size(), "line"),
                    overWidth.getFirst().lineWidth()
                ),
                Style.fg_yellow
            ));
            for (OverWidthLine line : overWidth) {
                err.println("  %s:%d: line is %d columns".formatted(path, line.lineNumber(), line.width()));
            }
        }
        if (totalLines > 0) {
            err.println(styled(
                "frmtr: %d breakable %s over the %d-column limit in %d %s (informational; does not affect exit code)."
                    .formatted(totalLines, plural(totalLines, "line"), limit, fileCount, plural(fileCount, "file")),
                Style.fg_yellow
            ));
        }
        err.flush();
    }

    /**
     * Maps a run that has failures to {@link #EXIT_VERIFY} when any failure is the verify safety valve's
     * AST-equivalence refusal, otherwise {@link #EXIT_FAILED}. This is the single seam that distinguishes a formatter
     * bug (non-AST-equivalent output) from an ordinary parse/IO failure, keyed on
     * {@link FormatterException#verifyViolation()} rather than the failure message string.
     */
    static int failureExit(FormatRunResult run) {
        boolean verifyViolation = run.failedResults().stream()
                .flatMap(result -> result.failureException().stream())
                .anyMatch(exception ->
                    exception instanceof FormatterException formatterException && formatterException.verifyViolation());
        return verifyViolation ? EXIT_VERIFY : EXIT_FAILED;
    }

    private RenderMode diffMode() {
        return renderLineWidth ? RenderMode.LINE_WIDTH_RULER : RenderMode.PATCH;
    }

    private int writeFiles(
            List<Path> files,
            FormatterOptions options,
            long ignored,
            long excluded,
            CliProgressRenderer progress
    ) {
        FormatRunProgress runProgress = progressRenderer(files.size(), "formatted", progress);
        FormatRunResult run = verify
            ? FormatterRunner.writeVerified(workingDirectory, files, options, runProgress)
            : FormatterRunner.write(workingDirectory, files, options, runProgress);
        printRunFailures(run);
        printWriteSummary(run, ignored, excluded);
        out.flush();
        return run.hasFailures() ? failureExit(run) : EXIT_OK;
    }

    private CliProgressRenderer progressRendererBeforeDiscovery(
            boolean enabled,
            String changedLabel,
            List<String> selectorArgs
    ) {
        if (!progressEnabled() || !enabled || !selectorsNeedTraversal(selectorArgs)) {
            return null;
        }
        CliProgressRenderer renderer = new CliProgressRenderer(err, changedLabel);
        renderer.discovering();
        return renderer;
    }

    private boolean selectorsNeedTraversal(List<String> selectorArgs) {
        for (String arg : selectorArgs) {
            for (String selector : arg.split(",")) {
                String trimmed = selector.trim();
                if (!trimmed.isEmpty() && (!trimmed.endsWith(".java") || hasGlobSyntax(trimmed))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasGlobSyntax(String selector) {
        for (char glob : new char[] {'*', '?', '[', '{'}) {
            if (selector.indexOf(glob) >= 0) {
                return true;
            }
        }
        return false;
    }

    private void clearProgress(CliProgressRenderer progress) {
        if (progress != null) {
            progress.clear();
        }
    }

    private FormatRunProgress progressRenderer(int fileCount, String changedLabel, CliProgressRenderer progress) {
        if (fileCount <= 1) {
            clearProgress(progress);
            return state -> {};
        }
        if (!progressEnabled()) {
            return state -> {};
        }
        return progress == null ? new CliProgressRenderer(err, changedLabel) : progress;
    }

    private boolean progressEnabled() {
        return progressMode.enabled(consolePresent);
    }

    private int printFiles(List<Path> files, FormatterOptions options, long ignored, long excluded) {
        List<FormatFileResult> failures = new ArrayList<>();
        long printed = 0;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                String formatted = Frmtr.format(Files.readString(file, StandardCharsets.UTF_8), options);
                printFormatted(files, i, file, formatted);
                printed++;
            } catch (FormatterException | IOException exception) {
                failures.add(
                    new FormatFileResult(
                        file,
                        displayPath(file),
                        FormatFileStatus.FAILED,
                        "",
                        exception
                    )
                );
            }
        }
        FormatRunResult failureRun = new FormatRunResult(failures);
        printRunFailures(failureRun);
        printPrintSummary(files.size(), printed, failureRun.failureCount(), ignored, excluded);
        return failureRun.hasFailures() ? 2 : 0;
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
        out.print(renderSource(formatted));
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

    private void printCheckSummary(FormatRunResult run, long excluded) {
        List<String> parts = new ArrayList<>();
        addCount(parts, statusCount(run, FormatFileStatus.UNCHANGED), "unchanged", Style.fg_green);
        addCount(parts, statusCount(run, FormatFileStatus.CHANGED), "would change", Style.fg_yellow);
        addCount(parts, run.failureCount(), "failed", Style.fg_red);
        addCount(parts, excluded, "excluded", LINE_BORDER_STYLE);
        out.println(summaryLine("Checked", run.results().size(), parts));
    }

    private void printWriteSummary(FormatRunResult run, long ignored, long excluded) {
        List<String> parts = new ArrayList<>();
        addRequiredCount(parts, statusCount(run, FormatFileStatus.WRITTEN), "formatted", Style.fg_green);
        addCount(parts, run.failureCount(), "failed", Style.fg_red);
        addCount(parts, ignored, "ignored", LINE_BORDER_STYLE);
        addCount(parts, excluded, "excluded", LINE_BORDER_STYLE);
        addCount(parts, statusCount(run, FormatFileStatus.UNCHANGED), "unchanged", Style.fg_green);
        out.println(summaryLine("Processed", run.results().size() + ignored + excluded, parts));
    }

    private void printPrintSummary(long total, long printed, long failed, long ignored, long excluded) {
        List<String> parts = new ArrayList<>();
        addRequiredCount(parts, printed, "printed", Style.fg_green);
        addCount(parts, failed, "failed", Style.fg_red);
        addCount(parts, ignored, "ignored", LINE_BORDER_STYLE);
        addCount(parts, excluded, "excluded", LINE_BORDER_STYLE);
        err.println(summaryLine("Processed", total + ignored + excluded, parts));
        err.flush();
    }

    private long statusCount(FormatRunResult run, FormatFileStatus status) {
        return run.results().stream().filter(result -> result.status() == status).count();
    }

    private void addCount(List<String> parts, long count, String label, IStyle... styles) {
        if (count > 0) {
            parts.add(styled(count + " " + label, styles));
        }
    }

    private void addRequiredCount(List<String> parts, long count, String label, IStyle... styles) {
        parts.add(styled(count + " " + label, styles));
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
            case UNCHANGED -> styled("✓", Style.fg_green);
            case CHANGED -> styled("✗", Style.fg_yellow);
            case WRITTEN -> styled("✗", Style.fg_yellow);
            case WRITTEN_PARTIALLY -> styled("!", Style.fg_red);
            case FAILED -> styled("!", Style.fg_red);
        };
    }

    private String colorizeDiff(String diff) {
        if (!ansi().enabled()) {
            return diff;
        }
        StringBuilder colored = new StringBuilder(diff.length());
        int lineStart = 0;
        while (lineStart < diff.length()) {
            int lineEnd = diff.indexOf('\n', lineStart);
            boolean hasLineEnding = lineEnd >= 0;
            String line = hasLineEnding ? diff.substring(lineStart, lineEnd) : diff.substring(lineStart);
            colored.append(colorizeDiffLine(line));
            if (hasLineEnding) {
                colored.append('\n');
            }
            lineStart = hasLineEnding ? lineEnd + 1 : diff.length();
        }
        return colored.toString();
    }

    private String colorizeDiffLine(String line) {
        if (line.startsWith("@@ ")) {
            return styledLineWithGrayBorder(line, Style.fg_cyan, Style.bold);
        }
        if (line.startsWith("diff --git ") || line.startsWith("--- ") || line.startsWith("+++ ")) {
            return styled(line, Style.faint);
        }
        if (line.startsWith("-")) {
            return styledLineWithGrayBorder(line, Style.fg_red);
        }
        if (line.startsWith("+")) {
            return styledLineWithGrayBorder(line, Style.fg_green);
        }
        if (line.indexOf(LINE_WIDTH_MARKER) >= 0) {
            return styledLineWithGrayBorder(line);
        }
        return line;
    }

    private String styledLineWithGrayBorder(String line, IStyle... styles) {
        if (line.indexOf(LINE_WIDTH_MARKER) < 0) {
            return styled(line, styles);
        }
        StringBuilder colored = new StringBuilder(line.length());
        int cursor = 0;
        while (cursor < line.length()) {
            int marker = line.indexOf(LINE_WIDTH_MARKER, cursor);
            if (marker < 0) {
                colored.append(styled(line.substring(cursor), styles));
                break;
            }
            colored.append(styled(line.substring(cursor, marker), styles));
            int markerEnd = lineWidthMarkerEnd(line, marker);
            colored.append(styled(line.substring(marker, markerEnd), LINE_BORDER_STYLE));
            cursor = markerEnd;
        }
        return colored.toString();
    }

    private int lineWidthMarkerEnd(String line, int marker) {
        int cursor = marker + 1;
        if (line.startsWith("@@ ")) {
            while (cursor < line.length() && line.charAt(cursor) == ' ') {
                cursor++;
            }
            while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) {
                cursor++;
            }
            return cursor;
        }
        if (marker > 0 && !line.substring(0, marker).isBlank()) {
            return cursor;
        }
        if (cursor < line.length() && line.charAt(cursor) == '+') {
            cursor++;
            while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) {
                cursor++;
            }
            return cursor;
        }
        while (cursor < line.length() && line.charAt(cursor) == ' ') {
            cursor++;
        }
        while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private String colorizeDiagnostic(DiagnosticText diagnostic) {
        if (!ansi().enabled()) {
            return diagnostic.plainText();
        }
        StringBuilder colored = new StringBuilder(diagnostic.plainText().length());
        for (int lineIndex = 0; lineIndex < diagnostic.lines().size(); lineIndex++) {
            if (lineIndex > 0) {
                colored.append(System.lineSeparator());
            }
            for (DiagnosticSpan span : diagnostic.lines().get(lineIndex).spans()) {
                colored.append(styled(span.text(), diagnosticStyle(span.style())));
            }
        }
        return colored.toString();
    }

    private IStyle[] diagnosticStyle(DiagnosticStyle style) {
        return switch (style) {
            case ERROR_TEXT, POINTER -> new IStyle[] {
                Style.fg_red,
            };
            case LINE_NUMBER, BORDER_GUTTER, GAP -> new IStyle[] {
                LINE_BORDER_STYLE,
            };
            case SOURCE_TEXT -> new IStyle[0];
        };
    }

    private String styled(String value, IStyle... styles) {
        Ansi ansi = ansi();
        if (!ansi.enabled() || styles.length == 0) {
            return value;
        }
        return Style.on(styles) + value + Style.reset.off();
    }

    private Ansi ansi() {
        return colorMode.ansi();
    }

    private void printFailure(String target, Exception exception) {
        err.println(target + ": " + failureMessage(exception));
        if (stacktrace) {
            exception.printStackTrace(err);
        }
        err.flush();
    }

    private void printRunFailures(FormatRunResult run) {
        if (!run.hasFailures()) {
            return;
        }
        if (stacktrace) {
            run
                  .failedResults()
                  .forEach(result -> result.failureException().ifPresent(
                          exception -> printFailure(result.displayPath().toString(), exception)
                  ));
            return;
        }
        err.println(colorizeDiagnostic(FormatterRunFailureRenderer.renderDiagnostic(run)));
        err.flush();
    }

    private String failureMessage(Exception exception) {
        if (
            exception instanceof FormatterException formatterException
            && formatterException.internal()
            && !stacktrace
        ) {
            return exception.getMessage() + " (run with --stacktrace for details)";
        }
        return colorizeDiagnostic(FormatterFailureRenderer.renderDiagnostic(exception));
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

    static final class ParseErrorBehaviorConverter
        implements CommandLine.ITypeConverter<FormatterOptions.ParseErrorBehavior> {

        @Override
        public FormatterOptions.ParseErrorBehavior convert(String value) {
            String normalized = value.trim().toUpperCase().replace('-', '_');
            return FormatterOptions.ParseErrorBehavior.valueOf(normalized);
        }
    }

    enum ColorMode {
        /**
         * Enables ANSI colors only when Picocli detects terminal support, keeping redirected output plain by default.
         */
        AUTO(Ansi.AUTO),

        /**
         * Forces ANSI colors for status and diff presentation even when output is redirected or captured.
         */
        ALWAYS(Ansi.ON),

        /**
         * Disables ANSI colors so status and diff output remain exact plain text for logs, scripts, or patch consumers.
         */
        NEVER(Ansi.OFF);

        private final Ansi ansi;

        ColorMode(Ansi ansi) {
            this.ansi = ansi;
        }

        Ansi ansi() {
            return ansi;
        }
    }

    static final class ColorModeConverter implements CommandLine.ITypeConverter<ColorMode> {

        @Override
        public ColorMode convert(String value) {
            String normalized = value.trim().toUpperCase().replace('-', '_');
            return ColorMode.valueOf(normalized);
        }
    }

    enum ProgressMode {
        /**
         * Enables progress only when the CLI process has an attached console, keeping captured output stable by default.
         */
        AUTO,

        /**
         * Forces progress rendering even when process output is captured by a launcher or build tool.
         */
        ALWAYS,

        /**
         * Disables progress rendering so stderr stays plain and append-only for logs and scripts.
         */
        NEVER;

        boolean enabled(boolean consolePresent) {
            return switch (this) {
                case AUTO -> consolePresent;
                case ALWAYS -> true;
                case NEVER -> false;
            };
        }
    }

    static final class ProgressModeConverter implements CommandLine.ITypeConverter<ProgressMode> {

        @Override
        public ProgressMode convert(String value) {
            String normalized = value.trim().toUpperCase().replace('-', '_');
            return ProgressMode.valueOf(normalized);
        }
    }

    static final class BuildVersionProvider implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            return new String[] {
                "frmtr version " + BuildInfo.VERSION,
                "commit " + BuildInfo.COMMIT_SHA,
                "built " + BuildInfo.BUILD_TIMESTAMP,
            };
        }
    }
}
