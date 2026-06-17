package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.FormatterOptions;
import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

public final class FrmtrGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(LifecycleBasePlugin.class);
        FrmtrExtension extension = project.getExtensions().create("frmtr", FrmtrExtension.class);
        inheritParentConventions(project, extension);

        TaskProvider<Task> format = project.getTasks().register("frmtrFormat", task -> {
            task.setGroup("formatting");
            task.setDescription("Formats source files with frmtr.");
        });
        TaskProvider<Task> check = project.getTasks().register("frmtrCheck", task -> {
            task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
            task.setDescription("Checks source files are formatted with frmtr.");
        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(task -> task.dependsOn(check));

        project.getPlugins().withType(JavaPlugin.class, plugin -> configureJava(project, extension, format, check));

        if (project == project.getRootProject()) {
            configureSubprojects(project, format, check);
        }
    }

    private static void inheritParentConventions(Project project, FrmtrExtension extension) {
        Project parent = project.getParent();
        if (parent == null) {
            return;
        }
        FrmtrExtension parentExtension = parent.getExtensions().findByType(FrmtrExtension.class);
        if (parentExtension == null) {
            return;
        }

        extension.getEnabled().convention(parentExtension.getEnabled());
        extension.getJava().getIncludes().convention(parentExtension.getJava().getIncludes());
        extension.getJava().getExcludes().convention(parentExtension.getJava().getExcludes());
        extension.getJava().getLineWidth().convention(parentExtension.getJava().getLineWidth());
        extension.getJava().getLanguageLevel().convention(parentExtension.getJava().getLanguageLevel());
        extension.getCheck().getPrint().getDiffs().convention(parentExtension.getCheck().getPrint().getDiffs());
    }

    private static void configureSubprojects(
            Project project,
            TaskProvider<Task> rootFormat,
            TaskProvider<Task> rootCheck
    ) {
        for (Project subproject : project.getChildProjects().values()) {
            subproject.getPluginManager().apply(FrmtrGradlePlugin.class);
            subproject
                    .getPlugins()
                    .withType(JavaPlugin.class, plugin -> {
                        rootFormat.configure(task -> task.dependsOn(subproject.getTasks().named("frmtrFormat")));
                        rootCheck.configure(task -> task.dependsOn(subproject.getTasks().named("frmtrCheck")));
                    });
            configureSubprojects(subproject, rootFormat, rootCheck);
        }
    }

    private static void configureJava(
            Project project,
            FrmtrExtension extension,
            TaskProvider<Task> format,
            TaskProvider<Task> check
    ) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        Provider<List<File>> sourceFiles = project.provider(() -> sourceFiles(project, java, extension));
        Provider<FormatterOptions.JavaLanguageLevel> languageLevel = extension.getJava()
                .getLanguageLevel()
                .map(configured -> resolveJavaLanguageLevel(configured, java));

        TaskProvider<FrmtrJavaFormatTask> javaFormat = project.getTasks().register(
            "frmtrJavaFormat",
            FrmtrJavaFormatTask.class,
            task -> {
                task.setGroup("formatting");
                task.setDescription("Formats Java source files with frmtr.");
                task.onlyIf("frmtr is enabled", ignored -> extension.getEnabled().get());
                task.getSourceFiles().from(sourceFiles);
                task.getIncludes().set(extension.getJava().getIncludes());
                task.getExcludes().set(extension.getJava().getExcludes());
                task.getLineWidth().set(extension.getJava().getLineWidth());
                task.getJavaLanguageLevel().set(languageLevel);
                task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
            }
        );

        TaskProvider<FrmtrJavaCheckTask> javaCheck = project.getTasks().register(
            "frmtrJavaCheck",
            FrmtrJavaCheckTask.class,
            task -> {
                task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                task.setDescription("Checks Java source files are formatted with frmtr.");
                task.onlyIf("frmtr is enabled", ignored -> extension.getEnabled().get());
                task.getSourceFiles().from(sourceFiles);
                task.getIncludes().set(extension.getJava().getIncludes());
                task.getExcludes().set(extension.getJava().getExcludes());
                task.getLineWidth().set(extension.getJava().getLineWidth());
                task.getJavaLanguageLevel().set(languageLevel);
                task.getPrintDiffs().set(extension.getCheck().getPrint().getDiffs());
                task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
                task.getSuccessMarker().set(project.getLayout().getBuildDirectory().file("frmtr/java-check.success"));
            }
        );

        format.configure(task -> task.dependsOn(javaFormat));
        check.configure(task -> task.dependsOn(javaCheck));
    }

    private static List<File> sourceFiles(Project project, JavaPluginExtension java, FrmtrExtension extension) {
        Path projectRoot = project.getLayout().getProjectDirectory().getAsFile().toPath().toAbsolutePath().normalize();
        Path buildRoot = project
                .getLayout()
                .getBuildDirectory()
                .get()
                .getAsFile()
                .toPath()
                .toAbsolutePath()
                .normalize();
        Set<File> files = new TreeSet<>(Comparator.comparing(file -> displayPath(projectRoot, file)));
        List<String> includes = extension.getJava().getIncludes().get();
        List<String> excludes = extension.getJava().getExcludes().get();
        for (SourceSet sourceSet : java.getSourceSets()) {
            FileTree tree = sourceSet.getAllJava().matching(spec -> {
                if (!includes.isEmpty()) {
                    spec.include(includes);
                }
                spec.exclude(excludes);
            });
            tree.getFiles()
                    .stream()
                    .filter(File::isFile)
                    .filter(file -> !file.toPath().toAbsolutePath().normalize().startsWith(buildRoot))
                    .sorted(Comparator.comparing(file -> displayPath(projectRoot, file)))
                    .forEach(files::add);
        }
        return List.copyOf(files);
    }

    private static String displayPath(Path projectRoot, File file) {
        Path absolute = file.toPath().toAbsolutePath().normalize();
        if (absolute.startsWith(projectRoot)) {
            return projectRoot.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    private static FormatterOptions.JavaLanguageLevel resolveJavaLanguageLevel(
            FrmtrJavaLanguageLevel configured,
            JavaPluginExtension java
    ) {
        return switch (configured) {
            case AUTO -> inferJavaLanguageLevel(java);
            case LATEST_AVAILABLE -> FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE;
            case UNDEFINED -> FormatterOptions.JavaLanguageLevel.UNSET;
        };
    }

    private static FormatterOptions.JavaLanguageLevel inferJavaLanguageLevel(JavaPluginExtension java) {
        JavaVersion sourceCompatibility = java.getSourceCompatibility();
        if (sourceCompatibility != null) {
            return fromMajor(Integer.parseInt(sourceCompatibility.getMajorVersion()));
        }
        if (java.getToolchain().getLanguageVersion().isPresent()) {
            return fromMajor(java.getToolchain().getLanguageVersion().get().asInt());
        }
        return FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE;
    }

    private static FormatterOptions.JavaLanguageLevel fromMajor(int major) {
        try {
            return FormatterOptions.JavaLanguageLevel.valueOf("JAVA_" + major);
        } catch (IllegalArgumentException exception) {
            return FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE;
        }
    }
}
