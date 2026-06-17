package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterRunFailureRenderer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileType;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.work.ChangeType;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.work.InputChanges;

@DisableCachingByDefault(
    because = "Concrete frmtr tasks define whether their source-processing action is cacheable."
)
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
        this.lineWidth = objects.property(Integer.class);
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
    @Optional
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
        FormatterOptions options = FormatterOptions.defaults().withJavaLanguageLevel(javaLanguageLevel.get());
        if (lineWidth.isPresent()) {
            options = options.withLineWidth(lineWidth.get());
        }
        return options;
    }

    protected Path displayRoot() {
        return projectDirectory.get().getAsFile().toPath();
    }

    protected List<Path> selectedFiles() {
        Path root = displayRoot().toAbsolutePath().normalize();
        return sourceFiles.getFiles()
                .stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .distinct()
                .sorted(Comparator.comparing(path -> displayPath(root, path).toString()))
                .toList();
    }

    protected List<Path> selectedFiles(InputChanges inputChanges) {
        if (!inputChanges.isIncremental()) {
            return selectedFiles();
        }

        Path root = displayRoot().toAbsolutePath().normalize();
        return StreamSupport.stream(inputChanges.getFileChanges(sourceFiles).spliterator(), false)
                .filter(change -> change.getChangeType() != ChangeType.REMOVED)
                .filter(change -> change.getFileType() == FileType.FILE)
                .map(change -> change.getFile().toPath().toAbsolutePath().normalize())
                .distinct()
                .sorted(Comparator.comparing(path -> displayPath(root, path).toString()))
                .toList();
    }

    protected void logSelectedFiles(String action, InputChanges inputChanges, List<Path> files) {
        String mode = inputChanges.isIncremental() ? "incremental" : "full";
        Path root = displayRoot().toAbsolutePath().normalize();
        List<String> displayPaths = files.stream()
                .map(path -> displayPath(root, path).toString())
                .toList();
        getLogger().info("frmtr {} {} run selected {} Java file(s): {}", action, mode, files.size(), displayPaths);
    }

    protected void clearMarker(RegularFileProperty marker) {
        try {
            Files.deleteIfExists(marker.get().getAsFile().toPath());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    protected void writeMarker(RegularFileProperty marker, String content) {
        Path markerPath = marker.get().getAsFile().toPath();
        try {
            Files.createDirectories(markerPath.getParent());
            Files.writeString(markerPath, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    protected void printFailures(FormatRunResult run) {
        if (run.hasFailures()) {
            getLogger().lifecycle(FormatterRunFailureRenderer.render(run));
        }
    }

    protected GradleException formatterFailure(String action, FormatRunResult run) {
        return new GradleException(
            "frmtr failed to %s %d Java file(s).".formatted(action, run.failureCount()),
            run.firstFailure().orElse(null)
        );
    }

    private Path displayPath(Path root, Path path) {
        if (path.startsWith(root)) {
            return root.relativize(path);
        }
        return path;
    }
}
