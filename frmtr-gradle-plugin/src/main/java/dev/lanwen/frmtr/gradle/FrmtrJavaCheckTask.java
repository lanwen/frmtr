package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.check.FormatFileResult;
import dev.lanwen.frmtr.check.FormatterRunner;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

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
        List<FormatFileResult> results =
                FormatterRunner.check(displayRoot(), selectedFiles(), formatterOptions(), printDiffs.get());
        results.stream().filter(FormatFileResult::changed).forEach(this::printChanged);
        results.stream().filter(FormatFileResult::failed).forEach(this::printFailed);

        long failures = results.stream().filter(FormatFileResult::failed).count();
        if (failures > 0) {
            throw new GradleException("frmtr failed to check " + failures + " Java file(s).", firstFailure(results));
        }

        long changed = results.stream().filter(FormatFileResult::changed).count();
        if (changed > 0) {
            throw new GradleException(
                    "frmtr found " + changed + " unformatted Java file(s). Run ./gradlew frmtrFormat.");
        }
    }

    private void printChanged(FormatFileResult result) {
        getLogger().lifecycle("✗ {}", result.displayPath());
        result.unifiedDiff().ifPresent(diff -> getLogger().lifecycle(diff.stripTrailing()));
    }

    private void printFailed(FormatFileResult result) {
        getLogger().lifecycle("! {}", result.displayPath());
        result.failureException()
                .ifPresent(exception -> getLogger().lifecycle("{}: {}", result.displayPath(), exception.getMessage()));
    }

    private Exception firstFailure(List<FormatFileResult> results) {
        return results.stream()
                .flatMap(result -> result.failureException().stream())
                .findFirst()
                .orElse(null);
    }
}
