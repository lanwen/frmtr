package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledInNativeImage;

@EnabledInNativeImage
final class NativeCompatibilityTest {

    @Test
    void formatsFieldDeclarationAndSwitchYieldSyntaxWithDefaultLanguageLevelInNativeImage() {
        Result result = run();

        assertFormatsFieldDeclarationAndSwitchYield(result);
    }

    @Test
    void formatsFieldDeclarationAndSwitchYieldSyntaxWithJava25LanguageLevelInNativeImage() {
        Result result = run("--java-level", "25");

        assertFormatsFieldDeclarationAndSwitchYield(result);
    }

    private static Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), fixture());

        int exitCode = Main.commandLine(main).execute(args);

        return new Result(exitCode, out.toString(), err.toString());
    }

    private static void assertFormatsFieldDeclarationAndSwitchYield(Result result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out())
                .contains("int first, second;")
                .contains("yield");
    }

    private static String fixture() {
        try (InputStream input = NativeCompatibilityTest.class.getResourceAsStream(
                "/native-compatibility/field-declaration-switch-yield.java"
        )) {
            if (input == null) {
                throw new IllegalStateException("Missing native compatibility fixture");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Result(int exitCode, String out, String err) {}
}
