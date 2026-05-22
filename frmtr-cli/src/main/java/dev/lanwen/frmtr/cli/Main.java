package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
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
        description = "Formats Java source.")
public final class Main implements Callable<Integer> {
    @Option(names = "--check", description = "Check whether files are already formatted.")
    boolean check;

    @Option(names = "--write", description = "Rewrite files in place.")
    boolean write;

    @Option(names = "--line-width", description = "Target line width.", defaultValue = "100")
    int lineWidth;

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
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (check && write) {
            err.println("--check and --write cannot be used together");
            return 2;
        }
        FormatterOptions options = new FormatterOptions(
                lineWidth,
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true);
        if (selectors.isEmpty()) {
            if (check || write) {
                err.println("--check and --write require at least one file or directory");
                return 2;
            }
            out.print(Frmtr.format(readStdin(), options));
            out.flush();
            return 0;
        }
        List<Path> files = new FileDiscovery(workingDirectory).discover(selectors);
        boolean changed = false;
        boolean failed = false;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                String original = Files.readString(file, StandardCharsets.UTF_8);
                String formatted = Frmtr.format(original, options);
                if (!formatted.equals(original)) {
                    changed = true;
                    if (write) {
                        Files.writeString(file, formatted, StandardCharsets.UTF_8);
                    } else if (check) {
                        out.println(displayPath(file));
                    }
                }
                if (!write && !check) {
                    printFormatted(files, i, file, formatted);
                }
            } catch (FormatterException | IOException exception) {
                failed = true;
                err.println(file + ": " + exception.getMessage());
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

    private Path displayPath(Path file) {
        return workingDirectory.relativize(file.toAbsolutePath().normalize());
    }
}
