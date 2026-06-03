package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.FormatterOptions;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
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
    private final ConfigurableFileCollection sourceDirectories;
    private final ListProperty<String> includes;
    private final ListProperty<String> excludes;
    private final Property<Integer> lineWidth;
    private final Property<FormatterOptions.JavaLanguageLevel> javaLanguageLevel;
    private final DirectoryProperty projectDirectory;
    private final DirectoryProperty buildDirectory;

    @Inject
    public AbstractFrmtrJavaTask(ObjectFactory objects) {
        this.sourceDirectories = objects.fileCollection();
        this.includes = objects.listProperty(String.class).convention(List.of());
        this.excludes = objects.listProperty(String.class).convention(List.of());
        this.lineWidth = objects.property(Integer.class).convention(FormatterOptions.DEFAULT_LINE_WIDTH);
        this.javaLanguageLevel = objects.property(FormatterOptions.JavaLanguageLevel.class)
                .convention(FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        this.projectDirectory = objects.directoryProperty();
        this.buildDirectory = objects.directoryProperty();
    }

    @Internal
    public ConfigurableFileCollection getSourceDirectories() {
        return sourceDirectories;
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

    @Internal
    public DirectoryProperty getBuildDirectory() {
        return buildDirectory;
    }

    @SkipWhenEmpty
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getSourceFiles() {
        return getProject().files(selectedFiles());
    }

    protected FormatterOptions formatterOptions() {
        return new FormatterOptions(
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
        Path buildRoot = buildDirectory.get().getAsFile().toPath().toAbsolutePath().normalize();
        Set<Path> files = new LinkedHashSet<>();
        for (File sourceDirectory : sourceDirectories.getFiles()) {
            if (!sourceDirectory.isDirectory()) {
                continue;
            }
            FileTree tree = getProject().fileTree(sourceDirectory, spec -> {
                List<String> configuredIncludes = includes.get();
                spec.include(configuredIncludes.isEmpty() ? List.of("**/*.java") : configuredIncludes);
                spec.exclude(excludes.get());
            });
            tree.getFiles().stream()
                    .filter(File::isFile)
                    .map(file -> file.toPath().toAbsolutePath().normalize())
                    .filter(path -> !path.startsWith(buildRoot))
                    .forEach(files::add);
        }
        List<Path> sorted = new ArrayList<>(files);
        Path root = displayRoot().toAbsolutePath().normalize();
        sorted.sort(Comparator.comparing(path -> root.relativize(path).toString()));
        return sorted;
    }
}
