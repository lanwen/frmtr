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
    void selectedSymlinkDirectoryIsNotTraversed(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("target");
        Path hidden = target.resolve("Hidden.java");
        Path selected = dir.resolve("selected");
        write(hidden, "class Hidden{int value;}");
        Files.createSymbolicLink(selected, target);

        FileDiscovery.Result result = discover(dir, List.of("selected"), List.of());

        assertThat(result.files()).isEmpty();
        assertThat(result.ignoredFiles()).isEmpty();
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void symlinkedJavaFileUnderSelectedDirectoryIsSelected(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("target/Linked.java");
        Path link = dir.resolve("selected/Linked.java");
        write(target, "class Linked{int value;}");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);

        FileDiscovery.Result result = discover(dir, List.of("selected"), List.of());

        assertThat(result.files()).containsExactly(absolute(link));
        assertThat(result.ignoredFiles()).isEmpty();
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
    void nestedGitignoreRulesDoNotLeakIntoSiblingDirectoryContexts(@TempDir Path dir) throws IOException {
        Path leftDrop = dir.resolve("src/left/Drop.java");
        Path rightKeep = dir.resolve("src/right/Keep.java");
        write(dir.resolve("src/left/.gitignore"), "*.java\n");
        write(leftDrop, "class Drop{int value;}");
        write(rightKeep, "class Keep{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("src"), List.of());

        assertThat(result.files()).containsExactly(absolute(rightKeep));
        assertThat(result.ignoredFiles()).containsExactly(absolute(leftDrop));
        assertThat(result.excludedFiles()).isEmpty();
    }

    @Test
    void selectedSiblingSubtreesInheritParentGitignoreRules(@TempDir Path dir) throws IOException {
        Path oneDrop = dir.resolve("src/one/Drop.java");
        Path oneKeep = dir.resolve("src/one/Keep.java");
        Path twoDrop = dir.resolve("src/two/Drop.java");
        Path twoKeep = dir.resolve("src/two/Keep.java");
        write(
            dir.resolve("src/.gitignore"),
            """
                /one/Drop.java
                /two/Drop.java
                """
        );
        write(oneDrop, "class Drop{int value;}");
        write(oneKeep, "class Keep{int value;}");
        write(twoDrop, "class Drop{int value;}");
        write(twoKeep, "class Keep{int value;}");

        FileDiscovery.Result result = discover(dir, List.of("src/one", "src/two"), List.of());

        assertThat(result.files()).containsExactlyInAnyOrder(absolute(oneKeep), absolute(twoKeep));
        assertThat(result.ignoredFiles()).containsExactlyInAnyOrder(absolute(oneDrop), absolute(twoDrop));
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

    @Test
    void directoryTraversalReturnsSortedSelectionsWhenSharedQueueSaturates(@TempDir Path dir) throws IOException {
        Path zebra = dir.resolve("src/z/Zebra.java");
        Path alpha = dir.resolve("src/a/Alpha.java");
        Path middle = dir.resolve("src/m/Middle.java");
        Path ignoredZebra = dir.resolve("ignored/z/Zebra.java");
        Path ignoredAlpha = dir.resolve("ignored/a/Alpha.java");
        Path excludedZebra = dir.resolve("generated/z/Zebra.java");
        Path excludedAlpha = dir.resolve("generated/a/Alpha.java");
        write(dir.resolve(".gitignore"), "ignored/\n");
        write(zebra, "class Zebra{int value;}");
        write(alpha, "class Alpha{int value;}");
        write(middle, "class Middle{int value;}");
        write(ignoredZebra, "class Zebra{int value;}");
        write(ignoredAlpha, "class Alpha{int value;}");
        write(excludedZebra, "class Zebra{int value;}");
        write(excludedAlpha, "class Alpha{int value;}");

        FileDiscovery.Result result = discoverWithBounds(dir, List.of("."), List.of("generated"), 1, 1);

        assertThat(result.files()).containsExactly(absolute(alpha), absolute(middle), absolute(zebra));
        assertThat(result.ignoredFiles()).containsExactly(absolute(ignoredAlpha), absolute(ignoredZebra));
        assertThat(result.excludedFiles()).containsExactly(absolute(excludedAlpha), absolute(excludedZebra));
    }

    private static FileDiscovery.Result discover(
            Path root, List<String> selectors, List<String> excludes
    ) throws IOException {
        return new FileDiscovery(root).discover(selectors, excludes);
    }

    private static FileDiscovery.Result discoverWithBounds(
            Path root, List<String> selectors, List<String> excludes, int directoryWorkers, int directoryQueueCapacity
    ) throws IOException {
        return new FileDiscovery(root, directoryWorkers, directoryQueueCapacity).discover(selectors, excludes);
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
