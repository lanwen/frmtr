package dev.lanwen.frmtr.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class MainTest {
    @Test
    void formatsStdinToStdout() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                "class Demo{int value;}");

        int exitCode = new CommandLine(main).execute();

        assertEquals(0, exitCode);
        assertEquals("""
                class Demo {
                    int value;
                }
                """, out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void rejectsCheckAndWriteTogether() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "");

        int exitCode = new CommandLine(main).execute("--check", "--write", "src");

        assertEquals(2, exitCode);
        assertEquals("--check and --write cannot be used together\n", err.toString());
    }
}
