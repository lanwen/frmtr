package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileDiscoveryTest {

    @Test
    void excludesPathsAndGlobsDuringDiscovery(@TempDir Path dir) throws IOException {
        Path kept = dir.resolve("src/Kept.java");
        Path generated = dir.resolve("src/generated/Generated.java");
        Path fixture = dir.resolve("fixtures/Fixture.java");
        write(kept, "class Kept{int value;}");
        write(generated, "class Generated{int value;}");
        write(fixture, "class Fixture{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("."), List.of("src/generated, fixtures/**/*.java"));

        assertThat(result.files()).containsExactly(absolute(kept));
        assertThat(result.ignoredFiles()).isEmpty();
        assertThat(result.excludedFiles()).containsExactlyInAnyOrder(absolute(generated), absolute(fixture));
    }

    @Test
    void respectsGitignoreDuringDiscovery(@TempDir Path dir) throws IOException {
        Path kept = dir.resolve("kept/Kept.java");
        Path ignored = dir.resolve("ignored/Ignored.java");
        write(dir.resolve(".gitignore"), "ignored/\n");
        write(kept, "class Kept{int value;}");
        write(ignored, "class Ignored{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("."), List.of());

        assertThat(result.files()).containsExactly(absolute(kept));
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void absoluteExternalDirectorySelectorLoadsExternalGitignore(@TempDir Path dir) throws IOException {
        Path cwd = dir.resolve("frmtr");
        Path external = dir.resolve("external");
        Path ignored = external.resolve("build/Ignored.java");
        Path kept = external.resolve("src/Kept.java");
        write(external.resolve(".gitignore"), "build/\n");
        write(ignored, "class Ignored{int value;}");
        write(kept, "class Kept{int value;}");

        FileDiscovery.Result result = discover(cwd, List.of(external.toString()), List.of());

        assertThat(result.files()).containsExactly(absolute(kept));
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void absoluteExternalExcludeTakesPrecedenceOverExternalGitignore(@TempDir Path dir) throws IOException {
        Path cwd = dir.resolve("frmtr");
        Path external = dir.resolve("external");
        Path excluded = external.resolve("build/Excluded.java");
        Path ignored = external.resolve("cache/Ignored.java");
        Path kept = external.resolve("src/Kept.java");
        write(
            external.resolve(".gitignore"),
            """
                build/
                cache/
                """
        );
        write(excluded, "class Excluded{int value;}");
        write(ignored, "class Ignored{int value;}");
        write(kept, "class Kept{int value;}");

        FileDiscovery.Result result = discover(
            cwd,
            List.of(external.toString()),
            List.of(external.resolve("build").toString())
        );

        assertThat(result.files()).containsExactly(absolute(kept));
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).containsExactly(absolute(excluded));
    }

    @Test
    void externalSubdirectorySelectorInheritsParentGitignore(@TempDir Path dir) throws IOException {
        Path cwd = dir.resolve("frmtr");
        Path external = dir.resolve("external");
        Path selected = external.resolve("selected");
        Path ignored = selected.resolve("Ignored.java");
        Path kept = selected.resolve("Kept.java");
        write(external.resolve(".gitignore"), "/selected/Ignored.java\n");
        write(ignored, "class Ignored{int value;}");
        write(kept, "class Kept{int value;}");

        FileDiscovery.Result result = discover(cwd, List.of(selected.toString()), List.of());

        assertThat(result.files()).containsExactly(absolute(kept));
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void externalExplicitFileSelectorInheritsParentGitignore(@TempDir Path dir) throws IOException {
        Path cwd = dir.resolve("frmtr");
        Path external = dir.resolve("external");
        Path ignored = external.resolve("src/Ignored.java");
        write(external.resolve(".gitignore"), "/src/Ignored.java\n");
        write(ignored, "class Ignored{int value;}");

        FileDiscovery.Result result = discover(cwd, List.of(ignored.toString()), List.of());

        assertThat(result.files()).isEmpty();
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void absoluteExternalGlobMatchesAgainstExternalScope(@TempDir Path dir) throws IOException {
        Path cwd = dir.resolve("frmtr");
        Path external = dir.resolve("external");
        Path kept = external.resolve("src/Kept.java");
        Path nested = external.resolve("src/nested/Nested.java");
        Path ignored = external.resolve("src/Drop.java");
        Path outsideGlob = external.resolve("other/Other.java");
        write(external.resolve(".gitignore"), "/src/Drop.java\n");
        write(kept, "class Kept{int value;}");
        write(nested, "class Nested{int value;}");
        write(ignored, "class Drop{int value;}");
        write(outsideGlob, "class Other{int value;}");

        FileDiscovery.Result result = discover(cwd, List.of(external.resolve("src/**/*.java").toString()), List.of());

        assertThat(result.files()).containsExactlyInAnyOrder(absolute(kept), absolute(nested));
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void explicitFileSelectorLoadsSameDirectoryGitignore(@TempDir Path dir) throws IOException {
        Path ignored = dir.resolve("src/Ignored.java");
        write(dir.resolve("src/.gitignore"), "Ignored.java\n");
        write(ignored, "class Ignored{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("src/Ignored.java"), List.of());

        assertThat(result.files()).isEmpty();
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void directorySelectorLoadsParentGitignoreRules(@TempDir Path dir) throws IOException {
        Path kept = dir.resolve("selected/Kept.java");
        Path ignored = dir.resolve("selected/Ignored.java");
        write(dir.resolve(".gitignore"), "/selected/Ignored.java\n");
        write(kept, "class Kept{int value;}");
        write(ignored, "class Ignored{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("selected"), List.of());

        assertThat(result.files()).containsExactly(absolute(kept));
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignored));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void directorySelectorLoadsNestedGitignoreRulesWithDirectoryLocalScope(@TempDir Path dir) throws IOException {
        Path drop = dir.resolve("selected/nested/Drop.java");
        Path keep = dir.resolve("selected/nested/Keep.java");
        Path deepDrop = dir.resolve("selected/nested/deep/Drop.java");
        write(
            dir.resolve("selected/nested/.gitignore"),
            """
                /*.java
                !/Keep.java
                """
        );
        write(drop, "class Drop{int value;}");
        write(keep, "class Keep{int value;}");
        write(deepDrop, "class DeepDrop{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("selected"), List.of());

        assertThat(result.files()).containsExactlyInAnyOrder(absolute(keep), absolute(deepDrop));
        assertThat(result.ignoredFiles()).containsExactly(absolute(drop));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void globSelectorLoadsParentAndNestedGitignoreRules(@TempDir Path dir) throws IOException {
        Path rootIgnored = dir.resolve("src/rootIgnored/Ignored.java");
        Path drop = dir.resolve("src/nested/Drop.java");
        Path keep = dir.resolve("src/nested/Keep.java");
        Path other = dir.resolve("src/Other.java");
        write(dir.resolve(".gitignore"), "src/rootIgnored/\n");
        write(dir.resolve("src/nested/.gitignore"), "/Drop.java\n");
        write(rootIgnored, "class Ignored{int value;}");
        write(drop, "class Drop{int value;}");
        write(keep, "class Keep{int value;}");
        write(other, "class Other{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("src/**/*.java"), List.of());

        assertThat(result.files()).containsExactlyInAnyOrder(absolute(keep), absolute(other));
        assertThat(result.ignoredFiles()).containsExactlyInAnyOrder(absolute(rootIgnored), absolute(drop));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void excludeTakesPrecedenceOverGitignore(@TempDir Path dir) throws IOException {
        Path kept = dir.resolve("src/Kept.java");
        Path generated = dir.resolve("src/generated/Generated.java");
        write(dir.resolve(".gitignore"), "src/generated/\n");
        write(kept, "class Kept{int value;}");
        write(generated, "class Generated{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("."), List.of("src/generated"));

        assertThat(result.files()).containsExactly(absolute(kept));
        assertThat(result.ignoredFiles()).isEmpty();
        assertThat(result.excludedFiles()).containsExactly(absolute(generated));
    }

    private static FileDiscovery.Result discover(Path root, List<String> selectors, List<String> excludes)
        throws IOException {
        return new FileDiscovery(root).discover(selectors, excludes);
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
