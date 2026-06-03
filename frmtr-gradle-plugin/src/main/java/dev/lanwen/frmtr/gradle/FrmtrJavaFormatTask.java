package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.check.FormatFileResult;
import dev.lanwen.frmtr.check.FormatterRunner;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskAction;

public abstract class FrmtrJavaFormatTask extends AbstractFrmtrJavaTask {
    @Inject
    public FrmtrJavaFormatTask(ObjectFactory objects) {
        super(objects);
    }

    @TaskAction
    public void format() {
        List<FormatFileResult> results = FormatterRunner.write(displayRoot(), selectedFiles(), formatterOptions());
        results.stream().filter(FormatFileResult::failed).forEach(this::printFailed);
        long failures = results.stream().filter(FormatFileResult::failed).count();
        if (failures > 0) {
            throw new GradleException("frmtr failed to format %d Java file(s).".formatted(failures), firstFailure(results));
        }
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
