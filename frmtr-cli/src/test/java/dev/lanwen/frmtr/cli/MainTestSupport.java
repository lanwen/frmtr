package dev.lanwen.frmtr.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

final class MainTestSupport {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*m");

    private MainTestSupport() {}

    static Result run(Path workingDirectory, String stdin, String... args) {
        return run(workingDirectory, stdin, false, args);
    }

    static Result run(Path workingDirectory, String stdin, boolean consolePresent, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
            new PrintWriter(out, true),
            new PrintWriter(err, true),
            workingDirectory,
            stdin,
            consolePresent
        );

        int exitCode = Main.commandLine(main).execute(args);

        return new Result(exitCode, out.toString(), err.toString());
    }

    static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String stripAnsi(String value) {
        return ANSI_ESCAPE.matcher(value).replaceAll("");
    }

    record Result(int exitCode, String out, String err) {}
}
