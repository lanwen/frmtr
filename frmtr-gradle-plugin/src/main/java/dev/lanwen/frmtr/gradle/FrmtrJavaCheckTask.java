package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.tooling.FormatFileResult;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterRunner;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.InputChanges;

@CacheableTask
public abstract class FrmtrJavaCheckTask extends AbstractFrmtrJavaTask {

    private static final String SUCCESS_MARKER = "frmtr-java-check-success\n";

    private final Property<Boolean> printDiffs;

    @Inject
    public FrmtrJavaCheckTask(ObjectFactory objects) {
        super(objects);
        this.printDiffs = objects.property(Boolean.class).convention(true);
    }

    @Input
    public Property<Boolean> getPrintDiffs() {
        return printDiffs;
    }

    @OutputFile
    public abstract RegularFileProperty getSuccessMarker();

    @TaskAction
    public void checkFormatting(InputChanges inputChanges) {
        clearMarker(getSuccessMarker());
        List<Path> files = selectedFiles(inputChanges);
        logSelectedFiles("check", inputChanges, files);
        FormatRunResult run = FormatterRunner.check(
            displayRoot(),
            files,
            formatterOptions(),
            printDiffs.get(),
            state -> {}
        );
        run.changedResults().forEach(this::printChanged);
        printFailures(run);

        if (run.hasFailures()) {
            throw formatterFailure("check", run);
        }

        if (run.hasChanges()) {
            throw new GradleException(
                "frmtr found %d unformatted Java file(s). Run ./gradlew frmtrFormat."
                        .formatted(run.changedCount())
            );
        }

        writeMarker(getSuccessMarker(), SUCCESS_MARKER);
    }

    private void printChanged(FormatFileResult result) {
        getLogger().lifecycle("✗ {}", result.displayPath());
        result.unifiedDiff().ifPresent(diff -> getLogger().lifecycle(diff.stripTrailing()));
    }
}
