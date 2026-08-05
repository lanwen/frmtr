package dev.example;

class Demo {

    void method() {
        await().untilAsserted(() -> {
            assertThat(something.isSuccess()).as("success").isTrue();
            something.getValue().tell(new Command.Check(firstValue, secondValue, responseTarget.getRef()));
        });
        Worker.current().start(() -> {
            runFirstStep();
            // keep comment inside block lambda
            runSecondStep();
        });
        TaskProvider<Task> format = project.getTasks()
                .register("formatSource", task -> {
                    task.setGroup("formatting");
                    task.setDescription("Formats source files with tool.");
                });
        TaskProvider<SourceBundleFormatTask> bundleFormat = workspace.getActions()
                .register("sourceBundleFormat", SourceBundleFormatTask.class, action -> {
                    action.setGroup("formatting");
                    action.setDescription("Formats source bundle files with tool.");
                    action.getSourceFiles().from(sourceFiles);
                    action.getIncludes().set(extension.getJava().getIncludes());
                    action.getExcludes().set(extension.getJava().getExcludes());
                    action.getLineWidth().set(extension.getJava().getLineWidth());
                    action.getLanguageLevel().set(languageLevel);
                    action.getProjectDirectory().set(workspace.getLayout().getProjectDirectory());
                });
    }
}
