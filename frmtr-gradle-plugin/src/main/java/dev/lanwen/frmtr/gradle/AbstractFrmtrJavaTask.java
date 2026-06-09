package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterRunFailureRenderer;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;

public abstract class AbstractFrmtrJavaTask extends DefaultTask {
    private final ConfigurableFileCollection sourceFiles;
    private final ListProperty<String> includes;
    private final ListProperty<String> excludes;
    private final Property<Integer> lineWidth;
    private final Property<FormatterOptions.JavaLanguageLevel> javaLanguageLevel;
    private final DirectoryProperty projectDirectory;

    @Inject
    public AbstractFrmtrJavaTask(ObjectFactory objects) {
        this.sourceFiles = objects.fileCollection();
        this.includes = objects.listProperty(String.class).convention(List.of());
        this.excludes = objects.listProperty(String.class).convention(List.of());
        this.lineWidth = objects.property(Integer.class).convention(FormatterOptions.DEFAULT_LINE_WIDTH);
        this.javaLanguageLevel = objects.property(FormatterOptions.JavaLanguageLevel.class)
                .convention(FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        this.projectDirectory = objects.directoryProperty();
    }

    @SkipWhenEmpty
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public ConfigurableFileCollection getSourceFiles() {
        return sourceFiles;
    }

    @Input
    public ListProperty<String> getIncludes() {
        return includes;
    }

    @Input
    public ListProperty<String> getExcludes() {
        return excludes;
    }

    @Input
    public Property<Integer> getLineWidth() {
        return lineWidth;
    }

    @Input
    public Property<FormatterOptions.JavaLanguageLevel> getJavaLanguageLevel() {
        return javaLanguageLevel;
    }

    @Internal
    public DirectoryProperty getProjectDirectory() {
        return projectDirectory;
    }

    protected FormatterOptions formatterOptions() {
        return FormatterOptions.withJavaLanguageLevel(
                lineWidth.get(),
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true,
                javaLanguageLevel.get());
    }

    protected Path displayRoot() {
        return projectDirectory.get().getAsFile().toPath();
    }

    protected List<Path> selectedFiles() {
        Path root = displayRoot().toAbsolutePath().normalize();
        return sourceFiles.getFiles().stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .distinct()
                .sorted(Comparator.comparing(path -> displayPath(root, path).toString()))
                .toList();
    }

    protected void printFailures(FormatRunResult run) {
        if (run.hasFailures()) {
            getLogger().lifecycle(FormatterRunFailureRenderer.render(run));
        }
    }

    protected GradleException formatterFailure(String action, FormatRunResult run) {
        return new GradleException(
                "frmtr failed to %s %d Java file(s).".formatted(action, run.failureCount()),
                run.firstFailure().orElse(null));
    }

    private Path displayPath(Path root, Path path) {
        if (path.startsWith(root)) {
            return root.relativize(path);
        }
        return path;
    }
}
