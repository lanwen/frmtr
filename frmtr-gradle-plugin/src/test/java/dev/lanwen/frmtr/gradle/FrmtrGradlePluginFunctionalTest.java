package dev.lanwen.frmtr.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrmtrGradlePluginFunctionalTest {

    @TempDir
    private Path projectDir;

    @Test
    void registersProjectLocalTasksInNonJavaProjects() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    id("dev.lanwen.frmtr")
                }
                """
        );

        BuildResult tasks = gradle("tasks", "--all").build();
        BuildResult check = gradle("frmtrCheck").build();

        assertThat(tasks.getOutput())
                .contains("frmtrFormat")
                .contains("frmtrCheck")
                .doesNotContain("frmtrJavaFormat")
                .doesNotContain("frmtrJavaCheck");
        assertThat(check.task(":frmtrCheck").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    @Test
    void checksAndFormatsJavaSourceSetsWithZeroConfiguration() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }
                """
        );
        write("src/main/java/demo/Main.java", "package demo; class Main{int value;}");

        BuildResult failedCheck = gradle("frmtrCheck").buildAndFail();

        assertThat(failedCheck.getOutput())
                .contains("✗ src/main/java/demo/Main.java")
                .contains("diff --git origin frmtr")
                .contains("--- origin\n+++ frmtr")
                .doesNotContain("a/src/main/java/demo/Main.java")
                .doesNotContain("b/src/main/java/demo/Main.java")
                .contains("-package demo; class Main{int value;}")
                .doesNotContain("✓ src/main/java/demo/Main.java");

        BuildResult format = gradle("frmtrFormat").build();

        assertThat(format.task(":frmtrJavaFormat").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(read("src/main/java/demo/Main.java")).isEqualTo(
            """
                package demo;

                class Main {

                    int value;
                }
                """
        );

        BuildResult passedCheck = gradle("frmtrCheck").build();

        assertThat(passedCheck.task(":frmtrJavaCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(passedCheck.getOutput()).doesNotContain("✓ src/main/java/demo/Main.java");
    }

    @Test
    void wiresFrmtrCheckIntoGradleCheckLifecycle() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }
                """
        );
        write("src/main/java/demo/Main.java", "package demo; class Main{int value;}");

        BuildResult result = gradle("check").buildAndFail();

        assertThat(result.task(":frmtrJavaCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput()).contains("frmtr found 1 unformatted Java file(s)");
    }

    @Test
    void rootPluginAggregatesJavaSubprojectTasks() {
        writeSettings("api", "docs");
        writeBuildFile(
            """
                plugins {
                    id("dev.lanwen.frmtr")
                }
                """
        );
        write(
            "api/build.gradle.kts",
            """
                plugins {
                    java
                }
                """
        );
        write("docs/build.gradle.kts", "");
        write("api/src/main/java/demo/Api.java", "package demo; class Api{int value;}");

        BuildResult result = gradle("frmtrCheck").buildAndFail();

        assertThat(result.task(":api:frmtrJavaCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput())
                .contains("✗ src/main/java/demo/Api.java")
                .contains("frmtr found 1 unformatted Java file(s)");
    }

    @Test
    void subprojectFrmtrBlockOverridesRootConventions() {
        writeSettings("api", "service");
        writeBuildFile(
            """
                plugins {
                    id("dev.lanwen.frmtr")
                }

                frmtr {
                    check {
                        print {
                            diffs.set(false)
                        }
                    }
                }
                """
        );
        write(
            "api/build.gradle.kts",
            """
                plugins {
                    java
                }
                """
        );
        write(
            "service/build.gradle.kts",
            """
                plugins {
                    java
                }

                frmtr {
                    check {
                        print {
                            diffs.set(true)
                        }
                    }
                }
                """
        );
        write("api/src/main/java/demo/Api.java", "package demo; class Api{int value;}");
        write("service/src/main/java/demo/Service.java", "package demo; class Service{int value;}");

        BuildResult inheritedRootConfig = gradle(":api:frmtrCheck").buildAndFail();
        BuildResult overriddenModuleConfig = gradle(":service:frmtrCheck").buildAndFail();

        assertThat(inheritedRootConfig.getOutput())
                .contains("✗ src/main/java/demo/Api.java")
                .doesNotContain("diff --git");
        assertThat(overriddenModuleConfig.getOutput())
                .contains("✗ src/main/java/demo/Service.java")
                .contains("diff --git origin frmtr");
    }

    @Test
    void subprojectCanDisableFrmtrInheritedFromRootPlugin() {
        writeSettings("api");
        writeBuildFile(
            """
                plugins {
                    id("dev.lanwen.frmtr")
                }
                """
        );
        write(
            "api/build.gradle.kts",
            """
                plugins {
                    java
                }

                frmtr {
                    enabled = false
                }
                """
        );
        write("api/src/main/java/demo/Api.java", "package demo; class Api{int value;}");

        BuildResult result = gradle("frmtrCheck").build();

        assertThat(result.task(":api:frmtrJavaCheck").getOutcome()).isEqualTo(TaskOutcome.SKIPPED);
    }

    @Test
    void javaFiltersUseSourceRootRelativeGradlePatterns() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }

                frmtr {
                    java {
                        include("**/Included.java")
                        exclude("**/Excluded.java")
                    }
                }
                """
        );
        write("src/main/java/demo/Included.java", "package demo; class Included{int value;}");
        write("src/main/java/demo/Excluded.java", "package demo; class Excluded{int value;}");
        write("src/main/java/demo/Skipped.java", "package demo; class Skipped{int value;}");

        BuildResult result = gradle("frmtrFormat").build();

        assertThat(result.task(":frmtrJavaFormat").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(read("src/main/java/demo/Included.java")).isEqualTo(
            """
                package demo;

                class Included {

                    int value;
                }
                """
        );
        assertThat(read("src/main/java/demo/Excluded.java")).isEqualTo("package demo; class Excluded{int value;}");
        assertThat(read("src/main/java/demo/Skipped.java")).isEqualTo("package demo; class Skipped{int value;}");
    }

    @Test
    void honorsJavaSourceSetExcludes() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }

                sourceSets {
                    main {
                        java {
                            exclude("**/ExcludedBySourceSet.java")
                        }
                    }
                }
                """
        );
        write("src/main/java/demo/IncludedBySourceSet.java", "package demo; class IncludedBySourceSet{int value;}");
        write("src/main/java/demo/ExcludedBySourceSet.java", "package demo; class ExcludedBySourceSet{int value;}");

        BuildResult result = gradle("frmtrFormat").build();

        assertThat(result.task(":frmtrJavaFormat").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(read("src/main/java/demo/IncludedBySourceSet.java")).isEqualTo(
            """
                package demo;

                class IncludedBySourceSet {

                    int value;
                }
                """
        );
        assertThat(read("src/main/java/demo/ExcludedBySourceSet.java")).isEqualTo(
            "package demo; class ExcludedBySourceSet{int value;}"
        );
    }

    @Test
    void excludesBuildDirectorySourcesByDefault() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }

                sourceSets {
                    main {
                        java.srcDir(layout.buildDirectory.dir("generated/sources/demo"))
                    }
                }
                """
        );
        write("build/generated/sources/demo/demo/Generated.java", "package demo; class Generated{int value;}");

        BuildResult result = gradle("frmtrCheck").build();

        assertThat(result.getOutput()).doesNotContain("Generated.java");
        assertThat(result.task(":frmtrJavaCheck").getOutcome()).isEqualTo(TaskOutcome.NO_SOURCE);
    }

    @Test
    void canDisableCheckDiffPrinting() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }

                frmtr {
                    check {
                        print {
                            diffs.set(false)
                        }
                    }
                }
                """
        );
        write("src/main/java/demo/Main.java", "package demo; class Main{int value;}");

        BuildResult result = gradle("frmtrCheck").buildAndFail();

        assertThat(result.getOutput())
                .contains("✗ src/main/java/demo/Main.java")
                .doesNotContain("diff --git");
    }

    @Test
    void infersJavaLanguageLevelFromSourceCompatibility() {
        writeSettings();
        writeBuildFile(
            """
                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }

                java {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                }
                """
        );
        write("src/main/java/demo/TextBlockDemo.java", textBlockSource());

        BuildResult result = gradle("frmtrCheck").buildAndFail();

        assertThat(result.getOutput())
                .contains("┌─ Unable to parse Java source:")
                .contains("\"\"\"")
                .contains("│ 2  class TextBlockDemo {")
                .contains("│    │")
                .contains("^")
                .contains("Text Block Literals are not supported");
    }

    @Test
    void explicitLatestAvailableLanguageLevelOverridesSourceCompatibility() {
        writeSettings();
        writeBuildFile(
            """
                import dev.lanwen.frmtr.gradle.FrmtrJavaLanguageLevel

                plugins {
                    java
                    id("dev.lanwen.frmtr")
                }

                java {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                }

                frmtr {
                    java {
                        languageLevel.set(FrmtrJavaLanguageLevel.LATEST_AVAILABLE)
                    }
                }
                """
        );
        write("src/main/java/demo/SwitchDemo.java", switchExpressionYieldSource());

        BuildResult result = gradle("frmtrFormat").build();

        assertThat(result.task(":frmtrJavaFormat").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(read("src/main/java/demo/SwitchDemo.java")).contains("yield new Created(cmd.id());");
    }

    private GradleRunner gradle(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private void writeSettings() {
        writeSettings(new String[0]);
    }

    private void writeSettings(String... projects) {
        StringBuilder settings = new StringBuilder("rootProject.name = \"fixture\"\n");
        for (String project : projects) {
            settings.append("include(\"").append(project).append("\")\n");
        }
        write("settings.gradle.kts", settings.toString());
    }

    private void writeBuildFile(String content) {
        write("build.gradle.kts", content);
    }

    private String read(String path) {
        try {
            return Files.readString(projectDir.resolve(path), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void write(String path, String content) {
        try {
            Path file = projectDir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String switchExpressionYieldSource() {
        return """
                package demo;
                class SwitchDemo {
                    Object map(Command command) {
                        return switch (command) {
                            case CreateCommand cmd -> {
                                yield new Created(cmd.id());
                            }
                            case DeleteCommand cmd -> new Deleted(cmd.id());
                        };
                    }
                }""";
    }

    private static String textBlockSource() {
        return """
                package demo;
                class TextBlockDemo {
                    String value = \"""
                            text
                            \""";
                }""";
    }
}
