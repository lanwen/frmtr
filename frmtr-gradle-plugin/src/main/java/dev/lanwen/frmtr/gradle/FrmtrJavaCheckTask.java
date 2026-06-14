package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.tooling.FormatFileResult;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterRunner;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Formatting checks produce diagnostics instead of reusable task outputs.")
public abstract class FrmtrJavaCheckTask extends AbstractFrmtrJavaTask {

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

    @TaskAction
    public void checkFormatting() {
        FormatRunResult run = FormatterRunner.check(
            displayRoot(),
            selectedFiles(),
            formatterOptions(),
            printDiffs.get()
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
    }

    private void printChanged(FormatFileResult result) {
        getLogger().lifecycle("✗ {}", result.displayPath());
        result.unifiedDiff().ifPresent(diff -> getLogger().lifecycle(diff.stripTrailing()));
    }
}
