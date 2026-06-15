package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.tooling.FormatRunResult;
import dev.lanwen.frmtr.tooling.FormatterRunner;
import javax.inject.Inject;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Formatting rewrites source files in place.")
public abstract class FrmtrJavaFormatTask extends AbstractFrmtrJavaTask {

    @Inject
    public FrmtrJavaFormatTask(ObjectFactory objects) {
        super(objects);
    }

    @TaskAction
    public void format() {
        FormatRunResult run = FormatterRunner.write(displayRoot(), selectedFiles(), formatterOptions(), state -> {});
        printFailures(run);
        if (run.hasFailures()) {
            throw formatterFailure("format", run);
        }
    }
}
